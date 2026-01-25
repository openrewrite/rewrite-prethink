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

public class MethodDescriptions extends DataTable<MethodDescriptions.Row> {

    public MethodDescriptions(Recipe recipe) {
        super(recipe, "Method descriptions",
                "AI-generated descriptions of methods in the codebase.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The path to the source file containing the method.")
        String sourcePath;

        @Column(displayName = "Class name",
                description = "The fully qualified name of the class containing the method.")
        String className;

        @Column(displayName = "Signature",
                description = "The method signature.")
        String signature;

        @Column(displayName = "Checksum",
                description = "SHA-256 checksum of the method source text for incremental updates.")
        String checksum;

        @Column(displayName = "Description",
                description = "AI-generated description of what the method does.")
        String description;

        @Column(displayName = "Return value description",
                description = "AI-generated description of what the method returns.")
        @Nullable
        String returnValueDescription;

        @Column(displayName = "Technique 1",
                description = "First technique or library used by this method.")
        @Nullable
        String technique1;

        @Column(displayName = "Technique 2",
                description = "Second technique or library used by this method.")
        @Nullable
        String technique2;

        @Column(displayName = "Technique 3",
                description = "Third technique or library used by this method.")
        @Nullable
        String technique3;

        @Column(displayName = "Inference time (ms)",
                description = "Time taken to generate the description in milliseconds.")
        long inferenceTimeMs;
    }
}
