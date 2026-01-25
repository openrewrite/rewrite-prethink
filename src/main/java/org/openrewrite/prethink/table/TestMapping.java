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

public class TestMapping extends DataTable<TestMapping.Row> {

    public TestMapping(Recipe recipe) {
        super(recipe, "Test mapping",
                "Mapping of test methods to the implementation methods they test.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Test source path",
                description = "The path to the test source file.")
        String testSourcePath;

        @Column(displayName = "Test class",
                description = "The fully qualified name of the test class.")
        String testClass;

        @Column(displayName = "Test method",
                description = "The signature of the test method.")
        String testMethod;

        @Column(displayName = "Implementation source path",
                description = "The path to the implementation source file being tested.")
        String implementationSourcePath;

        @Column(displayName = "Implementation class",
                description = "The fully qualified name of the implementation class being tested.")
        String implementationClass;

        @Column(displayName = "Implementation method",
                description = "The signature of the implementation method being tested.")
        String implementationMethod;

        @Column(displayName = "Test summary",
                description = "AI-generated summary of what the test is verifying.")
        @Nullable
        String testSummary;

        @Column(displayName = "Test checksum",
                description = "Hash of the test method code for cache invalidation.")
        @Nullable
        String testChecksum;
    }
}
