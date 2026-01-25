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

public class ClassDescriptions extends DataTable<ClassDescriptions.Row> {

    public ClassDescriptions(Recipe recipe) {
        super(recipe, "Class descriptions",
                "AI-generated descriptions of classes in the codebase.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The path to the source file containing the class.")
        String sourcePath;

        @Column(displayName = "Class name",
                description = "The fully qualified name of the class.")
        String className;

        @Column(displayName = "Checksum",
                description = "SHA-256 checksum of the class source text for incremental updates.")
        String checksum;

        @Column(displayName = "Description",
                description = "AI-generated description of what the class does.")
        String description;

        @Column(displayName = "Responsibility",
                description = "The primary responsibility or purpose of the class (2-3 words).")
        String responsibility;

        @Column(displayName = "Pattern 1",
                description = "First architectural pattern used by this class.")
        @Nullable
        String pattern1;

        @Column(displayName = "Pattern 2",
                description = "Second architectural pattern used by this class.")
        @Nullable
        String pattern2;

        @Column(displayName = "Pattern 3",
                description = "Third architectural pattern used by this class.")
        @Nullable
        String pattern3;

        @Column(displayName = "Inference time (ms)",
                description = "Time taken to generate the description in milliseconds.")
        long inferenceTimeMs;
    }
}
