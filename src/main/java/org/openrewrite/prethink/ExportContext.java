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

    @Override
    public String getDescription() {
        return "Export DataTables to CSV files in `.moderne/context/` along with a markdown " +
               "description file. The markdown file describes the context and includes schema " +
               "information for each data table.";
    }

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
        // Skip first cycle - let data table producers complete
        if (ctx.getCycle() == 1) {
            return Collections.emptyList();
        }

        List<SourceFile> contextFiles = new ArrayList<>();

        // Access DATA_TABLES here - after preceding recipes have populated it
        Map<DataTable<?>, List<?>> allTables = ctx.getMessage(ExecutionContext.DATA_TABLES);

        if (allTables == null || allTables.isEmpty()) {
            return contextFiles;
        }

        // Collect the data tables we're exporting for the markdown file
        List<DataTableInfo> exportedTables = new ArrayList<>();

        for (Map.Entry<DataTable<?>, List<?>> entry : allTables.entrySet()) {
            DataTable<?> table = entry.getKey();
            List<?> rows = entry.getValue();

            String tableFqn = table.getClass().getName();

            // Filter to only requested tables
            if (dataTables.contains(tableFqn)) {
                String filename = tableToFilename(tableFqn);
                String csvContent = exportToCsv(table, rows);
                Path filePath = CONTEXT_DIR.resolve(filename);

                // Collect table info for markdown
                exportedTables.add(new DataTableInfo(
                        table.getDisplayName(),
                        table.getDescription(),
                        filename,
                        getColumnInfo(table, rows)
                ));

                // Only generate if file doesn't already exist
                if (!acc.getExistingContextPaths().contains(filePath)) {
                    PlainText csvFile = PlainText.builder()
                            .text(csvContent)
                            .sourcePath(filePath)
                            .build();
                    contextFiles.add(csvFile);
                }
            }
        }

        // Generate the markdown description file
        if (!exportedTables.isEmpty()) {
            String mdFilename = toKebabCase(displayName) + ".md";
            Path mdPath = CONTEXT_DIR.resolve(mdFilename);

            if (!acc.getExistingContextPaths().contains(mdPath)) {
                String mdContent = generateMarkdown(exportedTables);
                PlainText mdFile = PlainText.builder()
                        .text(mdContent)
                        .sourcePath(mdPath)
                        .build();
                contextFiles.add(mdFile);
            }
        }

        return contextFiles;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                // Skip first cycle
                if (ctx.getCycle() == 1) {
                    return tree;
                }
                if (tree instanceof PlainText) {
                    PlainText pt = (PlainText) tree;
                    Path path = pt.getSourcePath();

                    if (path.startsWith(CONTEXT_DIR)) {
                        String filename = path.getFileName().toString();

                        // Update CSV files
                        if (filename.endsWith(".csv")) {
                            String newContent = getCsvContentForFile(filename, ctx);
                            if (newContent != null && !newContent.equals(pt.getText())) {
                                return pt.withText(newContent);
                            }
                        }

                        // Update markdown file
                        String expectedMdFilename = toKebabCase(displayName) + ".md";
                        if (filename.equals(expectedMdFilename)) {
                            Map<DataTable<?>, List<?>> allTables = ctx.getMessage(ExecutionContext.DATA_TABLES);
                            if (allTables != null) {
                                List<DataTableInfo> exportedTables = new ArrayList<>();
                                for (Map.Entry<DataTable<?>, List<?>> entry : allTables.entrySet()) {
                                    DataTable<?> table = entry.getKey();
                                    String tableFqn = table.getClass().getName();
                                    if (dataTables.contains(tableFqn)) {
                                        exportedTables.add(new DataTableInfo(
                                                table.getDisplayName(),
                                                table.getDescription(),
                                                tableToFilename(tableFqn),
                                                getColumnInfo(table, entry.getValue())
                                        ));
                                    }
                                }
                                if (!exportedTables.isEmpty()) {
                                    String newContent = generateMarkdown(exportedTables);
                                    if (!newContent.equals(pt.getText())) {
                                        return pt.withText(newContent);
                                    }
                                }
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

    private List<ColumnInfo> getColumnInfo(DataTable<?> table, List<?> rows) {
        List<ColumnInfo> columns = new ArrayList<>();

        Class<?> rowClass;
        if (!rows.isEmpty()) {
            rowClass = rows.get(0).getClass();
        } else {
            try {
                rowClass = Class.forName(table.getClass().getName() + "$Row");
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

    @SuppressWarnings("unchecked")
    private @Nullable String getCsvContentForFile(String filename, ExecutionContext ctx) {
        Map<DataTable<?>, List<?>> allTables = ctx.getMessage(ExecutionContext.DATA_TABLES);
        if (allTables == null) {
            return null;
        }

        for (Map.Entry<DataTable<?>, List<?>> entry : allTables.entrySet()) {
            DataTable<?> table = entry.getKey();
            String tableFqn = table.getClass().getName();

            if (dataTables.contains(tableFqn)) {
                String expectedFilename = tableToFilename(tableFqn);
                if (expectedFilename.equals(filename)) {
                    return exportToCsv(table, entry.getValue());
                }
            }
        }
        return null;
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
        // Get the Row class from the DataTable's generic type
        try {
            Class<?> rowClass = Class.forName(table.getClass().getName() + "$Row");
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
