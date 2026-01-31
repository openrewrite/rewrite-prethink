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

public class ErrorHandlingPatterns extends DataTable<ErrorHandlingPatterns.Row> {

    public ErrorHandlingPatterns(Recipe recipe) {
        super(recipe, "Error handling patterns",
                "Error and exception handling patterns detected in the codebase.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The path to the source file.")
        String sourcePath;

        @Column(displayName = "Pattern type",
                description = "The type of error handling pattern (try-catch, throws, global handler).")
        String patternType;

        @Column(displayName = "Exception types",
                description = "The exception types being handled or thrown.")
        @Nullable
        String exceptionTypes;

        @Column(displayName = "Handling strategy",
                description = "How the error is handled (log, rethrow, wrap, suppress).")
        @Nullable
        String handlingStrategy;

        @Column(displayName = "Context",
                description = "The class and method where this pattern occurs.")
        @Nullable
        String context;

        @Column(displayName = "Details",
                description = "Additional details about the error handling.")
        @Nullable
        String details;
    }
}
