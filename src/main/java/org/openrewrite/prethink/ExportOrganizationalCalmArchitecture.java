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

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.marker.GitProvenance;
import org.openrewrite.prethink.calm.GenerateCalmArchitecture;
import org.openrewrite.prethink.table.ProjectMetadata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.openrewrite.prethink.OrganizationalContext.*;

/**
 * Collect the FINOS CALM architecture of each repository into a shared,
 * multi-repository Prethink collection.
 * <p>
 * The per-repository recipe writes one {@code calm-architecture.json} into the
 * repository it describes. Here each repository instead contributes
 * {@code architecture/<repository>.json}, keyed by the same repository name the
 * combined CSVs use, so an agent can move between a row in a combined table and
 * the architecture of the system that row belongs to.
 * <p>
 * Nothing is written to the repository being analyzed: the collection is updated
 * on the filesystem as a side effect, and this recipe produces no changes.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class ExportOrganizationalCalmArchitecture extends ScanningRecipe<ExportOrganizationalCalmArchitecture.Accumulator> {

    private static final String ARCHITECTURE_DIR = "architecture";
    private static final String MARKDOWN_FILE = "calm-architecture.md";

    @Option(displayName = "Target directory",
            description = "The directory holding the combined context, which may be outside of the repository " +
                          "being analyzed. Architecture files are written to `.moderne/context/architecture/` " +
                          "within it. A relative path, and no path at all, resolve against the working " +
                          "directory of the process running the recipe, so an absolute path is recommended.",
            required = false,
            example = "/var/lib/prethink/acme")
    @Nullable
    String targetDirectory;

    @Option(displayName = "Repository",
            description = "The name this repository's architecture is filed under, matching the `Repository` " +
                          "column of the combined tables. If not specified, it is taken from the git remote as " +
                          "`organization/repository`.",
            required = false,
            example = "acme/orders-service")
    @Nullable
    String repositoryName;

    String displayName = "Export organizational [CALM](https://calm.finos.org/) architecture";

    String description = "Write the FINOS CALM (Common Architecture Language Model) architecture of the " +
            "repository being analyzed into a shared directory that many repositories contribute to, as " +
            "`architecture/<repository>.json`. This recipe reads data tables that other Prethink discovery " +
            "recipes populate first, so it produces nothing in isolation. The collection is written directly " +
            "to the filesystem; the repository being analyzed is left unchanged.";

    @Override
    public boolean causesAnotherCycle() {
        return true;
    }

    public static class Accumulator {
        @Nullable
        volatile GitProvenance provenance;

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
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (ctx.getCycle() == 1) {
                    // The architectural data tables are populated during this cycle's edit phase.
                    // This recipe changes no source file, so ask for another cycle explicitly.
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
            acc.exported = true;

            DataTableStore store = DataTableExecutionContextView.view(ctx).getDataTableStore();
            ProjectMetadata.Row project = projectMetadata(store);
            String repository = repositoryId(acc.provenance, repositoryName,
                    project == null ? null : project.getArtifactId());
            String architecture = new GenerateCalmArchitecture().generateCalmJsonFromDataTables(ctx);

            try {
                Layout layout = layout(targetDirectory);
                locked(layout, () -> write(layout, repository, architecture));
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Unable to write the organizational CALM architecture to " + targetPath(targetDirectory), e);
            }
        }
    }

    private void write(Layout layout, String repository, @Nullable String architecture) throws IOException {
        Path architectureDir = layout.context.resolve(ARCHITECTURE_DIR);
        Path architectureFile = architectureDir.resolve(repository + ".json");
        if (architecture == null) {
            // No architectural data was discovered this run, so drop whatever an
            // earlier run left for this repository rather than let it go stale.
            Files.deleteIfExists(architectureFile);
            deleteEmptyParents(architectureFile.getParent(), architectureDir);
        } else {
            writeIfChanged(architectureFile, architecture);
        }

        Path markdown = layout.context.resolve(MARKDOWN_FILE);
        if (isEmptyDirectory(architectureDir)) {
            Files.deleteIfExists(markdown);
            Files.deleteIfExists(architectureDir);
        } else {
            writeIfChanged(markdown, markdown());
        }
    }

    private String markdown() {
        return "# CALM Architecture\n" +
               "\n" +
               "## FINOS CALM architecture diagram of each repository\n" +
               "\n" +
               "One [FINOS CALM](https://calm.finos.org/) (Common Architecture Language Model) document per " +
               "repository, showing that repository's services, databases, external integrations, and messaging " +
               "connections, and the relationships between them. Use these to understand the internal shape of a " +
               "system before reading any of its source, and the combined tables to see how systems reach each " +
               "other.\n" +
               "\n" +
               "## Files\n" +
               "\n" +
               "Each document is at `" + contextPath(ARCHITECTURE_DIR) + "/<repository>.json`, where " +
               "`<repository>` is that repository's value in the `" + REPOSITORY_COLUMN + "` column of the " +
               "combined tables, listed in [`" + contextPath(REPOSITORIES_FILE) + "`](" +
               contextPath(REPOSITORIES_FILE) + "). A repository with no discovered architecture has no file " +
               "here, so check for it rather than assuming it exists.\n";
    }

    private boolean isEmptyDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return true;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            return !entries.iterator().hasNext();
        }
    }

    /**
     * Remove the directories a nested repository name created, up to but not
     * including the architecture directory itself, once they hold nothing.
     */
    private void deleteEmptyParents(@Nullable Path directory, Path stopAt) throws IOException {
        Path current = directory;
        while (current != null && !current.equals(stopAt) && current.startsWith(stopAt) && isEmptyDirectory(current)) {
            Files.deleteIfExists(current);
            current = current.getParent();
        }
    }
}
