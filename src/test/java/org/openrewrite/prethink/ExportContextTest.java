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
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.text.PlainText;

import java.nio.file.Path;
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

    /**
     * A fake recipe that populates TestMapping rows from one "recipe".
     */
    @Getter
    public static class PopulateTestMappingA extends Recipe {
        transient TestMapping testMapping = new TestMapping(this);

        String displayName = "Populate test mapping A";
        String description = "Populates TestMapping data table with rows from recipe A.";

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

    /**
     * A second fake recipe that populates its own TestMapping instance with different rows.
     */
    @Getter
    public static class PopulateTestMappingB extends Recipe {
        transient TestMapping testMapping = new TestMapping(this);

        String displayName = "Populate test mapping B";
        String description = "Populates TestMapping data table with rows from recipe B.";

        @Override
        public TreeVisitor<?, ExecutionContext> getVisitor() {
            return new TreeVisitor<>() {
                @Override
                public Tree visit(Tree tree, ExecutionContext ctx) {
                    if (tree instanceof SourceFile sf &&
                      sf.getSourcePath().toString().endsWith("BarTest.java")) {
                        testMapping.insertRow(ctx, new TestMapping.Row(
                          "src/test/java/BarTest.java",
                          "com.example.BarTest",
                          "testBar()",
                          "src/main/java/Bar.java",
                          "com.example.Bar",
                          "bar()",
                          null,
                          null
                        ));
                    }
                    return tree;
                }
            };
        }
    }

    @Test
    void requestsAnotherCycleWhenNoOtherRecipeMakesChangesInCycle1() {
        // Regression for the multi-repo bug: when no other recipe in the pipeline
        // makes a tree-modifying change in cycle 1, RecipeScheduler.runRecipeCycles
        // terminates the loop after cycle 1 (since `madeChangesInThisCycle` is empty
        // and `i >= minCycles`). ExportContext.generate() defers all work to cycle 2,
        // so without an explicit cycle-trigger it would never produce any files.
        //
        // This test mirrors the production CLI scheduler call (RunTask.java passes
        // maxCycles=3, minCycles=1) by invoking Recipe.run directly. RewriteTest's
        // rewriteRun cannot represent this because it strictly enforces
        // `expectedCyclesThatMakeChanges`, and the only way to set minCycles=1
        // through that API also asserts zero cycles make changes -- contradicting
        // the success criterion.
        //
        // PopulateTestMappingA inserts a data-table row but returns the tree
        // unchanged, contributing no tree-level changes. Without the cycle trigger
        // in ExportContext.getVisitor(), cycle 1 ends with `madeChangesInThisCycle`
        // empty, the loop breaks, and ExportContext.generate() never runs.

        Recipe pipeline = new Recipe() {
            @Override
            public String getDisplayName() {
                return "Pipeline: producer + ExportContext";
            }

            @Override
            public String getDescription() {
                return "Test pipeline.";
            }

            @Override
            public List<Recipe> getRecipeList() {
                return List.of(
                  new PopulateTestMappingA(),
                  new ExportContext(
                    "Test Coverage",
                    "Maps tests to implementations",
                    "Detailed description of test coverage context",
                    List.of("org.openrewrite.prethink.table.TestMapping")
                  )
                );
            }
        };

        SourceFile fooTest = PlainText.builder()
          .text("package com.example;\npublic class FooTest {}")
          .sourcePath(java.nio.file.Paths.get("src/test/java/FooTest.java"))
          .build();
        InMemoryLargeSourceSet lss = new InMemoryLargeSourceSet(List.of(fooTest));

        // Match production: maxCycles=3, minCycles=1.
        RecipeRun run = pipeline.run(lss, new InMemoryExecutionContext(), 3, 1);

        List<String> generatedPaths = run.getChangeset().getAllResults().stream()
          .filter(r -> r.getAfter() != null)
          .map(r -> r.getAfter().getSourcePath().toString())
          .collect(java.util.stream.Collectors.toList());

        assertThat(generatedPaths)
          .as("generate() must produce context files even when no other recipe makes a tree-modifying change in cycle 1")
          .contains(".moderne/context/test-mapping.csv", ".moderne/context/test-coverage.md");

        SourceFile csv = run.getChangeset().getAllResults().stream()
          .filter(r -> r.getAfter() != null && ".moderne/context/test-mapping.csv".equals(r.getAfter().getSourcePath().toString()))
          .findFirst().map(org.openrewrite.Result::getAfter).orElseThrow();
        assertThat(((PlainText) csv).getText()).contains("com.example.FooTest").contains("testFoo()");
    }

    /**
     * Mirrors the production discovery recipes (e.g. FindProjectMetadata,
     * FindServiceEndpoints) that put their data table into a shared community
     * {@code group}. The group changes the {@link DataTableStore} bucket key,
     * which is the dimension that distinguishes the failing production path
     * (a {@link CsvDataTableStore}) from the in-memory test path.
     */
    @Getter
    public static class PopulateGroupedTestMapping extends Recipe {
        transient TestMapping testMapping = new TestMapping(this).withGroup("architecture");

        String displayName = "Populate grouped test mapping";
        String description = "Populates a grouped TestMapping data table.";

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

    /**
     * Reproduces the production failure: a composite where a data table is
     * populated in cycle 1 and {@link ExportContext} must export it, run against
     * a {@link CsvDataTableStore} (the Moderne CLI's {@code mod run} store) with
     * the CLI's cycle semantics ({@code maxCycles=3, minCycles=1}).
     * <p>
     * The default RewriteTest harness hides this bug because it inflates
     * {@code minCycles} to {@code expectedCyclesThatMakeChanges + 1}, forcing a
     * second cycle the production run never gets unless a recipe makes a file
     * change in cycle 1. There is deliberately <em>no</em> external cycle trigger
     * here: with the fix, {@link ExportContext} generates a placeholder in cycle 1
     * (a real change), which both triggers cycle 2 and gives it a file to fill —
     * the only file-producing pattern the V3 edit overlay carries through.
     */
    @Test
    void exportsFromCsvDataTableStoreWithProductionCycleSemantics(@TempDir Path dataTablesDir) {
        ExecutionContext ctx = new InMemoryExecutionContext();
        DataTableExecutionContextView.view(ctx)
          .setDataTableStore(new CsvDataTableStore(dataTablesDir));

        Recipe composite = new CompositeRecipe(List.of(
          new PopulateGroupedTestMapping(),
          new ExportContext(
            "Test Coverage",
            "Maps tests to implementations",
            "Detailed description of test coverage context",
            List.of("org.openrewrite.prethink.table.TestMapping")
          )
        ));

        // Drive the run exactly as the Moderne CLI does: maxCycles=3, minCycles=1.
        InMemoryLargeSourceSet sources = new InMemoryLargeSourceSet(List.of(
          PlainText.builder()
            .sourcePath(Path.of("src/test/java/FooTest.java"))
            .text("package com.example;\npublic class FooTest {}")
            .build()
        ));
        RecipeRun run = composite.run(sources, ctx, 3, 1);

        SourceFile generated = run.getChangeset().getAllResults().stream()
          .map(Result::getAfter)
          .filter(java.util.Objects::nonNull)
          .filter(sf -> sf.getSourcePath().equals(Path.of(".moderne/context/test-mapping.csv")))
          .findFirst()
          .orElse(null);

        assertThat(generated)
          .as("ExportContext should generate test-mapping.csv from the CsvDataTableStore-backed run")
          .isNotNull();
        assertThat(generated.printAll())
          .contains("com.example.FooTest")
          .contains("testFoo()");
    }

    /**
     * Populates the CALM-architecture data tables (grouped {@code "architecture"})
     * the same way the production discovery recipes do, so that the real
     * {@link UpdatePrethinkContext} composite has data to both generate the CALM
     * JSON and export the architecture CSVs from.
     */
    @Getter
    public static class PopulateArchitectureTables extends Recipe {
        transient org.openrewrite.prethink.table.ServiceEndpoints serviceEndpoints =
          new org.openrewrite.prethink.table.ServiceEndpoints(this).withGroup("architecture");
        transient org.openrewrite.prethink.table.ProjectMetadata projectMetadata =
          new org.openrewrite.prethink.table.ProjectMetadata(this).withGroup("architecture");

        String displayName = "Populate architecture tables";
        String description = "Populates ServiceEndpoints and ProjectMetadata grouped data tables.";

        @Override
        public TreeVisitor<?, ExecutionContext> getVisitor() {
            return new TreeVisitor<>() {
                @Override
                public Tree visit(Tree tree, ExecutionContext ctx) {
                    if (tree instanceof SourceFile sf &&
                      sf.getSourcePath().toString().endsWith("UserController.java")) {
                        projectMetadata.insertRow(ctx, new org.openrewrite.prethink.table.ProjectMetadata.Row(
                          "pom.xml", "demo-app", "com.example", "Demo", "Demo app", "1.0.0", null, null));
                        serviceEndpoints.insertRow(ctx, new org.openrewrite.prethink.table.ServiceEndpoints.Row(
                          "endpoint:com.example.UserController#listUsers()",
                          "src/main/java/com/example/UserController.java",
                          "com.example.UserController",
                          "listUsers",
                          "GET",
                          "/api/users",
                          "application/json",
                          "",
                          "Spring",
                          "listUsers()"));
                    }
                    return tree;
                }
            };
        }
    }

    /**
     * The end-to-end reproduction of the production bug: running the real
     * {@link UpdatePrethinkContext} composite (which both generates the CALM
     * JSON via {@code GenerateCalmArchitecture} and exports architecture CSVs via
     * {@link ExportContext}) against a {@link CsvDataTableStore} with the Moderne
     * CLI's cycle semantics ({@code maxCycles=3, minCycles=1}).
     * <p>
     * Before the fix, only {@code calm-architecture.json} was generated; the
     * architecture CSVs (e.g. {@code service-endpoints.csv}) were silently
     * dropped because {@link ExportContext} generated them one cycle later than
     * {@code GenerateCalmArchitecture}.
     */
    @Test
    void realCompositeExportsArchitectureCsvAlongsideCalmJson(@TempDir Path dataTablesDir) {
        ExecutionContext ctx = new InMemoryExecutionContext();
        DataTableExecutionContextView.view(ctx)
          .setDataTableStore(new CsvDataTableStore(dataTablesDir));

        Recipe composite = new CompositeRecipe(List.of(
          new PopulateArchitectureTables(),
          new UpdatePrethinkContext(null)
        ));

        InMemoryLargeSourceSet sources = new InMemoryLargeSourceSet(List.of(
          PlainText.builder()
            .sourcePath(Path.of("src/main/java/com/example/UserController.java"))
            .text("package com.example;\npublic class UserController {}")
            .build()
        ));
        RecipeRun run = composite.run(sources, ctx, 3, 1);

        java.util.Map<Path, SourceFile> generated = new java.util.HashMap<>();
        for (Result result : run.getChangeset().getAllResults()) {
            if (result.getAfter() != null) {
                generated.put(result.getAfter().getSourcePath(), result.getAfter());
            }
        }

        // CALM JSON is generated today (the working baseline)
        assertThat(generated).containsKey(Path.of(".moderne/context/calm-architecture.json"));

        // The architecture CSV must also be generated (this is the bug)
        SourceFile csv = generated.get(Path.of(".moderne/context/service-endpoints.csv"));
        assertThat(csv)
          .as("ExportContext should generate service-endpoints.csv alongside the CALM JSON")
          .isNotNull();
        assertThat(csv.printAll()).contains("com.example.UserController");
    }

    @Test
    void aggregatesRowsFromMultipleInstancesOfSameDataTable() {
        rewriteRun(
          spec -> spec
            .recipes(
              new PopulateTestMappingA(),
              new PopulateTestMappingB(),
              new ExportContext(
                "Test Coverage",
                "Maps tests to implementations",
                "Detailed description of test coverage context",
                List.of("org.openrewrite.prethink.table.TestMapping")
              )
            )
            // ExportContext now generates placeholder context files in cycle 1
            // and fills them with data-table content in cycle 2.
            .cycles(4)
            .expectedCyclesThatMakeChanges(3),
          // Source files that trigger the two fake recipes
          text(
            "package com.example;\npublic class FooTest {}",
            spec -> spec.path("src/test/java/FooTest.java")
          ),
          text(
            "package com.example;\npublic class BarTest {}",
            spec -> spec.path("src/test/java/BarTest.java")
          ),
          // Expect the aggregated CSV to be generated with rows from both recipes
          text(
            doesNotExist(),
            spec -> spec
              .path(".moderne/context/test-mapping.csv")
              .after(csv -> {
                  assertThat(csv)
                    .contains("com.example.FooTest")
                    .contains("testFoo()")
                    .contains("com.example.BarTest")
                    .contains("testBar()");
                  return csv;
              })
          ),
          // Expect the markdown description file
          text(
            doesNotExist(),
            spec -> spec
              .path(".moderne/context/test-coverage.md")
              .after(md -> {
                  assertThat(md)
                    .contains("# Test Coverage")
                    .contains("Maps tests to implementations")
                    .contains("test-mapping.csv");
                  return md;
              })
          )
        );
    }
}
