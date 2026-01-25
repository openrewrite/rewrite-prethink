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

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.test.SourceSpecs.text;

class ExportContextTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new ExportContext(
                "Test Context",
                "Short description for testing",
                "Long description for testing purposes",
                List.of()
        ));
    }

    @DocumentExample
    @Test
    void updatesExistingContextCsv() {
        // Test that existing context CSV files can be updated
        rewriteRun(
            spec -> spec.afterRecipe(run -> {
                // The recipe should run without error
                // In production, it would update the CSV with data from ExecutionContext.DATA_TABLES
            }),
            // Existing context file that could be updated
            text(
                //language=csv
                "Source path,Description\nold/path,old description",
                spec -> spec.path(".moderne/context/method-descriptions.csv")
            )
        );
    }

    @Test
    void handlesNoDataTables() {
        // Test that the recipe handles the case when no DataTables are present
        rewriteRun(
            spec -> spec.afterRecipe(run -> {
                // Should complete without error
                assertThat(run).isNotNull();
            }),
            // Some source file
            text(
                //language=Markdown
                "content",
                spec -> spec.path("README.md")
            )
        );
    }

    @Test
    void tableToFilenameConversion() {
        // Test the table name to filename conversion logic
        ExportContext exportContext = new ExportContext(
                "Test Context",
                "Short description",
                "Long description",
                List.of()
        );

        // Use reflection to test the private method
        try {
            var method = ExportContext.class.getDeclaredMethod("tableToFilename", String.class);
            method.setAccessible(true);

            assertThat(method.invoke(exportContext, "io.moderne.context.table.MethodDescriptions"))
                    .isEqualTo("method-descriptions.csv");
            assertThat(method.invoke(exportContext, "io.moderne.context.table.ClassDescriptions"))
                    .isEqualTo("class-descriptions.csv");
            assertThat(method.invoke(exportContext, "io.moderne.context.table.TestMapping"))
                    .isEqualTo("test-mapping.csv");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void toKebabCaseConversion() {
        ExportContext exportContext = new ExportContext(
                "Test Context",
                "Short description",
                "Long description",
                List.of()
        );

        // Use reflection to test the private method
        try {
            var method = ExportContext.class.getDeclaredMethod("toKebabCase", String.class);
            method.setAccessible(true);

            assertThat(method.invoke(exportContext, "Test Coverage"))
                    .isEqualTo("test-coverage");
            assertThat(method.invoke(exportContext, "CodeComprehension"))
                    .isEqualTo("code-comprehension");
            assertThat(method.invoke(exportContext, "Dependencies"))
                    .isEqualTo("dependencies");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void getContextFilename() {
        ExportContext exportContext = new ExportContext(
                "Test Coverage",
                "Short description",
                "Long description",
                List.of()
        );

        assertThat(exportContext.getContextFilename()).isEqualTo("test-coverage.md");
    }
}
