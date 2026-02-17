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

class UpdateGitignoreTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UpdateGitignore());
    }

    @DocumentExample
    @Test
    void replacesModerneDirectoryPattern() {
        rewriteRun(
            text(
                """
                # Build
                build/

                # Moderne
                .moderne/

                # IDE
                .idea/
                """,
                """
                # Build
                build/

                # Moderne
                .moderne/*
                !.moderne/context/

                # IDE
                .idea/
                """,
                spec -> spec.path(".gitignore")
            )
        );
    }

    @Test
    void addsPatternWhenNoModerneEntry() {
        rewriteRun(
            text(
                """
                # Build
                build/

                # IDE
                .idea/
                """,
                """
                # Build
                build/

                # IDE
                .idea/

                # Moderne CLI
                .moderne/*
                !.moderne/context/
                """,
                spec -> spec.path(".gitignore")
            )
        );
    }

    @Test
    void addsExceptionWhenWildcardExistsWithoutException() {
        rewriteRun(
            text(
                """
                # Build
                build/

                # Moderne
                .moderne/*
                """,
                """
                # Build
                build/

                # Moderne
                .moderne/*
                !.moderne/context/
                """,
                spec -> spec.path(".gitignore")
            )
        );
    }

    @Test
    void noChangeWhenAlreadyCorrect() {
        rewriteRun(
            text(
                """
                # Build
                build/

                # Moderne
                .moderne/*
                !.moderne/context/
                """,
                spec -> spec.path(".gitignore")
            )
        );
    }

    @Test
    void handlesModerneWithoutTrailingSlash() {
        rewriteRun(
            text(
                "# Moderne\n.moderne\n",
                "# Moderne\n.moderne/*\n!.moderne/context/\n",
                spec -> spec.path(".gitignore")
            )
        );
    }

    @Test
    void doesNotModifyOtherGitignores() {
        rewriteRun(
            text(
                """
                # Build
                build/
                .moderne/
                """,
                spec -> spec.path("subdir/.gitignore")
            )
        );
    }

    @Test
    void updateGitignoreContentUnit() {
        // Test the static method directly
        String input = "build/\n.moderne/\n.idea/\n";
        String expected = "build/\n.moderne/*\n!.moderne/context/\n.idea/\n";
        assertThat(UpdateGitignore.updateGitignoreContent(input)).isEqualTo(expected);
    }

    @Test
    void updateGitignoreContentAlreadyCorrect() {
        String input = "build/\n.moderne/*\n!.moderne/context/\n";
        assertThat(UpdateGitignore.updateGitignoreContent(input)).isEqualTo(input);
    }

    @Test
    void updateGitignoreContentNoModerne() {
        String input = "build/\n.idea/\n";
        String result = UpdateGitignore.updateGitignoreContent(input);
        assertThat(result)
                .contains(".moderne/*")
                .contains("!.moderne/context/")
                .contains("# Moderne CLI");
    }

    @Test
    void updateGitignoreContentModerneWithoutSlash() {
        String input = "# Moderne\n.moderne\n";
        String result = UpdateGitignore.updateGitignoreContent(input);
        assertThat(result).isEqualTo("# Moderne\n.moderne/*\n!.moderne/context/\n");
    }

}
