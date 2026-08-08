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

import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;
import com.univocity.parsers.csv.CsvWriter;
import com.univocity.parsers.csv.CsvWriterSettings;
import org.jspecify.annotations.Nullable;
import org.openrewrite.DataTable;
import org.openrewrite.DataTableStore;
import org.openrewrite.PathUtils;
import org.openrewrite.marker.GitProvenance;
import org.openrewrite.prethink.table.ProjectMetadata;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.openrewrite.prethink.Prethink.CONTEXT_DIR;

/**
 * Filesystem plumbing shared by the organizational Prethink recipes.
 * <p>
 * Unlike the per-repository recipes, which express their output as source file
 * changes inside the repository being analyzed, these recipes write to a
 * directory that is deliberately <em>outside</em> any repository: one collection
 * that every repository analyzed contributes to, so an agent started there can
 * reason across all of them at once. That means writing to the filesystem
 * directly rather than through the changeset, and it means several repositories
 * -- analyzed in parallel threads by one {@code mod run}, or by separate
 * {@code mod run} invocations -- may be updating the same files at the same
 * time. Every update therefore runs under {@link #locked}, and the combined CSVs
 * are merged by streaming rather than by reading them into memory: a table
 * combined across an organization can be far larger than any single
 * repository's.
 */
final class OrganizationalContext {

    /**
     * The first column of every combined CSV, identifying which repository a row
     * was extracted from. It is what makes the tables of many repositories safe
     * to concatenate, and what an agent filters and joins on.
     */
    static final String REPOSITORY_COLUMN = "Repository";

    /**
     * Index of every repository in the collection, written alongside the
     * combined tables.
     */
    static final String REPOSITORIES_FILE = "repositories.csv";

    /**
     * Catalog of the combined tables: which data table produced each CSV, and how
     * that table describes itself.
     * <p>
     * A collection outlives the run that created any one of its tables. When the
     * set of tables is discovered from each run rather than configured up front, a
     * repository that contributes nothing to a table still has to be able to
     * describe it -- otherwise exporting a repository with a narrower composite
     * would silently shrink the description of a table other repositories are
     * still filling.
     */
    static final String TABLES_FILE = "tables.csv";

    static final String[] TABLE_INDEX_HEADERS = {"Table file", "Data table", "Display name", "Description"};

    static final String UNKNOWN_REPOSITORY = "unknown";

    private static final String LOCK_FILE = ".prethink-lock";

    /**
     * Serializes updates from repositories analyzed in parallel within this JVM.
     * The {@link FileLock} taken alongside it only excludes other processes;
     * overlapping locks on one file from within a single JVM are rejected
     * outright rather than queued.
     */
    private static final Object JVM_WRITE_LOCK = new Object();

    private OrganizationalContext() {
    }

    /**
     * Where an organizational collection keeps its files. The context directory
     * mirrors the per-repository layout ({@code .moderne/context/}) so that the
     * same conventions -- and the same agent instructions -- apply whether the
     * context describes one repository or a hundred.
     */
    static final class Layout {
        final Path root;
        final Path context;
        final Path lockFile;

        private Layout(Path root) {
            this.root = root;
            this.context = root.resolve(CONTEXT_DIR);
            this.lockFile = root.resolve(CONTEXT_DIR.getParent()).resolve(LOCK_FILE);
        }
    }

    /**
     * Resolve the target directory and create the context directory within it.
     * A relative target resolves against the working directory of the process
     * running the recipe, which is why absolute paths are recommended.
     */
    static Layout layout(String targetDirectory) throws IOException {
        Layout layout = new Layout(Paths.get(targetDirectory).toAbsolutePath().normalize());
        Files.createDirectories(layout.context);
        return layout;
    }

    @FunctionalInterface
    interface IoTask {
        void run() throws IOException;
    }

    @FunctionalInterface
    interface RowSource {
        /**
         * Write this repository's rows, each beginning with the repository
         * column, and return how many were written.
         */
        long write(CsvWriter writer) throws IOException;
    }

