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

import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Base class for Prethink recipes that write rows to data tables in cycle 1's edit phase
 * and need to read those data tables in cycle 2 to export context files under
 * {@link Prethink#CONTEXT_DIR}.
 * <p>
 * Encapsulates the shared infrastructure these recipes need:
 * <ul>
 *   <li>An {@link Accumulator} that tracks which context files already exist in the LST,
 *       so {@link #cycle2Visit} can update them and {@code generate()} (overridden per recipe)
 *       can avoid creating duplicates.</li>
 *   <li>A scanner that populates that accumulator.</li>
 *   <li>{@link #causesAnotherCycle()} returning {@code true}.</li>
 *   <li>A cycle-1 visitor that requests another cycle via the framework's
 *       {@code WatchableExecutionContext.hasNewMessages} channel, so the
 *       {@code RecipeScheduler} loop runs cycle 2 even when no other recipe in the
 *       pipeline contributes a tree-level change in cycle 1.</li>
 *   <li>A cycle-2 dispatch to {@link #cycle2Visit}, which subclasses implement.</li>
 * </ul>
 * <p>
 * Subclasses typically also override {@code generate()} to emit new context files
 * on cycle 2 when the path is not already present in {@link Accumulator#getExistingContextPaths()}.
 * <p>
 * The accumulator shape is intentionally fixed to a single {@code Set<Path>}. Recipes
 * needing additional per-run state should keep that state outside the accumulator
 * (or stay outside this base class) until a need to generalize emerges.
 */
public abstract class PrethinkContextRecipe extends ScanningRecipe<PrethinkContextRecipe.Accumulator> {

    @Override
    public final boolean causesAnotherCycle() {
        return true;
    }

    @Override
    public final Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator(new HashSet<>());
    }

    @Override
    public final TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    Path path = ((SourceFile) tree).getSourcePath();
                    if (path.startsWith(Prethink.CONTEXT_DIR)) {
                        acc.getExistingContextPaths().add(path);
                    }
                }
                return tree;
            }
        };
    }

    @Override
    public final TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                // Cycle 1: data tables aren't populated yet because their producers fill rows
                // during the edit phase of cycle 1, after this recipe's generate() has already
                // run. Request another cycle so the scheduler doesn't terminate the loop after
                // cycle 1 when no other recipe makes a tree-level change.
                if (ctx.getCycle() == 1) {
                    ctx.putMessage(Prethink.CYCLE_TRIGGER, true);
                    return tree;
                }
                // Cycle 3+ would mean another recipe is still making changes; this recipe's
                // work was already done in cycle 2. Returning early avoids wasted work and
                // keeps cycle2Visit implementations from having to be idempotent across cycles.
                if (ctx.getCycle() != 2) {
                    return tree;
                }
                return cycle2Visit(tree, acc, ctx);
            }
        };
    }

    /**
     * Visit each source file in cycle 2, after data table producers have populated their
     * tables in cycle 1's edit phase. Implementations may return a modified tree to apply
     * an update, return {@code null} to delete the file, or return the tree unchanged.
     *
     * @param tree the source file being visited
     * @param acc  the accumulator populated during the scanner phase
     * @param ctx  the execution context, including populated data tables
     * @return the visited tree (possibly modified), or {@code null} to delete it
     */
    protected abstract @Nullable Tree cycle2Visit(@Nullable Tree tree, Accumulator acc, ExecutionContext ctx);

    @Value
    public static class Accumulator {
        Set<Path> existingContextPaths;
    }
}
