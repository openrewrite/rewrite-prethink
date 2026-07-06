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

import lombok.Getter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.*;
import org.openrewrite.config.CompositeRecipe;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.prethink.table.TestMapping;
import org.openrewrite.text.PlainText;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RemoveStaleContextFilesTest {

    /** Populates a grouped TestMapping so {@link ExportContext} has rows to export. */
    @Getter
    public static class PopulateTestMapping extends Recipe {
        transient TestMapping testMapping = new TestMapping(this).withGroup("architecture");

        String displayName = "Populate test mapping";
        String description = "Populates a TestMapping data table.";

        @Override
        public TreeVisitor<?, ExecutionContext> getVisitor() {
            return new TreeVisitor<>() {
                @Override
                public Tree visit(Tree tree, ExecutionContext ctx) {
                    if (tree instanceof SourceFile sf &&
                        sf.getSourcePath().toString().endsWith("FooTest.java")) {
                        testMapping.insertRow(ctx, new TestMapping.Row(
                          "src/test/java/FooTest.java",
                          "com.example.FooTest",
                          "testFoo()",
                          "src/main/java/Foo.java",
                          "com.example.Foo",
                          "foo()",
                          null,
                          null
                        ));
                    }
                    return tree;
                }
            };
        }
    }

    private static Set<String> deletedPaths(RecipeRun run) {
        return run.getChangeset().getAllResults().stream()
          .filter(r -> r.getAfter() == null && r.getBefore() != null)
          .map(r -> r.getBefore().getSourcePath().toString())
          .collect(Collectors.toSet());
    }

    @Test
    @Issue("https://github.com/moderneinc/customer-requests/issues/2261")
    void deletesOrphanCsvAndMarkdownButKeepsLiveAndNonPairContext(@TempDir Path dataTablesDir) {
        ExecutionContext ctx = new InMemoryExecutionContext();
        DataTableExecutionContextView.view(ctx)
          .setDataTableStore(new CsvDataTableStore(dataTablesDir));

        Recipe composite = new CompositeRecipe(List.of(
          new PopulateTestMapping(),
          new ExportContext(
            "Test Coverage",
            "Maps tests to implementations",
            "Detailed description of test coverage context",
            List.of("org.openrewrite.prethink.table.TestMapping")
          ),
          new RemoveStaleContextFiles()
        ));

        // Only orphans from an earlier recipe version are pre-seeded; the live context is generated fresh.
        InMemoryLargeSourceSet sources = new InMemoryLargeSourceSet(List.of(
          plainText("src/test/java/FooTest.java", "package com.example;\npublic class FooTest {}"),
          // Orphan CSV (owning table removed).
          plainText(".moderne/context/method-descriptions.csv",
            "Source path,Signature,Inference time (ms),Input tokens,Output tokens\nold,old,1,2,3"),
          // Orphan markdown (context restructured).
          plainText(".moderne/context/messaging-connections.md",
            "# Messaging Connections\n\n## Kafka/async event flows\n\nConnection class, Messaging type, Topic/Queue name, Role, Framework"),
          // Not a CSV/markdown pair — must be kept.
          plainText(".moderne/context/calm-architecture.json", "{\"nodes\":[]}")
        ));

        RecipeRun run = composite.run(sources, ctx, 3, 1);

        Set<String> deleted = deletedPaths(run);
        assertThat(deleted)
          .as("orphaned CSV and markdown files must be removed")
          .contains(
            ".moderne/context/method-descriptions.csv",
            ".moderne/context/messaging-connections.md");
        assertThat(deleted)
          .as("live context files and non-pair context files must be preserved")
          .doesNotContain(
            ".moderne/context/test-mapping.csv",
            ".moderne/context/test-coverage.md",
            ".moderne/context/calm-architecture.json");

        // Live CSV is refreshed with real rows.
        SourceFile testMappingCsv = run.getChangeset().getAllResults().stream()
          .map(Result::getAfter)
          .filter(Objects::nonNull)
          .filter(sf -> sf.getSourcePath().toString().equals(".moderne/context/test-mapping.csv"))
          .findFirst()
          .orElse(null);
        assertThat(testMappingCsv)
          .as("the live test-mapping.csv should be refreshed with real rows")
          .isNotNull();
        assertThat(((PlainText) testMappingCsv).getText())
          .contains("com.example.FooTest")
          .contains("testFoo()")
          .doesNotContain("stale");
    }

    /** With no {@link ExportContext} to define the kept set, the recipe deletes nothing. */
    @Test
    void keepsEverythingWhenNoExportContextHasRun() {
        InMemoryLargeSourceSet sources = new InMemoryLargeSourceSet(List.of(
          plainText(".moderne/context/method-descriptions.csv", "Source path,Signature\nold,old"),
          plainText(".moderne/context/messaging-connections.md", "# Messaging Connections\n")
        ));

        RecipeRun run = new RemoveStaleContextFiles().run(sources, new InMemoryExecutionContext(), 3, 1);

        assertThat(deletedPaths(run))
          .as("with no ExportContext to define the kept set, delete nothing")
          .isEmpty();
    }

    private static SourceFile plainText(String path, String text) {
        return PlainText.builder()
          .sourcePath(Path.of(path))
          .text(text)
          .build();
    }
}
