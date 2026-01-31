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
package org.openrewrite.prethink.table;

import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class CodingConventions extends DataTable<CodingConventions.Row> {

    public CodingConventions(Recipe recipe) {
        super(recipe, "Coding conventions",
                "Coding conventions and patterns detected in the codebase.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Convention type",
                description = "The type of convention (naming, comments, imports, formatting).")
        String conventionType;

        @Column(displayName = "Pattern",
                description = "Description of the detected pattern.")
        String pattern;

        @Column(displayName = "Example",
                description = "An example from the codebase.")
        @Nullable
        String example;

        @Column(displayName = "Frequency",
                description = "How often this pattern occurs.")
        @Nullable
        String frequency;

        @Column(displayName = "Scope",
                description = "Where this convention applies (project-wide, package, class).")
        @Nullable
        String scope;
    }
}
