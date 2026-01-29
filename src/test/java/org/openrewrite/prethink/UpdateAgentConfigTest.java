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
            spec -> spec.expectedCyclesThatMakeChanges(1),
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
                spec -> spec.path("CLAUDE.md").after(after -> {
                    // Check section markers
                    assertThat(after).contains("<!-- prethink-context -->");
                    assertThat(after).contains("<!-- /prethink-context -->");

                    // Check section content
                    assertThat(after).contains("## Moderne Prethink Context");
                    assertThat(after).contains("Moderne Prethink");

                    // Check context table
                    assertThat(after).contains("| Test Coverage |");
                    assertThat(after).contains("test-coverage.md");

                    // Check for template content (agent instructions)
                    assertThat(after).contains("IMPORTANT: Before exploring source code");
                    assertThat(after).contains("### Usage Pattern");

                    // Verify original content preserved
                    assertThat(after).contains("# Project Documentation");
                    assertThat(after).contains("This is my project.");
                    return after;
                })
            )
        );
    }

    @Test
    void updatesMultipleContextFiles() {
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1),
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
                spec -> spec.path("CLAUDE.md").after(after -> {
                    // Check both context entries appear in table
                    assertThat(after).contains("| Code Comprehension |");
                    assertThat(after).contains("| Test Coverage |");
                    assertThat(after).contains("code-comprehension.md");
                    assertThat(after).contains("test-coverage.md");

                    // Check section structure
                    assertThat(after).contains("<!-- prethink-context -->");
                    assertThat(after).contains("<!-- /prethink-context -->");
                    return after;
                })
            )
        );
    }

    @Test
    void replacesExistingContextSection() {
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1),
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
                spec -> spec.path("CLAUDE.md").after(after -> {
                    // New context should be present
                    assertThat(after).contains("| Test Coverage |");
                    assertThat(after).contains("test-coverage.md");

                    // Old context should be gone
                    assertThat(after).doesNotContain("Old Context");
                    assertThat(after).doesNotContain("old-context.md");

                    // Other section should still be there
                    assertThat(after).contains("## Other Section");

                    // Verify section structure
                    assertThat(after).contains("<!-- prethink-context -->");
                    assertThat(after).contains("<!-- /prethink-context -->");
                    return after;
                })
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
