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

public class DependencyUsage extends DataTable<DependencyUsage.Row> {

    public DependencyUsage(Recipe recipe) {
        super(recipe, "Dependency usage",
                "External library dependencies and how they are used in the codebase.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Library",
                description = "The GAV coordinates of the library.")
        String library;

        @Column(displayName = "Top-level package",
                description = "The top-level package of the library.")
        @Nullable
        String topLevelPackage;

        @Column(displayName = "Usage pattern",
                description = "How the library is typically used.")
        @Nullable
        String usagePattern;

        @Column(displayName = "Common classes",
                description = "Commonly used classes from this library.")
        @Nullable
        String commonClasses;

        @Column(displayName = "Import count",
                description = "Number of files that use this library.")
        int importCount;

        @Column(displayName = "Example usage",
                description = "An example of how this library is used.")
        @Nullable
        String exampleUsage;
    }
}
