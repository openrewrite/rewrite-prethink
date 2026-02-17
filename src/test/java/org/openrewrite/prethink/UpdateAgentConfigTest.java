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

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.test.SourceSpecs.text;

class UpdateAgentConfigTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UpdateAgentConfig(null));
    }

    @DocumentExample
    @Test
    void addsContextSectionToClaudeMd() {
        rewriteRun(
            // Context markdown file (scanner needs to find it to extract metadata)
            text(
                //language=Markdown
                """
                # Test Coverage

                ## Maps test methods to implementation methods they verify

                This context maps each test method to the implementation methods it calls.

                ## Data Tables

                ### Test Mapping

                **File:** [`test-mapping.csv`](test-mapping.csv)
                """,
                spec -> spec.path(".moderne/context/test-coverage.md")
            ),
            // CLAUDE.md file to be updated
            text(
                //language=Markdown
                """
                # Project Documentation

                This is my project.
                """,
                spec -> spec.path("CLAUDE.md").after(after ->
                    assertThat(after)
                        // Check section markers
                        .contains("<!-- prethink-context -->")
                        .contains("<!-- /prethink-context -->")
                        // Check section content
                        .contains("## Moderne Prethink Context")
                        .contains("Moderne Prethink")
                        // Check context table
                        .contains("| Test Coverage |")
                        .contains("test-coverage.md")
                        // Check for template content (agent instructions)
                        .contains("IMPORTANT: Before exploring source code")
                        .contains("### Usage Pattern")
                        // Verify original content preserved
                        .contains("# Project Documentation")
                        .contains("This is my project.")
                        .actual())
            )
        );
    }

    @Test
    void addsContextSectionToCopilotInstructions() {
        rewriteRun(
            spec -> spec.recipe(new UpdateAgentConfig(".github/copilot-instructions.md")),
            // Context markdown file (scanner needs to find it to extract metadata)
            text(
                //language=Markdown
                """
                  # Test Coverage
                  
                  ## Maps test methods to implementation methods they verify
                  
                  This context maps each test method to the implementation methods it calls.
                  
                  ## Data Tables
                  
                  ### Test Mapping
                  
                  **File:** [`test-mapping.csv`](test-mapping.csv)
                  """,
                spec -> spec.path(".moderne/context/test-coverage.md")
            ),
            // CLAUDE.md file to be updated
            text(
                //language=Markdown
                """
                  # Project Documentation
                  
                  This is my project.
                  """,
                spec -> spec.path(".github/copilot-instructions.md").after(after ->
                    assertThat(after)
                        // Check section markers
                        .contains("<!-- prethink-context -->")
                        .contains("<!-- /prethink-context -->")
                        // Check section content
                        .contains("## Moderne Prethink Context")
                        .contains("Moderne Prethink")
                        // Check context table
                        .contains("| Test Coverage |")
                        .contains("test-coverage.md")
                        // Check for template content (agent instructions)
                        .contains("IMPORTANT: Before exploring source code")
                        .contains("### Usage Pattern")
                        // Verify original content preserved
                        .contains("# Project Documentation")
                        .contains("This is my project.")
                        .actual())
            )
        );
    }

    @Test
    void updatesMultipleContextFiles() {
        rewriteRun(
            // Multiple context markdown files
            text(
                //language=Markdown
                """
                # Code Comprehension

                ## AI-generated descriptions for classes and methods

                This context provides descriptions for code.
                """,
                spec -> spec.path(".moderne/context/code-comprehension.md")
            ),
            text(
                //language=Markdown
                """
                # Test Coverage

                ## Maps test methods to implementation methods

                This context maps tests to implementations.
                """,
                spec -> spec.path(".moderne/context/test-coverage.md")
            ),
            // CLAUDE.md file to be updated
            text(
                //language=Markdown
                """
                # Project Documentation

                This is my project.
                """,
                spec -> spec.path("CLAUDE.md").after(after ->
                    assertThat(after)
                        // Check both context entries appear in table
                        .contains("| Code Comprehension |")
                        .contains("| Test Coverage |")
                        .contains("code-comprehension.md")
                        .contains("test-coverage.md")
                        // Check section structure
                        .contains("<!-- prethink-context -->")
                        .contains("<!-- /prethink-context -->")
                        .actual())
            )
        );
    }

    @Test
    void replacesExistingContextSection() {
        rewriteRun(
            // New context file
            text(
                //language=Markdown
                """
                # Test Coverage

                ## Maps tests to implementations

                This context maps tests.
                """,
                spec -> spec.path(".moderne/context/test-coverage.md")
            ),
            // CLAUDE.md with existing context section
            text(
                //language=Markdown
                """
                # Project Documentation

                <!-- prethink-context -->
                ## Moderne Prethink Context

                This repository has pre-generated context files from [Moderne Prethink](https://www.moderne.io).
                Use these files to understand the codebase structure and implementation details.

                | Context | Description | Details |
                |---------|-------------|--------|
                | Old Context | Old description | [`old-context.md`](.moderne/context/old-context.md) |

                <!-- /prethink-context -->

                ## Other Section
                """,
                spec -> spec.path("CLAUDE.md").after(after ->
                    assertThat(after)
                        // New context should be present
                        .contains("| Test Coverage |")
                        .contains("test-coverage.md")
                        // Old context should be gone
                        .doesNotContain("Old Context")
                        .doesNotContain("old-context.md")
                        // Other section should still be there
                        .contains("## Other Section")
                        // Verify section structure
                        .contains("<!-- prethink-context -->")
                        .contains("<!-- /prethink-context -->")
                        .actual())
            )
        );
    }

    @Test
    void createsClaudeMdIfNoConfigExists() {
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1)
                .afterRecipe(run -> {
                    boolean hasClaudeMd = run.getChangeset().getAllResults().stream()
                            .anyMatch(r -> r.getAfter() != null &&
                                    r.getAfter().getSourcePath().toString().equals("CLAUDE.md"));
                    assertThat(hasClaudeMd).isTrue();
                }),
            // Only context markdown file exists, no config file
            text(
                //language=Markdown
                """
                # Test Coverage

                ## Maps tests to implementations

                This context maps tests.
                """,
                spec -> spec.path(".moderne/context/test-coverage.md")
            )
        );
    }

    @Test
    void noChangesWhenNoContextFiles() {
        rewriteRun(
            // CLAUDE.md exists but no context markdown files
            text(
                //language=Markdown
                """
                # Project Documentation

                This is my project.
                """,
                spec -> spec.path("CLAUDE.md")
            )
        );
    }

    @Test
    void ignoresCsvFilesWithoutMarkdown() {
        // CSV files alone should not trigger updates - only markdown description files
        rewriteRun(
            // CSV file without corresponding markdown
            text(
                //language=csv
                "header\ndata",
                spec -> spec.path(".moderne/context/test-mapping.csv")
            ),
            // CLAUDE.md should not be modified
            text(
                //language=Markdown
                """
                # Project Documentation

                This is my project.
                """,
                spec -> spec.path("CLAUDE.md")
            )
        );
    }
}