    /**
     * Run an update to the collection while holding exclusive access to it,
     * against both other threads in this JVM and other processes.
     */
    static void locked(Layout layout, IoTask task) throws IOException {
        synchronized (JVM_WRITE_LOCK) {
            try (FileChannel channel = FileChannel.open(layout.lockFile, CREATE, WRITE);
                 FileLock ignored = channel.lock()) {
                task.run();
            }
        }
    }

    /**
     * The identity a repository contributes rows under, preferring an explicit
     * override, then the {@code organization/repository} of the git remote. The
     * result doubles as a path segment (for per-repository files such as the
     * CALM architecture), so it is restricted to characters every filesystem
     * accepts.
     */
    static String repositoryId(@Nullable GitProvenance provenance, @Nullable String override, @Nullable String fallback) {
        if (!isBlank(override)) {
            return sanitize(override);
        }
        if (provenance != null) {
            String path = provenance.getRepositoryPath();
            if (!isBlank(path)) {
                return sanitize(path);
            }
            String name = provenance.getRepositoryName();
            if (!isBlank(name)) {
                String organization = provenance.getOrganizationName();
                return sanitize(isBlank(organization) ? name : organization + "/" + name);
            }
        }
        if (!isBlank(fallback)) {
            return sanitize(fallback);
        }
        return UNKNOWN_REPOSITORY;
    }

    /**
     * Where a file in the collection sits relative to the collection root --
     * which is where an agent reading it runs, and so how it has to be named in
     * anything the agent is expected to act on.
     */
    static String contextPath(String filename) {
        return PathUtils.separatorsToUnix(CONTEXT_DIR.resolve(filename).toString());
    }

