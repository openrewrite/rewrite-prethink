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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.univocity.parsers.csv.CsvWriter;
import com.univocity.parsers.csv.CsvWriterSettings;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
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
 * <p>
 * A single table whose CSV would exceed {@link #maxBytesPerFile} is split across a
 * stable primary file ({@code <name>.csv}) and numbered overflow files
 * ({@code <name>-002.csv}, {@code <name>-003.csv}, ...) so no generated context file
 * grows large enough to break serialization or exceed the CLI's read limits.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class ExportContext extends ScanningRecipe<ExportContext.Accumulator> {

    /**
     * Default per-file budget: comfortably under the CLI serializer's ~20 MB
     * single-string limit, leaving margin for CSV quoting overhead.
     */
    private static final long DEFAULT_MAX_BYTES_PER_FILE = 8L * 1024 * 1024;

    /**
     * Spare overflow pages provisioned beyond what the previous run used, so a
     * table that grows by a page or two does not have to wait a run to expand.
     */
    private static final int PROVISION_HEADROOM_PAGES = 2;

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

    /**
     * Internal per-file pagination budget in bytes, overridable in tests and otherwise defaulting to {@link #DEFAULT_MAX_BYTES_PER_FILE}.
     */
    @Getter(AccessLevel.NONE)
    @Nullable
    Long maxBytesPerFile;

    @JsonCreator
    public ExportContext(String displayName, String shortDescription, String longDescription, List<String> dataTables) {
        this(displayName, shortDescription, longDescription, dataTables, null);
    }

    ExportContext(String displayName, String shortDescription, String longDescription,
                  List<String> dataTables, @Nullable Long maxBytesPerFile) {
        this.displayName = displayName;
        this.shortDescription = shortDescription;
        this.longDescription = longDescription;
        this.dataTables = dataTables;
        this.maxBytesPerFile = maxBytesPerFile;
    }

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
        private final Map<Path, Integer> existingContextSizes = new HashMap<>();

        public Set<Path> getExistingContextPaths() {
            return existingContextPaths;
        }

        /**
         * Character length of each existing context file, used to decide how many
         * overflow pages to provision for a table whose primary file is near budget.
         */
        public Map<Path, Integer> getExistingContextSizes() {
            return existingContextSizes;
        }

        // The fill-phase output, aggregated and rendered exactly once (in cycle 2+,
        // when the store is populated) and reused across every visited context file
        // instead of re-reading the data tables per file. Safe to cache because the
        // producing recipes stop writing after cycle 1. A table that produced no
        // rows is absent from the map so getVisitor() deletes its cycle-1
        // placeholder. Published last via volatile so readers see a fully-built map.
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
     * Aggregate and render this context's tables exactly once, caching the result
     * on the accumulator; later calls are no-ops. Only invoked from cycle 2+ (the
     * store is empty during cycle 1), so it always reads populated data tables.
     * Tables that produced no rows are omitted from {@link Accumulator#csvByFilename}
     * so their cycle-1 placeholder is deleted in {@link #getVisitor}.
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

            // Multiple recipes can write the same table type, so collect every instance to concatenate its rows.
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
                String base = tableToBaseName(tableFqn);
                // Fill exactly the page files that were provisioned in cycle 1 and
                // now exist in the LST. Recomputing overflowSlots() here would
                // overcount, because by cycle 2 the scan also sees the overflow
                // placeholders this recipe generated in cycle 1.
                int maxPages = Math.max(1, existingPageCount(acc, base));
                RenderedTable table = renderPages(store, representative, instances, maxPages);
                // No rows across any instance: omit so the cycle-1 placeholder is
                // deleted (matching GenerateCalmArchitecture, which removes its
                // placeholder when there is no data), and skip it in the markdown.
                if (table.pages.isEmpty()) {
                    continue;
                }
                List<String> filenames = new ArrayList<>(table.pages.size());
                for (int i = 0; i < table.pages.size(); i++) {
                    String filename = pageFilename(base, i + 1);
                    rendered.put(filename, table.pages.get(i));
                    filenames.add(filename);
                }
                exportedTables.add(new DataTableInfo(
                        representative.getDisplayName(),
                        representative.getDescription(),
                        filenames,
                        table.omittedRows,
                        getColumnInfo(representative)
                ));
            }
            acc.markdown = exportedTables.isEmpty() ? null : generateMarkdown(exportedTables);
            // Publish the map last so readers see it (and markdown) fully built — volatile happens-before.
            acc.csvByFilename = rendered;
        }
    }

    /**
     * Stream each row straight to the writer so a full table is never held in
     * memory, splitting into pages of at most {@link #maxBytesPerFile} bytes. At
     * most {@code maxPages} pages are produced; any rows beyond that are counted
     * (not buffered) and reported as omitted, so a table that outgrew its
     * provisioned pages is completed on the next run rather than crashing this one.
     * Returns an empty page list when no instance produced any row, signalling the
     * caller to drop the table.
     */
    @SuppressWarnings("unchecked")
    private RenderedTable renderPages(DataTableStore store, DataTable<?> representative,
                                      List<DataTable<?>> instances, int maxPages) {
        List<Field> columnFields = getColumnFields(representative.getType());
        String[] headers = columnFields.stream()
                .map(f -> f.getAnnotation(Column.class).displayName())
                .toArray(String[]::new);
        long budget = maxBytesPerFile == null ? DEFAULT_MAX_BYTES_PER_FILE : maxBytesPerFile;

        List<String> pages = new ArrayList<>();
        String[] values = new String[columnFields.size()];
        long omittedRows = 0;

        StringWriter stringWriter = new StringWriter();
        CsvWriter writer = new CsvWriter(stringWriter, new CsvWriterSettings());
        writer.writeHeaders(headers);
        long pageChars = estimatedLength(headers);
        boolean pageHasRow = false;

        for (DataTable<?> instance : instances) {
            Class<? extends DataTable<Object>> dtClass = (Class<? extends DataTable<Object>>) instance.getClass();
            try (Stream<Object> rows = store.getRows(dtClass, instance.getGroup())) {
                Iterator<Object> it = rows.iterator();
                while (it.hasNext()) {
                    Object row = it.next();
                    fillRowValues(row, columnFields, values);
                    // Roll to a fresh page (header repeated) once the current one is
                    // full, splitting only at row boundaries so every row stays whole;
                    // a single row larger than the budget therefore fills its own page.
                    if (budget > 0 && pageHasRow && pageChars >= budget) {
                        if (pages.size() + 1 >= maxPages) {
                            // Out of provisioned page slots: drain and count the rest
                            // without buffering, keeping memory bounded to maxPages.
                            omittedRows++;
                            while (it.hasNext()) {
                                it.next();
                                omittedRows++;
                            }
                            break;
                        }
                        writer.close();
                        pages.add(stringWriter.toString());
                        stringWriter = new StringWriter();
                        writer = new CsvWriter(stringWriter, new CsvWriterSettings());
                        writer.writeHeaders(headers);
                        pageChars = estimatedLength(headers);
                        pageHasRow = false;
                    }
                    writer.writeRow((Object[]) values);
                    pageChars += estimatedLength(values);
                    pageHasRow = true;
                }
            }
            if (omittedRows > 0) {
                break;
            }
        }

        writer.close();
        if (pageHasRow) {
            pages.add(stringWriter.toString());
        }
        return new RenderedTable(pages, omittedRows);
    }

    /**
     * Copy a data table row's column values into the reused {@code values} buffer,
     * substituting an empty string for nulls or inaccessible fields.
     */
    private static void fillRowValues(Object row, List<Field> columnFields, String[] values) {
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
    }

    /**
     * Running estimate of a CSV line's length that counts the characters the writer
     * adds when it quote-escapes a field, so the page budget is not undercounted for
     * values containing commas, quotes, or newlines.
     */
    private static long estimatedLength(String[] values) {
        long length = 0;
        for (String value : values) {
            length += escapedLength(value) + 1;
        }
        return length;
    }

    /**
     * The number of characters a value occupies once written to CSV: a field holding
     * the delimiter, a quote, or a line break is wrapped in quotes with its own
     * quotes doubled, which can far exceed the raw length.
     */
    static long escapedLength(@Nullable String value) {
        if (value == null) {
            return 0;
        }
        long length = value.length();
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                length++;
                quoted = true;
            } else if (c == ',' || c == '\n' || c == '\r') {
                quoted = true;
            }
        }
        return quoted ? length + 2 : length;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    SourceFile sf = (SourceFile) tree;
                    Path path = sf.getSourcePath();
                    // Track existing context files (and their size) so we can update
                    // them and gauge how many overflow pages a table already needs.
                    if (path.startsWith(CONTEXT_DIR)) {
                        acc.getExistingContextPaths().add(path);
                        if (sf instanceof PlainText) {
                            acc.getExistingContextSizes().put(path, ((PlainText) sf).getText().length());
                        }
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
        // configured data table plus its provisioned overflow pages, plus the
        // markdown) and fill them with real content from the store in cycle 2 via
        // getVisitor().
        //
        // This mirrors GenerateCalmArchitecture, and is deliberate: generating a
        // file in cycle 1 and editing it in cycle 2 is the only file-producing
        // pattern the Moderne CLI's V3 edit overlay carries through reliably.
        // Generating a brand-new file in cycle 2 is dropped from the changeset on
        // real multi-file repositories, which is why overflow pages must be
        // provisioned here in cycle 1 rather than created on demand while filling.
        if (ctx.getCycle() != 1 || dataTables.isEmpty()) {
            return emptyList();
        }

        List<SourceFile> contextFiles = new ArrayList<>();

        // One placeholder CSV per configured data table (page 1 = <base>.csv) plus
        // any overflow page slots. The header row comes from the Row class schema,
        // so it is available without any rows.
        boolean anyTableResolvable = false;
        for (String tableFqn : dataTables) {
            String headers = getHeadersFromTableFqn(tableFqn);
            // Skip tables whose Row class can't be resolved on this classpath —
            // we can't produce a meaningful CSV (and don't want an empty file).
            if (headers.isEmpty()) {
                continue;
            }
            anyTableResolvable = true;
            String base = tableToBaseName(tableFqn);
            int pageCount = 1 + overflowSlots(acc, base);
            for (int page = 1; page <= pageCount; page++) {
                Path filePath = CONTEXT_DIR.resolve(pageFilename(base, page));
                if (acc.getExistingContextPaths().contains(filePath)) {
                    continue;
                }
                contextFiles.add(PlainText.builder()
                        .text(headers)
                        .sourcePath(filePath)
                        .build());
            }
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

                        // Fill (or remove) CSV files for tables this recipe owns.
                        // The content is aggregated + rendered exactly once and
                        // reused, rather than re-read from the store per file.
                        if (filename.endsWith(".csv") && ownsCsvFile(filename)) {
                            renderOnce(acc, ctx);
                            Map<String, String> csvByFilename = acc.csvByFilename;
                            String newContent = csvByFilename == null ? null : csvByFilename.get(filename);
                            // Delete the cycle-1 placeholder when the table produced
                            // no rows for this page — an empty table, or an unused
                            // overflow slot that a smaller-than-expected table did
                            // not need this run.
                            if (newContent == null) {
                                return null;
                            }
                            if (!newContent.equals(pt.getText())) {
                                return pt.withText(newContent);
                            }
                        } else if (filename.equals(toKebabCase(displayName) + ".md")) {
                            // Fill (or remove) the markdown description file. The
                            // markdown documents only the tables that produced rows;
                            // it is null when none did, so the placeholder is deleted.
                            renderOnce(acc, ctx);
                            String markdown = acc.markdown;
                            if (markdown == null) {
                                return null;
                            }
                            if (!markdown.equals(pt.getText())) {
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
     * How many overflow page slots to provision this run, derived from the pages the
     * previous run left because cycle 1 cannot read row counts. A full highest page
     * means the table was still dropping rows, so capacity grows geometrically to
     * converge a very large table in a few runs; otherwise it keeps a small headroom.
     */
    private int overflowSlots(Accumulator acc, String base) {
        long budget = maxBytesPerFile == null ? DEFAULT_MAX_BYTES_PER_FILE : maxBytesPerFile;
        int existingOverflow = 0;
        int highestPage = 0;
        int highestPageSize = 0;
        for (Map.Entry<Path, Integer> entry : acc.getExistingContextSizes().entrySet()) {
            int page = pageNumber(entry.getKey().getFileName().toString(), base);
            if (page == 0) {
                continue;
            }
            if (page > 1) {
                existingOverflow++;
            }
            if (page > highestPage) {
                highestPage = page;
                highestPageSize = entry.getValue();
            }
        }
        // A full highest page means rows were dropped last run, so grow geometrically;
        // otherwise keep a small headroom so a stable table is not over-provisioned.
        boolean saturated = budget > 0 && highestPage > 0 && highestPageSize >= budget * 3 / 4;
        if (existingOverflow == 0 && !saturated) {
            return 0;
        }
        return saturated ? existingOverflow * 2 + PROVISION_HEADROOM_PAGES
                         : existingOverflow + PROVISION_HEADROOM_PAGES;
    }

    /**
     * Number of page files (primary plus overflow) for a table that currently
     * exist in the LST, i.e. the page capacity provisioned in cycle 1. Used in
     * cycle 2 to cap rendering to the files that can actually be filled.
     */
    private int existingPageCount(Accumulator acc, String base) {
        int count = 0;
        for (Path path : acc.getExistingContextPaths()) {
            String filename = path.getFileName().toString();
            if (filename.equals(base + ".csv") || isOverflowPage(filename, base)) {
                count++;
            }
        }
        return count;
    }

    /**
     * The context filename for a given 1-based page: page 1 is the stable primary
     * {@code <base>.csv}; later pages are zero-padded {@code <base>-002.csv}, etc.
     */
    private String pageFilename(String base, int page) {
        return page == 1 ? base + ".csv" : String.format("%s-%03d.csv", base, page);
    }

    /**
     * Whether {@code filename} is a numbered overflow page of {@code base}, i.e.
     * {@code <base>-<digits>.csv}.
     */
    private boolean isOverflowPage(String filename, String base) {
        String prefix = base + "-";
        if (!filename.startsWith(prefix) || !filename.endsWith(".csv")) {
            return false;
        }
        String number = filename.substring(prefix.length(), filename.length() - ".csv".length());
        // Overflow pages are zero-padded to at least three digits (`-002`), so a
        // shorter numeric suffix belongs to a different table whose name ends in
        // `-<digits>` rather than to an overflow page of this base.
        if (number.length() < 3) {
            return false;
        }
        for (int i = 0; i < number.length(); i++) {
            if (!Character.isDigit(number.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The 1-based page number for a filename belonging to {@code base}: 1 for the
     * primary {@code <base>.csv}, N for {@code <base>-00N.csv}, or 0 when the
     * filename is not a page of this base.
     */
    private int pageNumber(String filename, String base) {
        if (filename.equals(base + ".csv")) {
            return 1;
        }
        if (!isOverflowPage(filename, base)) {
            return 0;
        }
        return Integer.parseInt(filename.substring((base + "-").length(), filename.length() - ".csv".length()));
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
     * The configured data table FQN that produces the given CSV filename (primary
     * or overflow page), or {@code null} if this ExportContext instance does not
     * own that file.
     */
    private @Nullable String fqnForCsvFile(String filename) {
        for (String tableFqn : dataTables) {
            String base = tableToBaseName(tableFqn);
            if (filename.equals(base + ".csv") || isOverflowPage(filename, base)) {
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

            if (table.filenames.size() == 1) {
                String filename = table.filenames.get(0);
                sb.append("**File:** [`").append(filename).append("`](").append(filename).append(")\n\n");
            } else {
                sb.append("**Files:** ");
                for (int i = 0; i < table.filenames.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    String filename = table.filenames.get(i);
                    sb.append("[`").append(filename).append("`](").append(filename).append(")");
                }
                sb.append("\n\n");
                sb.append("_Split across ").append(table.filenames.size())
                        .append(" files; read them together (e.g. `")
                        .append(pageGlob(table.filenames.get(0))).append("`)._\n\n");
            }

            if (table.omittedRows > 0) {
                sb.append("> ").append(table.omittedRows)
                        .append(" additional row(s) were omitted because the table exceeded the per-file size ")
                        .append("budget; re-run to expand into more files.\n\n");
            }

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

    /**
     * The glob that matches a paginated table's primary and overflow files, e.g.
     * {@code method-quality-metrics*.csv} from {@code method-quality-metrics.csv}.
     */
    private String pageGlob(String primaryFilename) {
        return primaryFilename.substring(0, primaryFilename.length() - ".csv".length()) + "*.csv";
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
        return tableToBaseName(tableFqn) + ".csv";
    }

    private String tableToBaseName(String tableFqn) {
        // org.openrewrite.prethink.table.MethodDescriptions -> method-descriptions
        String simpleName = tableFqn.substring(tableFqn.lastIndexOf('.') + 1);
        return toKebabCase(simpleName);
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
    private static class RenderedTable {
        List<String> pages;
        long omittedRows;
    }

    @Value
    private static class DataTableInfo {
        String displayName;
        String description;
        List<String> filenames;
        long omittedRows;
        List<ColumnInfo> columns;
    }

    @Value
    private static class ColumnInfo {
        String displayName;
        String description;
    }
}
