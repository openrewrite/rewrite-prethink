/*
 * Copyright 2025 the original author or authors.
 * <p>
 * Licensed under the Moderne Source Available License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://docs.moderne.io/licensing/moderne-source-available-license
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.prethink;

import com.univocity.parsers.csv.CsvWriter;
import com.univocity.parsers.csv.CsvWriterSettings;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.text.PlainText;

import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static org.openrewrite.prethink.Prethink.CONTEXT_DIR;

/**
 * Export DataTables to CSV files in .moderne/context/ along with a markdown description.
 * <p>
 * This recipe exports data tables from a single recipe context and generates:
 * - CSV files for each data table
 * - A markdown file describing the context with data table schemas
 * <p>
 * The markdown file is named using the kebab-cased short name (e.g., test-coverage.md)
 * and includes the display name, descriptions, and a schema for each data table.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class ExportContext extends ScanningRecipe<ExportContext.Accumulator> {

    @Option(displayName = "Display name",
            description = "The display name for this context, shown in agent configurations.",
            example = "Test Coverage")
    String displayName;

    @Option(displayName = "Short description",
            description = "A brief description of what context this provides to the model.",
            example = "Maps test methods to implementation methods they verify")
    String shortDescription;

    @Option(displayName = "Long description",
            description = "A detailed description of the context and how to use it.",
            example = "This context maps each test method to the implementation methods it calls...")
    String longDescription;

    @Option(displayName = "Data tables to export",
            description = "Fully qualified class names of DataTables to export to CSV.",
            example = "org.openrewrite.prethink.table.TestMapping")
    List<String> dataTables;

    @Override
    public String getDisplayName() {
        return "Export context files";
    }

    String description = "Export DataTables to CSV files in `.moderne/context/` along with a markdown " +
            "description file. The markdown file describes the context and includes schema " +
            "information for each data table.";

    @Override
    public boolean causesAnotherCycle() {
        return true;
    }

    public static class Accumulator {
        private final Set<Path> existingContextPaths = new HashSet<>();

        public Set<Path> getExistingContextPaths() {
            return existingContextPaths;
        }

        // Aggregated + rendered exactly once in the export phase (cycle >= 2), then
        // reused for every file visit and any later cycle. Producers only write data
        // tables in cycle <= 1, so the tables are frozen by then and caching is safe.
        // `csvByFilename != null` is the "already rendered" sentinel.
        @Nullable
        volatile Map<String, String> csvByFilename;
        @Nullable
        volatile String markdown;
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    /**
     * Aggregate the referenced data tables and render their CSV + markdown output
     * exactly once per run; subsequent calls are no-ops. This replaces re-reading
     * every table from disk in generate(), again in getVisitor() once per visited
     * context file, and again in the forced extra cycle.
     */
    private void renderOnce(Accumulator acc, ExecutionContext ctx) {
        if (acc.csvByFilename != null) {
            return;
        }
        synchronized (acc) {
            if (acc.csvByFilename != null) {
                return;
            }
            DataTableStore store = DataTableExecutionContextView.view(ctx).getDataTableStore();

            // Group the present DataTable instances by class without reading any rows.
            // Multiple recipes may write the same table type (e.g. FindTestCoverage and
            // FindNodeTestCoverage both produce TestMapping); their rows are concatenated.
            Map<String, List<DataTable<?>>> instancesByFqn = new HashMap<>();
            for (DataTable<?> dt : store.getDataTables()) {
                String tableFqn = dt.getClass().getName();
                if (dataTables.contains(tableFqn)) {
                    instancesByFqn.computeIfAbsent(tableFqn, k -> new ArrayList<>()).add(dt);
                }
            }

            Map<String, String> rendered = new LinkedHashMap<>();
            List<DataTableInfo> exportedTables = new ArrayList<>();
            // Iterate in the declared dataTables order for deterministic output.
            for (String tableFqn : dataTables) {
                List<DataTable<?>> instances = instancesByFqn.get(tableFqn);
                if (instances == null || instances.isEmpty()) {
                    continue;
                }
                DataTable<?> representative = instances.get(0);
                rendered.put(tableToFilename(tableFqn), streamToCsv(store, representative, instances));
                exportedTables.add(new DataTableInfo(
                        representative.getDisplayName(),
                        representative.getDescription(),
                        tableToFilename(tableFqn),
                        getColumnInfo(representative)
                ));
            }
            acc.markdown = exportedTables.isEmpty() ? null : generateMarkdown(exportedTables);
            // Publish last: readers that observe a non-null map also observe `markdown`
            // and the fully-built map contents (volatile happens-before).
            acc.csvByFilename = rendered;
        }
    }

    /**
     * Render one data table to CSV by streaming its rows straight from the store into
     * the writer one row at a time, never materializing the full row set in memory.
     * Rows from every instance of the same table class are concatenated. The row stream
     * is closed after each instance so a lazy {@link DataTableStore} can release the
     * underlying file handle.
     */
    @SuppressWarnings("unchecked")
    private String streamToCsv(DataTableStore store, DataTable<?> representative, List<DataTable<?>> instances) {
        List<Field> columnFields = getColumnFields(representative.getType());
        String[] headers = columnFields.stream()
                .map(f -> f.getAnnotation(Column.class).displayName())
                .toArray(String[]::new);

        StringWriter stringWriter = new StringWriter();
        CsvWriter writer = new CsvWriter(stringWriter, new CsvWriterSettings());
        writer.writeHeaders(headers);

        String[] values = new String[columnFields.size()];
        for (DataTable<?> instance : instances) {
            Class<? extends DataTable<Object>> dtClass = (Class<? extends DataTable<Object>>) instance.getClass();
            try (Stream<Object> rows = store.getRows(dtClass, instance.getGroup())) {
                rows.forEach(row -> {
                    for (int i = 0; i < columnFields.size(); i++) {
                        Field field = columnFields.get(i);
                        try {
                            field.setAccessible(true);
                            Object value = field.get(row);
                            values[i] = value == null ? "" : value.toString();
                        } catch (IllegalAccessException e) {
                            values[i] = "";
                        }
                    }
                    writer.writeRow((Object[]) values);
                });
            }
        }

        writer.close();
        return stringWriter.toString();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    SourceFile sf = (SourceFile) tree;
                    Path path = sf.getSourcePath();
                    // Track existing context files so we can update them
                    if (path.startsWith(CONTEXT_DIR)) {
                        acc.getExistingContextPaths().add(path);
                    }
                }
                return tree;
            }
        };
    }

    @Override
    public Collection<SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        // Skip first cycle - let data table producers complete
        if (ctx.getCycle() == 1) {
            return emptyList();
        }

        // Aggregate + render exactly once; reused by getVisitor() and any later cycle.
        renderOnce(acc, ctx);

        List<SourceFile> contextFiles = new ArrayList<>();
        Map<String, String> csvByFilename = acc.csvByFilename;
        if (csvByFilename == null || csvByFilename.isEmpty()) {
            return contextFiles;
        }

        for (Map.Entry<String, String> entry : csvByFilename.entrySet()) {
            Path filePath = CONTEXT_DIR.resolve(entry.getKey());
            // Only generate if file doesn't already exist
            if (!acc.getExistingContextPaths().contains(filePath)) {
                contextFiles.add(PlainText.builder()
                        .text(entry.getValue())
                        .sourcePath(filePath)
                        .build());
            }
        }

        // Generate the markdown description file
        String markdown = acc.markdown;
        if (markdown != null) {
            Path mdPath = CONTEXT_DIR.resolve(toKebabCase(displayName) + ".md");
            if (!acc.getExistingContextPaths().contains(mdPath)) {
                contextFiles.add(PlainText.builder()
                        .text(markdown)
                        .sourcePath(mdPath)
                        .build());
            }
        }

        return contextFiles;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                // Skip first cycle: data tables aren't populated until producer recipes' visitors
                // run, and generate also defers to cycle 2. Request another cycle so the scheduler
                // doesn't terminate the loop after cycle 1 when no other recipe makes changes.
                if (ctx.getCycle() == 1) {
                    ctx.putMessage(Prethink.CYCLE_TRIGGER, true);
                    return tree;
                }
                if (tree instanceof PlainText) {
                    PlainText pt = (PlainText) tree;
                    Path path = pt.getSourcePath();

                    if (path.startsWith(CONTEXT_DIR)) {
                        // Reuse the once-rendered output instead of re-aggregating per file.
                        renderOnce(acc, ctx);
                        String filename = path.getFileName().toString();

                        // Update CSV files
                        if (filename.endsWith(".csv")) {
                            Map<String, String> csvByFilename = acc.csvByFilename;
                            String newContent = csvByFilename == null ? null : csvByFilename.get(filename);
                            if (newContent != null && !newContent.equals(pt.getText())) {
                                return pt.withText(newContent);
                            }
                        } else if (filename.equals(toKebabCase(displayName) + ".md")) {
                            // Update markdown file
                            String markdown = acc.markdown;
                            if (markdown != null && !markdown.equals(pt.getText())) {
                                return pt.withText(markdown);
                            }
                        }
                    }
                }
                return tree;
            }
        };
    }

    /**
     * Get the kebab-cased filename for this context's markdown file.
     */
    public String getContextFilename() {
        return toKebabCase(displayName) + ".md";
    }

    private String generateMarkdown(List<DataTableInfo> tables) {
        StringBuilder sb = new StringBuilder();

        // Title
        sb.append("# ").append(displayName).append("\n\n");

        // Short description as subheading
        sb.append("## ").append(shortDescription).append("\n\n");

        // Long description
        sb.append(longDescription).append("\n\n");

        // Data tables section
        sb.append("## Data Tables\n\n");

        for (DataTableInfo table : tables) {
            sb.append("### ").append(table.displayName).append("\n\n");
            sb.append("**File:** [`").append(table.filename).append("`](").append(table.filename).append(")\n\n");
            sb.append(table.description).append("\n\n");

            // Column schema table
            if (!table.columns.isEmpty()) {
                sb.append("| Column | Description |\n");
                sb.append("|--------|-------------|\n");
                for (ColumnInfo col : table.columns) {
                    sb.append("| ").append(col.displayName).append(" | ").append(col.description).append(" |\n");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private List<ColumnInfo> getColumnInfo(DataTable<?> table) {
        List<ColumnInfo> columns = new ArrayList<>();
        for (Field field : table.getType().getDeclaredFields()) {
            Column columnAnnotation = field.getAnnotation(Column.class);
            if (columnAnnotation != null) {
                columns.add(new ColumnInfo(columnAnnotation.displayName(), columnAnnotation.description()));
            }
        }
        return columns;
    }

    private String tableToFilename(String tableFqn) {
        // org.openrewrite.prethink.table.MethodDescriptions -> method-descriptions.csv
        String simpleName = tableFqn.substring(tableFqn.lastIndexOf('.') + 1);
        return toKebabCase(simpleName) + ".csv";
    }

    private String toKebabCase(String input) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && result.length() > 0 && result.charAt(result.length() - 1) != '-') {
                    result.append('-');
                }
                result.append(Character.toLowerCase(c));
            } else if (c == ' ' || c == '_') {
                if (result.length() > 0 && result.charAt(result.length() - 1) != '-') {
                    result.append('-');
                }
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private List<Field> getColumnFields(Class<?> rowClass) {
        List<Field> columnFields = new ArrayList<>();
        for (Field field : rowClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(Column.class)) {
                columnFields.add(field);
            }
        }
        return columnFields;
    }

    @Value
    private static class DataTableInfo {
        String displayName;
        String description;
        String filename;
        List<ColumnInfo> columns;
    }

    @Value
    private static class ColumnInfo {
        String displayName;
        String description;
    }
}