    /**
     * The first {@link ProjectMetadata} row a run discovered, which names the
     * repository when there is no git remote to name it and describes it in the
     * repository index.
     */
    static ProjectMetadata.@Nullable Row projectMetadata(DataTableStore store) {
        for (DataTable<?> dataTable : store.getDataTables()) {
            if (ProjectMetadata.class.getName().equals(dataTable.getClass().getName())) {
                try (Stream<ProjectMetadata.Row> rows = store.getRows(ProjectMetadata.class, dataTable.getGroup())) {
                    Optional<ProjectMetadata.Row> first = rows.findFirst();
                    if (first.isPresent()) {
                        return first.get();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Reduce an identifier to slash-separated segments of characters that are
     * safe in a file name, so it can be used both as a CSV value and as a
     * relative path. Segments that would escape the collection ({@code .},
     * {@code ..}, empty) are dropped.
     */
    static String sanitize(@Nullable String id) {
        if (id == null) {
            return UNKNOWN_REPOSITORY;
        }
        StringBuilder result = new StringBuilder();
        for (String rawSegment : id.trim().split("[/\\\\]")) {
            StringBuilder segment = new StringBuilder();
            for (int i = 0; i < rawSegment.length(); i++) {
                char c = rawSegment.charAt(i);
                segment.append(Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' ? c : '-');
            }
            String cleaned = segment.toString();
            while (cleaned.startsWith(".")) {
                cleaned = cleaned.substring(1);
            }
            if (cleaned.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append('/');
            }
            result.append(cleaned);
        }
        return result.length() == 0 ? UNKNOWN_REPOSITORY : result.toString();
    }

    /**
     * Replace one repository's rows in a combined CSV, leaving every other
     * repository's rows untouched.
     * <p>
     * The existing file is streamed into a temporary sibling and the new rows
     * appended, so the combined table is never held in memory. Columns are
     * matched by name, so rows contributed by other repositories survive a
     * schema change in the data table. Returns {@code false} when the merge left
     * no rows at all, in which case the file is deleted rather than left behind
     * with only headers.
     */
    static boolean mergeCsv(Path csvFile, String repository, String[] headers, RowSource rows) throws IOException {
        Path temporary = csvFile.resolveSibling(csvFile.getFileName() + ".tmp");
        long written;
        CsvWriter writer = new CsvWriter(Files.newBufferedWriter(temporary, UTF_8), new CsvWriterSettings());
        try {
            writer.writeHeaders(headers);
            written = copyOtherRepositories(csvFile, repository, headers, writer);
            written += rows.write(writer);
        } finally {
            writer.close();
        }
        if (written == 0) {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(csvFile);
            return false;
        }
        move(temporary, csvFile);
        return true;
    }

    private static long copyOtherRepositories(Path csvFile, String repository, String[] headers, CsvWriter writer) throws IOException {
        if (!Files.exists(csvFile)) {
            return 0;
        }
        long copied = 0;
        CsvParser parser = new CsvParser(parserSettings(headers.length));
        String[] values = new String[headers.length];
        try (Reader reader = Files.newBufferedReader(csvFile, UTF_8)) {
            parser.beginParsing(reader);
            int[] columns = null;
            String[] row;
            while ((row = parser.parseNext()) != null) {
                if (columns == null) {
                    // Headers are only available once the first record has been read.
                    columns = mapColumns(headers, parser.getContext().headers());
                }
                // columns[0] is where the repository column we wrote ended up.
                if (columns[0] >= 0 && columns[0] < row.length && repository.equals(row[columns[0]])) {
                    continue;
                }
                for (int i = 0; i < headers.length; i++) {
                    int at = columns[i];
                    values[i] = at < 0 || at >= row.length || row[at] == null ? "" : row[at];
                }
                writer.writeRow((Object[]) values);
                copied++;
            }
        }
        return copied;
    }

    /**
     * Where each column of the schema being written sits in a file that was
     * written earlier, or {@code -1} for one that file does not have.
     * <p>
     * Matching by name rather than position is what lets rows contributed by
     * other repositories survive a data table gaining, losing, or reordering a
     * column. Repeated names are matched in order of occurrence, so a data table
     * that has a column of its own called {@code Repository} does not collide
     * with the one prepended here.
     */
    private static int[] mapColumns(String[] headers, String @Nullable [] existingHeaders) {
        Map<String, Deque<Integer>> positions = new HashMap<>();
        if (existingHeaders != null) {
            for (int i = 0; i < existingHeaders.length; i++) {
                if (existingHeaders[i] != null) {
                    positions.computeIfAbsent(existingHeaders[i], k -> new ArrayDeque<>()).add(i);
                }
            }
        }
        int[] mapping = new int[headers.length];
        for (int i = 0; i < headers.length; i++) {
            Deque<Integer> available = positions.get(headers[i]);
            mapping[i] = available == null || available.isEmpty() ? -1 : available.poll();
        }
        return mapping;
    }

    /**
     * Remove one repository's rows from a combined CSV without needing to know
     * which data table produced it, reusing the headers the file already has.
     * <p>
     * This is what keeps a discovered -- rather than configured -- set of tables
     * honest: a table a repository contributed to last time but not this time is
     * not in this run's data table store at all, so it can only be found by
     * sweeping the collection. Returns whether the file survived; a CSV left with
     * no rows is deleted.
     */
    static boolean purgeRepository(Path csvFile, String repository) throws IOException {
        if (!Files.exists(csvFile)) {
            return false;
        }
        String[] headers = headersOf(csvFile);
        if (headers == null) {
            Files.deleteIfExists(csvFile);
            return false;
        }
        return mergeCsv(csvFile, repository, headers, writer -> 0);
    }

    /**
     * The header row of an existing CSV, or {@code null} when it has none.
     */
    static String @Nullable [] headersOf(Path csvFile) throws IOException {
        CsvParser parser = new CsvParser(parserSettings(64));
        try (Reader reader = Files.newBufferedReader(csvFile, UTF_8)) {
            parser.beginParsing(reader);
            parser.parseNext();
            String[] headers = parser.getContext().headers();
            parser.stopParsing();
            return headers;
        }
    }

    /**
     * Every combined table currently in the collection, other than the indexes
     * that describe the collection itself.
     */
    static List<Path> tableFiles(Path contextDir) throws IOException {
        List<Path> tables = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(contextDir, "*.csv")) {
            for (Path entry : entries) {
                String filename = entry.getFileName().toString();
                if (!REPOSITORIES_FILE.equals(filename) && !TABLES_FILE.equals(filename)) {
                    tables.add(entry);
                }
            }
        }
        Collections.sort(tables);
        return tables;
    }

    /**
     * The table catalog, keyed by CSV filename and ordered so that the files
     * generated from it are stable across runs.
     */
    static Map<String, String[]> readTableIndex(Path indexFile) throws IOException {
        Map<String, String[]> index = new TreeMap<>();
        if (!Files.exists(indexFile)) {
            return index;
        }
        CsvParser parser = new CsvParser(parserSettings(TABLE_INDEX_HEADERS.length));
        try (Reader reader = Files.newBufferedReader(indexFile, UTF_8)) {
            parser.beginParsing(reader);
            int[] columns = null;
            String[] row;
            while ((row = parser.parseNext()) != null) {
                if (columns == null) {
                    columns = mapColumns(TABLE_INDEX_HEADERS, parser.getContext().headers());
                }
                String[] values = new String[TABLE_INDEX_HEADERS.length];
                for (int i = 0; i < values.length; i++) {
                    int at = columns[i];
                    values[i] = at < 0 || at >= row.length || row[at] == null ? "" : row[at];
                }
                if (!values[0].isEmpty()) {
                    index.put(values[0], values);
                }
            }
        }
        return index;
    }

    static void writeTableIndex(Path indexFile, Map<String, String[]> index) throws IOException {
        if (index.isEmpty()) {
            Files.deleteIfExists(indexFile);
            return;
        }
        StringWriter rendered = new StringWriter();
        CsvWriter writer = new CsvWriter(rendered, new CsvWriterSettings());
        try {
            writer.writeHeaders(TABLE_INDEX_HEADERS);
            for (String[] row : index.values()) {
                writer.writeRow((Object[]) row);
            }
        } finally {
            writer.close();
        }
        // Small enough to render in memory, unlike the combined tables it describes.
        writeIfChanged(indexFile, rendered.toString());
    }

    /**
     * Whether a combined CSV already holds rows for a repository. Used to decide
     * whether a table this repository contributed nothing to still has to be
     * rewritten, to purge what an earlier run left behind.
     */
    static boolean containsRepository(Path csvFile, String repository) throws IOException {
        if (!Files.exists(csvFile)) {
            return false;
        }
        CsvParser parser = new CsvParser(parserSettings(8));
        try (Reader reader = Files.newBufferedReader(csvFile, UTF_8)) {
            parser.beginParsing(reader);
            int repositoryColumn = -1;
            String[] row;
            while ((row = parser.parseNext()) != null) {
                if (repositoryColumn < 0) {
                    repositoryColumn = mapColumns(new String[]{REPOSITORY_COLUMN}, parser.getContext().headers())[0];
                    if (repositoryColumn < 0) {
                        parser.stopParsing();
                        return false;
                    }
                }
                if (repositoryColumn < row.length && repository.equals(row[repositoryColumn])) {
                    parser.stopParsing();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * How many repositories the collection currently holds.
     */
    static int repositoryCount(Path repositoriesCsv) throws IOException {
        if (!Files.exists(repositoriesCsv)) {
            return 0;
        }
        int count = 0;
        CsvParser parser = new CsvParser(parserSettings(8));
        try (Reader reader = Files.newBufferedReader(repositoriesCsv, UTF_8)) {
            parser.beginParsing(reader);
            while (parser.parseNext() != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * Write a file only when its content would change, so that repeatedly
     * exporting an unchanged repository leaves the collection byte-for-byte
     * identical.
     */
    static void writeIfChanged(Path file, String content) throws IOException {
        if (Files.exists(file) && content.equals(new String(Files.readAllBytes(file), UTF_8))) {
            return;
        }
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temporary, content.getBytes(UTF_8));
        move(temporary, file);
    }

    static String read(Path file) throws IOException {
        return Files.exists(file) ? new String(Files.readAllBytes(file), UTF_8) : "";
    }

    private static CsvParserSettings parserSettings(int columns) {
        CsvParserSettings settings = new CsvParserSettings();
        settings.setHeaderExtractionEnabled(true);
        // Descriptions generated by AI recipes routinely exceed the 4096 character default.
        settings.setMaxCharsPerColumn(-1);
        settings.setMaxColumns(Math.max(512, columns * 2));
        return settings;
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, REPLACE_EXISTING, ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, REPLACE_EXISTING);
        }
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }
}
