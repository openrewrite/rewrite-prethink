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
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;

import java.nio.file.Path;
import java.util.Set;

import static org.openrewrite.prethink.Prethink.CONTEXT_DIR;

/**
 * Remove stale Prethink context files that no live context produces.
 * <p>
 * Each Prethink context is a CSV of LST-derived data plus a markdown file describing that CSV's
 * schema. {@link ExportContext} regenerates both from the same {@code @Column} metadata, so within
 * a single run they always agree. Drift appears <em>across</em> runs: when a data table is removed,
 * its columns are renamed, or a context is restructured (for example, per-table markdown files
 * collapsed into a single bundled {@code architecture.md}), the files a previous recipe version
 * committed to {@code .moderne/context/} are left behind. The current run refreshes the files it
 * still owns while the orphans stay frozen at their old schema, so a coding agent reads a stale
 * markdown doc next to a regenerated CSV.
 * <p>
 * This recipe deletes any {@code .moderne/context/} {@code *.csv} or {@code *.md} file that was not
 * produced by an {@link ExportContext} in this run (tracked via {@link Prethink#KEPT_CONTEXT_FILES}).
 * Non-CSV/markdown context files (such as {@code calm-architecture.json}) are never touched.
 * <p>
 * It must run <em>after</em> every {@link ExportContext} in the pipeline so the kept-file set is
 * complete before anything is deleted; if no {@link ExportContext} has run, the set is absent and
 * this recipe deletes nothing.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class RemoveStaleContextFiles extends Recipe {

    @Override
    public String getDisplayName() {
        return "Remove stale Prethink context files";
    }

    @Override
    public String getDescription() {
        return "Delete `.moderne/context/` CSV and markdown files that no current context produces, " +
               "so documentation left behind by a previous recipe version (renamed columns, removed " +
               "tables, restructured contexts) does not linger out of sync with the regenerated CSVs. " +
               "Must run after every `ExportContext` so the set of files to keep is complete.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile)) {
                    return tree;
                }

                Set<String> keptContextFiles = ctx.getMessage(Prethink.KEPT_CONTEXT_FILES);
                // No ExportContext has published its files yet (e.g. cycle 1, or a pipeline with no
                // ExportContext). Deleting now would be unsafe, so keep everything.
                if (keptContextFiles == null) {
                    return tree;
                }

                Path path = ((SourceFile) tree).getSourcePath();
                if (!path.startsWith(CONTEXT_DIR)) {
                    return tree;
                }

                String filename = path.getFileName().toString();
                // Only CSV/markdown pairs can drift out of sync; leave everything else (e.g.
                // calm-architecture.json) untouched.
                if (!filename.endsWith(".csv") && !filename.endsWith(".md")) {
                    return tree;
                }

                // A file no live context produced is an orphan from an earlier recipe version.
                if (!keptContextFiles.contains(filename)) {
                    return null;
                }
                return tree;
            }
        };
    }
}
