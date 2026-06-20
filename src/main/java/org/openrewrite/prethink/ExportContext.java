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

    @Value
    public static class Accumulator {
        Set<Path> existingContextPaths;
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator(new HashSet<>());
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
        // The data tables this recipe exports are populated by sibling recipes
        // during the *edit* phase of cycle 1, which runs after this generate
        // phase. So the store is still empty here in cycle 1 — we cannot read
        // rows yet. Instead, generate placeholder files in cycle 1 (one CSV per
        // configured data table, plus the markdown) and fill them with real
        // content from the store in cycle 2 via getVisitor().
        //
        // This mirrors GenerateCalmArchitecture, and is deliberate: generating a
        // file in cycle 1 and editing it in cycle 2 is the only file-producing
        // pattern the Moderne CLI's V3 edit overlay carries through reliably.
        // Generating a brand-new file in cycle 2 (the previous behavior of this
        // recipe) is dropped from the changeset on real multi-file repositories,
        // so no topic CSVs were ever written even though their data tables were
        // populated. Generating the placeholder in cycle 1 also drives the second
        // cycle on its own (a generated file is a change), so the export no longer
        // depends on a sibling recipe making a change to trigger cycle 2.
        if (ctx.getCycle() != 1 || dataTables.isEmpty()) {
            return emptyList();
        }

        List<SourceFile> contextFiles = new ArrayList<>();

        // One placeholder CSV per configured data table. The header row comes
        // from the Row class schema, so it is available without any rows.
        boolean anyTableResolvable = false;
        for (String tableFqn : dataTables) {
            String headers = getHeadersFromTableFqn(tableFqn);
            // Skip tables whose Row class can't be resolved on this classpath —
            // we can't produce a meaningful CSV (and don't want an empty file).
            if (headers.isEmpty()) {
                continue;
            }
            anyTableResolvable = true;
            Path filePath = CONTEXT_DIR.resolve(tableToFilename(tableFqn));
            if (acc.getExistingContextPaths().contains(filePath)) {
                continue;
            }
            contextFiles.add(PlainText.builder()
                    .text(headers)
                    .sourcePath(filePath)
                    .build());
        }

        // Placeholder markdown description file (only when at least one table
        // can actually be exported).
        Path mdPath = CONTEXT_DIR.resolve(toKebabCase(displayName) + ".md");
        if (anyTableResolvable && !acc.getExistingContextPaths().contains(mdPath)) {
            contextFiles.add(PlainText.builder()
                    .text("# " + displayName + "\n")
                    .sourcePath(mdPath)
                    .build());
        }

        return contextFiles;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                // Fill placeholders generated in cycle 1 with real content read
                // from the now-populated data table store. Done from cycle 2
                // onward (the store is only readable after cycle 1's edit phase).
                if (ctx.getCycle() == 1) {
                    return tree;
                }
                if (tree instanceof PlainText) {
                    PlainText pt = (PlainText) tree;
                    Path path = pt.getSourcePath();

                    if (path.startsWith(CONTEXT_DIR)) {
                        String filename = path.getFileName().toString();

                        DataTableStore store2 = DataTableExecutionContextView.view(ctx).getDataTableStore();
                        Map<String, DataTable<?>> tablesByFqn = new LinkedHashMap<>();
                        Map<String, List<Object>> rowsByFqn = new LinkedHashMap<>();
                        aggregateMatchingTables(store2, tablesByFqn, rowsByFqn);

                        // Fill (or remove) CSV files for tables this recipe owns
                        if (filename.endsWith(".csv") && ownsCsvFile(filename)) {
                            String ownedFqn = fqnForCsvFile(filename);
                            List<?> rows = ownedFqn == null ? emptyList() : rowsByFqn.getOrDefault(ownedFqn, emptyList());
                            // Delete the cycle-1 placeholder when the table produced
                            // no rows, so empty tables don't leave behind a
                            // headers-only CSV (matching GenerateCalmArchitecture,
                            // which deletes its placeholder when there's no data).
                            if (rows.isEmpty()) {
                                return null;
                            }
                            String newContent = exportToCsv(tablesByFqn.get(ownedFqn), rows);
                            if (!newContent.equals(pt.getText())) {
                                return pt.withText(newContent);
                            }
                        }

                        // Fill (or remove) the markdown description file
                        String expectedMdFilename = toKebabCase(displayName) + ".md";
                        if (filename.equals(expectedMdFilename)) {
                            List<DataTableInfo> exportedTables = new ArrayList<>();
                            // Document only the tables that actually produced rows,
                            // since the empty ones' CSVs are removed above.
                            for (String tableFqn : dataTables) {
                                List<?> rows = rowsByFqn.getOrDefault(tableFqn, emptyList());
                                if (rows.isEmpty()) {
                                    continue;
                                }
                                List<ColumnInfo> columns = getColumnInfoForFqn(tableFqn, rows);
                                if (columns.isEmpty()) {
                                    continue;
                                }
                                DataTable<?> table = tablesByFqn.get(tableFqn);
                                exportedTables.add(new DataTableInfo(
                                        table != null ? table.getDisplayName() : simpleName(tableFqn),
                                        table != null ? table.getDescription() : "",
                                        tableToFilename(tableFqn),
                                        columns
                                ));
                            }
                            // Delete the placeholder markdown when no configured
                            // table produced any rows.
                            if (exportedTables.isEmpty()) {
                                return null;
                            }
                            String newContent = generateMarkdown(exportedTables);
                            if (!newContent.equals(pt.getText())) {
                                return pt.withText(newContent);
                            }
                        }
                    }
                }
                return tree;
            }
        };
    }

    /**
     * Whether the given CSV filename corresponds to one of the data tables this
     * ExportContext instance is configured to export. Without this guard, every
     * ExportContext instance in a composite would try to fill every other
     * instance's CSVs (they all share the same getVisitor shape), producing
     * empty/incorrect content.
     */
    private boolean ownsCsvFile(String filename) {
        return fqnForCsvFile(filename) != null;
    }

    /**
     * The configured data table FQN that produces the given CSV filename, or
     * {@code null} if this ExportContext instance does not own that file.
     */
    private @Nullable String fqnForCsvFile(String filename) {
        for (String tableFqn : dataTables) {
            if (tableToFilename(tableFqn).equals(filename)) {
                return tableFqn;
            }
        }
        return null;
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

    private List<ColumnInfo> getColumnInfoForFqn(String tableFqn, List<?> rows) {
        List<ColumnInfo> columns = new ArrayList<>();

        Class<?> rowClass;
        if (!rows.isEmpty()) {
            rowClass = rows.get(0).getClass();
        } else {
            try {
                rowClass = Class.forName(tableFqn + "$Row");
            } catch (ClassNotFoundException e) {
                return columns;
            }
        }

        for (Field field : rowClass.getDeclaredFields()) {
            Column columnAnnotation = field.getAnnotation(Column.class);
            if (columnAnnotation != null) {
                columns.add(new ColumnInfo(columnAnnotation.displayName(), columnAnnotation.description()));
            }
        }

        return columns;
    }

    /**
     * Aggregate rows from all DataTable instances of the same class into a single list per class.
     * When multiple recipes produce the same DataTable type (e.g., FindNodeTestCoverage and
     * FindTestCoverage both produce TestMapping), this ensures all rows are combined.
     */
    @SuppressWarnings({"unchecked"})
    private void aggregateMatchingTables(DataTableStore store,
                                         Map<String, DataTable<?>> tablesByFqn,
                                         Map<String, List<Object>> rowsByFqn) {
        // First pass: collect all matching tables (order from store is non-deterministic)
        Map<String, DataTable<?>> unordered = new HashMap<>();
        Map<String, List<Object>> unorderedRows = new HashMap<>();
        for (DataTable<?> dt : store.getDataTables()) {
            String tableFqn = dt.getClass().getName();
            if (dataTables.contains(tableFqn)) {
                unordered.putIfAbsent(tableFqn, dt);
                List<Object> rows = unorderedRows.computeIfAbsent(tableFqn, k -> new ArrayList<>());
                Class<? extends DataTable<Object>> dtClass = (Class<? extends DataTable<Object>>) dt.getClass();
                store.getRows(dtClass, dt.getGroup()).forEach(rows::add);
            }
        }
        // Second pass: insert in dataTables list order for deterministic output
        for (String fqn : dataTables) {
            if (unordered.containsKey(fqn)) {
                tablesByFqn.put(fqn, unordered.get(fqn));
                rowsByFqn.put(fqn, unorderedRows.getOrDefault(fqn, new ArrayList<>()));
            }
        }
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

    private String exportToCsv(DataTable<?> table, List<?> rows) {
        if (rows.isEmpty()) {
            return getHeadersFromTable(table);
        }

        Class<?> rowClass = rows.get(0).getClass();
        List<Field> columnFields = getColumnFields(rowClass);

        StringWriter stringWriter = new StringWriter();
        CsvWriter writer = new CsvWriter(stringWriter, new CsvWriterSettings());

        // Write headers
        String[] headers = columnFields.stream()
                .map(f -> f.getAnnotation(Column.class).displayName())
                .toArray(String[]::new);
        writer.writeHeaders(headers);

        // Write rows
        for (Object row : rows) {
            String[] values = new String[columnFields.size()];
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
        }

        writer.close();
        return stringWriter.toString();
    }

    private String getHeadersFromTable(DataTable<?> table) {
        return getHeadersFromTableFqn(table.getClass().getName());
    }

    /**
     * Render the header-only CSV for a data table identified by its fully
     * qualified class name, reading the column display names from the table's
     * {@code $Row} class. Used to write cycle-1 placeholder CSVs before any rows
     * exist in the store. Returns the empty string when the {@code $Row} class
     * cannot be resolved on the current classpath.
     */
    private String getHeadersFromTableFqn(String tableFqn) {
        try {
            Class<?> rowClass = Class.forName(tableFqn + "$Row");
            List<Field> columnFields = getColumnFields(rowClass);

            StringWriter stringWriter = new StringWriter();
            CsvWriter writer = new CsvWriter(stringWriter, new CsvWriterSettings());

            String[] headers = columnFields.stream()
                    .map(f -> f.getAnnotation(Column.class).displayName())
                    .toArray(String[]::new);
            writer.writeHeaders(headers);
            writer.close();

            return stringWriter.toString();
        } catch (ClassNotFoundException e) {
            return "";
        }
    }

    private static String simpleName(String tableFqn) {
        return tableFqn.substring(tableFqn.lastIndexOf('.') + 1);
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
