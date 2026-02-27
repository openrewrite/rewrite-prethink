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

public class SourceSetLanguageSummary extends DataTable<SourceSetLanguageSummary.Row> {

    public SourceSetLanguageSummary(Recipe recipe) {
        super(recipe, "Source set language summary",
                "Per-source-set summary of programming languages, compiler versions, file counts, " +
                "and build tools. Each row represents one language in one source set of one module " +
                "(e.g., Java 17 in the main source set of the order-api module). Use this to understand " +
                "what language versions a project targets, which build tools are in use, and how source " +
                "files are distributed across modules.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source set ID",
                description = "Composite key identifying the source set (e.g., order-api:main:Java).")
        String sourceSetId;

        @Column(displayName = "Module path",
                description = "Relative path to the module (e.g., services/order-api).")
        String modulePath;

        @Column(displayName = "Source set name",
                description = "The source set name (e.g., main, test, integrationTest, testFixtures).")
        String sourceSetName;

        @Column(displayName = "Build tool",
                description = "The build tool used (e.g., Maven, Gradle, GradleKotlin, Npm, Dotnet).")
        String buildTool;

        @Column(displayName = "Language",
                description = "The programming language (e.g., Java, Kotlin, Groovy, Scala, TypeScript, CSharp).")
        String language;

        @Column(displayName = "File count",
                description = "The number of source files in this source set.")
        int fileCount;

        @Column(displayName = "Source version",
                description = "The language source version — what language features/syntax can be used.")
        @Nullable
        String sourceVersion;

        @Column(displayName = "Target version",
                description = "The language target version — what runtime is targeted.")
        @Nullable
        String targetVersion;
    }
}
