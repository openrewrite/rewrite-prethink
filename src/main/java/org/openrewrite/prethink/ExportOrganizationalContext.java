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
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.marker.GitProvenance;
import org.openrewrite.prethink.table.ProjectMetadata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static org.openrewrite.prethink.OrganizationalContext.*;

/**
 * Export data tables into a shared collection that spans many repositories.
 * <p>
 * This is the organizational counterpart to {@link ExportContext}. Where that
 * recipe writes one repository's context into that repository, this one appends
 * to combined CSVs in a directory that typically lives outside every repository
 * analyzed, so that a single agent started in that directory can reason across
 * the whole organization. Each combined CSV carries a leading {@code Repository}
 * column identifying where a row came from, and re-exporting a repository
 * replaces exactly that repository's rows.
 * <p>
 * Nothing is written to the repository being analyzed: the collection is updated
 * on the filesystem as a side effect, and this recipe produces no changes.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class ExportOrganizationalContext extends ScanningRecipe<ExportOrganizationalContext.Accumulator> {

    private static final String[] REPOSITORY_HEADERS = {
            REPOSITORY_COLUMN, "Organization", "Origin", "Branch", "Project name", "Project description"
    };

    @Option(displayName = "Target directory",
            description = "The directory the combined context is written to, which may be outside of the " +
                          "repository being analyzed so that every repository contributes to one central " +
                          "collection. Context files are written to `.moderne/context/` within it, mirroring " +
                          "the per-repository layout. A relative path, and no path at all, resolve against the " +
                          "working directory of the process running the recipe, so an absolute path is " +
                          "recommended.",
            required = false,
            example = "/var/lib/prethink/acme")
    @Nullable
    String targetDirectory;

    @Option(displayName = "Repository",
            description = "The name recorded in the `Repository` column for the rows contributed by this " +
                          "repository. If not specified, it is taken from the git remote as " +
                          "`organization/repository`.",
            required = false,
            example = "acme/orders-service")
    @Nullable
    String repositoryName;

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
            description = "Fully qualified class names of DataTables to export to CSV. If not specified, every " +
                          "data table that produced rows in the run is exported, so the recipe works with any " +
                          "composite without having to be told what that composite discovers.",
            required = false,
            example = "org.openrewrite.prethink.table.TestMapping")
    @Nullable
    List<String> dataTables;

    @Option(displayName = "Data tables to exclude",
            description = "Fully qualified class names of DataTables to leave out. Useful when a composite " +
                          "contains recipes whose data tables are not context worth combining.",
            required = false,
            example = "org.openrewrite.table.SourcesFileResults")
    @Nullable
    List<String> excludeDataTables;

    @Override
    public String getDisplayName() {
        return "Export organizational context files";
    }

    String description = "Export DataTables into combined CSV files in a shared directory that many " +
            "repositories contribute to, along with a markdown description file. Every row carries a " +
            "leading `Repository` column, and re-running for a repository replaces just that " +
            "repository's rows. With no data tables configured, whatever the composite discovered is " +
            "exported. The collection is written directly to the filesystem; the repository being " +
            "analyzed is left unchanged.";

    @Override
    public boolean causesAnotherCycle() {
        return true;
    }

    public static class Accumulator {
        @Nullable
        volatile GitProvenance provenance;

        /**
         * Exporting is a single filesystem update rather than a per-file edit,
         * so it happens on the first source file visited and is skipped for
         * every other. Guarded by the accumulator's monitor rather than a
         * compare-and-set so that recipes later in a composite -- the agent
         * config writer in particular -- observe a completed export even when
         * source files are visited in parallel.
         */
        boolean exported;
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (acc.provenance == null && tree instanceof SourceFile) {
                    ((SourceFile) tree).getMarkers().findFirst(GitProvenance.class)
                            .ifPresent(provenance -> acc.provenance = provenance);
                }
                return tree;
            }
        };
    }

    @Override
    public Collection<SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        return emptyList();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (ctx.getCycle() == 1) {
                    // The data tables this recipe exports are populated by sibling recipes during
                    // this cycle's edit phase, so there is nothing to read yet. Unlike ExportContext
                    // this recipe generates no placeholder file to carry it into a second cycle --
                    // it changes no source file at all -- so ask for another cycle explicitly.
                    ctx.putMessage(Prethink.CYCLE_TRIGGER, true);
                } else if (ctx.getCycle() == 2) {
                    export(acc, ctx);
                }
                return tree;
            }
        };
    }

    private void export(Accumulator acc, ExecutionContext ctx) {
        synchronized (acc) {
            if (acc.exported) {
                return;
            }
            // Set before the work so a failure surfaces once, rather than being
            // retried for every remaining source file.
            acc.exported = true;

            DataTableStore store = DataTableExecutionContextView.view(ctx).getDataTableStore();
            // Multiple recipes can write the same table type, so collect every instance to concatenate its
            // rows. A store only registers a table once a row has been inserted into it, so iterating it is
            // exactly "everything this composite discovered" -- which is what makes an unconfigured export
            // work against any composite rather than a catalog of tables known ahead of time.
            Map<String, List<DataTable<?>>> instancesByFqn = new TreeMap<>();
            for (DataTable<?> dataTable : store.getDataTables()) {
                String tableFqn = dataTable.getClass().getName();
                if (exports(tableFqn)) {
                    instancesByFqn.computeIfAbsent(tableFqn, k -> new ArrayList<>()).add(dataTable);
                }
            }

            ProjectMetadata.Row project = projectMetadata(store);
            String repository = repositoryId(acc.provenance, repositoryName,
                    project == null ? null : project.getArtifactId());

            try {
                Layout layout = layout(targetDirectory);
                locked(layout, () -> write(layout, store, instancesByFqn, repository, acc.provenance, project));
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Unable to write the organizational Prethink context to " + targetPath(targetDirectory), e);
            }
        }
    }

    private void write(Layout layout, DataTableStore store, Map<String, List<DataTable<?>>> instancesByFqn,
                       String repository, @Nullable GitProvenance provenance,
                       ProjectMetadata.@Nullable Row project) throws IOException {
        Path repositoriesCsv = layout.context.resolve(REPOSITORIES_FILE);
        Path tablesCsv = layout.context.resolve(TABLES_FILE);
        // A repository already in the index may have left rows in tables it no longer
        // contributes to, which have to be rewritten to purge them. A repository being
        // exported for the first time can only add rows, so untouched tables are skipped.
        boolean previouslyExported = containsRepository(repositoriesCsv, repository);
        Map<String, String[]> tableIndex = readTableIndex(tablesCsv);

        Set<String> handled = new HashSet<>();
        Map<String, List<ColumnInfo>> columnsByFqn = new HashMap<>();
        for (Map.Entry<String, List<DataTable<?>>> entry : instancesByFqn.entrySet()) {
            String tableFqn = entry.getKey();
            List<DataTable<?>> instances = entry.getValue();
            List<ColumnInfo> columns = declaredColumns(instances.get(0));
            // Skip tables whose Row class can't be resolved at all -- without the schema
            // there is neither a header to write nor a schema to document.
            if (columns == null) {
                continue;
            }
            columnsByFqn.put(tableFqn, columns);
            String filename = tableToFilename(tableFqn);
            handled.add(filename);
            boolean hasRows = mergeCsv(layout.context.resolve(filename), repository, headers(columns),
                    writer -> writeRows(store, instances, repository, columns, writer));
            if (hasRows) {
                DataTable<?> table = instances.get(0);
                tableIndex.put(filename, new String[]{
                        filename, tableFqn, table.getDisplayName(), table.getDescription()});
            } else {
                tableIndex.remove(filename);
            }
        }

        // Sweep the rest of the collection for rows this repository left behind in tables it
        // no longer contributes to. Those tables are absent from this run's store, so this is
        // the only place they can be found -- and with a discovered table set, a composite
        // that changes between runs makes that the normal case rather than the exception.
        if (previouslyExported) {
            for (Path csv : tableFiles(layout.context)) {
                String filename = csv.getFileName().toString();
                if (!handled.contains(filename) && !purgeRepository(csv, repository)) {
                    tableIndex.remove(filename);
                }
            }
        }

        mergeCsv(repositoriesCsv, repository, REPOSITORY_HEADERS, writer -> {
            writer.writeRow(
                    repository,
                    provenance == null ? "" : nullToEmpty(provenance.getOrganizationName()),
                    provenance == null ? "" : nullToEmpty(provenance.getOrigin()),
                    provenance == null ? "" : nullToEmpty(provenance.getBranch()),
                    project == null ? "" : nullToEmpty(project.getName()),
                    project == null ? "" : nullToEmpty(project.getDescription()));
            return 1;
        });

        writeTableIndex(tablesCsv, tableIndex);

        Path markdown = layout.context.resolve(toKebabCase(displayName) + ".md");
        if (tableIndex.isEmpty()) {
            // Nothing in the whole collection describes this context, so leave no
            // description behind, mirroring ExportContext's handling of empty tables.
            Files.deleteIfExists(markdown);
        } else {
            // Documented from the catalog rather than from this run, so that a repository
            // with a narrower composite does not erase the description of a table other
            // repositories are still contributing to.
            List<TableInfo> documented = new ArrayList<>();
            for (String[] table : tableIndex.values()) {
                documented.add(tableInfo(layout, table, columnsByFqn));
            }
            writeIfChanged(markdown, generateMarkdown(documented, repositoryCount(repositoriesCsv)));
        }
    }

    /**
     * Whether a data table belongs in this context: everything discovered, unless
     * an explicit set was configured, and never anything excluded.
     */
    private boolean exports(String tableFqn) {
        if (excludeDataTables != null && excludeDataTables.contains(tableFqn)) {
            return false;
        }
        return dataTables == null || dataTables.isEmpty() || dataTables.contains(tableFqn);
    }

    /**
     * Stream this repository's rows straight to the writer, so a table is never
     * held in memory, prefixing each with the repository it came from.
     */
    @SuppressWarnings("unchecked")
    private long writeRows(DataTableStore store, List<DataTable<?>> instances, String repository,
                           List<ColumnInfo> columns, CsvWriter writer) {
        long[] written = {0};
        String[] values = new String[columns.size() + 1];
        values[0] = repository;
        for (DataTable<?> instance : instances) {
            Class<? extends DataTable<Object>> dataTableClass = (Class<? extends DataTable<Object>>) instance.getClass();
            try (Stream<Object> rows = store.getRows(dataTableClass, instance.getGroup())) {
                rows.forEach(row -> {
                    written[0]++;
                    for (int i = 0; i < columns.size(); i++) {
                        Field field = columns.get(i).field;
                        try {
                            field.setAccessible(true);
                            Object value = field.get(row);
                            values[i + 1] = value == null ? "" : value.toString();
                        } catch (IllegalAccessException | RuntimeException e) {
                            values[i + 1] = "";
                        }
                    }
                    writer.writeRow((Object[]) values);
                });
            }
        }
        return written[0];
    }

    private String generateMarkdown(List<TableInfo> tables, int repositories) {
        StringBuilder sb = new StringBuilder();

        sb.append("# ").append(displayName).append("\n\n");
        sb.append("## ").append(shortDescription).append("\n\n");
        sb.append(longDescription).append("\n\n");

        sb.append("This context is combined across ").append(repositories)
          .append(repositories == 1 ? " repository" : " repositories")
          .append(". Every row's first column, `").append(REPOSITORY_COLUMN)
          .append("`, identifies the repository it was extracted from; filter or group by it to scope a " +
                  "question to one repository, and join on it to follow something across several. See [`")
          .append(contextPath(REPOSITORIES_FILE)).append("`](").append(contextPath(REPOSITORIES_FILE))
          .append(") for the repositories included.\n\n");

        sb.append("## Data Tables\n\n");

        for (TableInfo table : tables) {
            String path = contextPath(table.filename);
            sb.append("### ").append(table.displayName).append("\n\n");
            sb.append("**File:** [`").append(path).append("`](").append(path).append(")\n\n");
            if (!table.description.isEmpty()) {
                sb.append(table.description).append("\n\n");
            }

            sb.append("| Column | Description |\n");
            sb.append("|--------|-------------|\n");
            sb.append("| ").append(REPOSITORY_COLUMN).append(" | The repository this row was extracted from. |\n");
            for (ColumnInfo column : table.columns) {
                sb.append("| ").append(column.displayName).append(" | ").append(column.description).append(" |\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Describe a catalogued table for the markdown.
     * <p>
     * The display name and description come from the catalog, so a table this run
     * contributed nothing to is still described. Its columns come from the row
     * class of the table this run exported, and otherwise from the combined CSV's
     * own header row -- which costs the per-column descriptions but keeps the
     * schema accurate for tables only other repositories contribute to.
     */
    private TableInfo tableInfo(Layout layout, String[] catalogued,
                                Map<String, List<ColumnInfo>> columnsByFqn) throws IOException {
        String filename = catalogued[0];
        List<ColumnInfo> columns = columnsByFqn.get(catalogued[1]);
        if (columns == null) {
            columns = new ArrayList<>();
            String[] headers = headersOf(layout.context.resolve(filename));
            if (headers != null) {
                // The first column is the repository column, documented separately.
                for (int i = 1; i < headers.length; i++) {
                    columns.add(new ColumnInfo(headers[i], "", null));
                }
            }
        }
        return new TableInfo(catalogued[2], catalogued[3], filename, columns);
    }

    private String[] headers(List<ColumnInfo> columns) {
        String[] headers = new String[columns.size() + 1];
        headers[0] = REPOSITORY_COLUMN;
        for (int i = 0; i < columns.size(); i++) {
            headers[i + 1] = columns.get(i).displayName;
        }
        return headers;
    }

    /**
     * The {@code @Column} annotated fields of a data table's row class, or
     * {@code null} when the table does not declare one the usual way.
     * <p>
     * The row class comes from the table instance the store holds rather than
     * from a lookup by name: every recipe artifact in a run is loaded by its own
     * classloader, so a table declared by another artifact -- which is most of
     * what a composite discovers -- is not resolvable by name from here.
     */
    private @Nullable List<ColumnInfo> declaredColumns(DataTable<?> table) {
        Class<?> rowClass;
        try {
            rowClass = table.getType();
        } catch (RuntimeException e) {
            // getType() reads the direct superclass's type argument, so a table that
            // reaches DataTable through an intermediate class has none to give. Skip
            // just that table rather than losing the rest of the collection.
            return null;
        }
        List<ColumnInfo> columns = new ArrayList<>();
        for (Field field : rowClass.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null) {
                columns.add(new ColumnInfo(column.displayName(), column.description(), field));
            }
        }
        return columns;
    }

    private String tableToFilename(String tableFqn) {
        // org.openrewrite.prethink.table.MethodDescriptions -> method-descriptions.csv
        return toKebabCase(tableFqn.substring(tableFqn.lastIndexOf('.') + 1)) + ".csv";
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

    private static String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

    @Value
    private static class TableInfo {
        String displayName;
        String description;
        String filename;
        List<ColumnInfo> columns;
    }

    /**
     * One column of a combined table. The backing field is present only when the
     * schema was read from a {@code $Row} class, which is also the only case in
     * which rows are being written through it.
     */
    @Value
    private static class ColumnInfo {
        String displayName;
        String description;

        @Nullable
        Field field;
    }
}
