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

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Shared constants for Prethink recipes.
 */
public final class Prethink {

    /**
     * The directory where Prethink context files are stored.
     */
    public static final Path CONTEXT_DIR = Paths.get(".moderne", "context");

    /**
     * ExecutionContext message key whose <em>presence</em> requests another scheduler cycle.
     * The key itself is never read; the value is never consumed. Recipes call
     * {@code ctx.putMessage(CYCLE_TRIGGER, true)} for the side effect of flipping
     * {@code WatchableExecutionContext.hasNewMessages}, which {@code RecipeRunCycle}
     * treats as "this recipe made a change" and combines with {@code causesAnotherCycle()}
     * to enroll the recipe for the next cycle. The {@code io.moderne.} prefix is required
     * by {@code CursorValidatingExecutionContextView}'s allow-list for ExecutionContext mutations.
     */
    public static final String CYCLE_TRIGGER = "io.moderne.prethink.cycleTrigger";

    private Prethink() {
    }
}
