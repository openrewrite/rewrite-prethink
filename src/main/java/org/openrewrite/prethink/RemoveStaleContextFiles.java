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
 * Delete {@code .moderne/context/} CSV/markdown files that no {@link ExportContext} produced this run
 * (tracked via {@link Prethink#KEPT_CONTEXT_FILES}), removing files left behind by an earlier recipe
 * version. Non-CSV/markdown files (e.g. {@code calm-architecture.json}) are left alone. Must run after
 * every {@link ExportContext} so the kept-file set is complete; if none ran, nothing is deleted.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class RemoveStaleContextFiles extends Recipe {

    String displayName = "Remove stale Prethink context files";

    String description = "Delete `.moderne/context/` CSV and markdown files that no current context produces, " +
            "so documentation left behind by a previous recipe version (renamed columns, removed " +
            "tables, restructured contexts) does not linger out of sync with the regenerated CSVs. " +
            "Must run after every `ExportContext` so the set of files to keep is complete.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile)) {
                    return tree;
                }

                Set<String> keptContextFiles = ctx.getMessage(Prethink.KEPT_CONTEXT_FILES);
                if (keptContextFiles == null) {
                    return tree;
                }

                Path path = ((SourceFile) tree).getSourcePath();
                if (!path.startsWith(CONTEXT_DIR)) {
                    return tree;
                }

                String filename = path.getFileName().toString();
                if (!filename.endsWith(".csv") && !filename.endsWith(".md")) {
                    return tree;
                }

                if (!keptContextFiles.contains(filename)) {
                    return null;
                }
                return tree;
            }
        };
    }
}
