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
package org.openrewrite.prethink.calm;

import org.junit.jupiter.api.Test;
import org.openrewrite.*;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.prethink.table.DatabaseConnections;
import org.openrewrite.prethink.table.ServiceEndpoints;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.text.PlainText;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.test.SourceSpecs.text;

class GenerateCalmArchitectureTest implements RewriteTest {

    /**
     * A fake recipe that just populates the ServiceEndpoints data table.
     */
    public static class PopulateServiceEndpoints extends Recipe {
        transient ServiceEndpoints serviceEndpoints = new ServiceEndpoints(this);

        @Override
        public String getDisplayName() {
            return "Populate service endpoints";
        }

        @Override
        public String getDescription() {
            return "Populates ServiceEndpoints data table for testing.";
        }

        @Override
        public TreeVisitor<?, ExecutionContext> getVisitor() {
            return new TreeVisitor<Tree, ExecutionContext>() {
                @Override
                public Tree visit(Tree tree, ExecutionContext ctx) {
                    if (tree instanceof SourceFile sf &&
                      sf.getSourcePath().toString().endsWith("GreetingController.java")) {
                        // Simulate finding an endpoint
                        serviceEndpoints.insertRow(ctx, new ServiceEndpoints.Row(
                          "endpoint:com.example.GreetingController#greeting(String)",
                          sf.getSourcePath().toString(),
                          "com.example.GreetingController",
                          "greeting",
                          "GET",
                          "/greeting",
                          null,
                          null,
                          "Spring",
                          "com.example.GreetingController{name=greeting,return=String,parameters=[java.lang.String]}"
                        ));
                    }
                    return tree;
                }
            };
        }
    }

    @Test
    void generatesNothingWithNoDataTables() {
        // With no data tables populated, generate() returns empty in both cycles
        // and cycle2Visit has nothing to update -- so the recipe contributes no
        // tree-level changes at all.
        rewriteRun(
          spec -> spec
            .recipe(new GenerateCalmArchitecture())
            .cycles(2)
            .expectedCyclesThatMakeChanges(0),
          text(
            "test content",
            spec -> spec.path("README.md")
          )
        );
    }

    @Test
    void generatesCalmWhenServiceEndpointsPopulated() {
        // PopulateServiceEndpoints populates DATA_TABLES during cycle 1's edit phase. The
        // PrethinkContextRecipe base class triggers cycle 2 from cycle 1's visitor. In cycle 2,
        // GenerateCalmArchitecture.generate() creates calm-architecture.json from the populated
        // data tables.
        rewriteRun(
          spec -> spec
            .recipes(new PopulateServiceEndpoints(), new GenerateCalmArchitecture())
            .cycles(3)
            .expectedCyclesThatMakeChanges(1),
          text(
            "package com.example;\npublic class GreetingController {}",
            spec -> spec.path("src/main/java/com/example/GreetingController.java")
          ),
          text(
            null, // Generated file - expect any non-null content
            """
              {
                "nodes" : [ {
                  "unique-id" : "greeting-controller",
                  "node-type" : "service",
                  "name" : "GreetingController",
                  "description" : "REST API with endpoints: GET /greeting",
                  "interfaces" : [ {
                    "unique-id" : "greeting-controller-api",
                    "port" : 8080
                  } ]
                } ],
                "relationships" : [ ],
                "$schema" : "https://calm.finos.org/draft/2025-03/meta/calm.json"
              }
              """,
            spec -> spec.path(".moderne/context/calm-architecture.json")
          )
        );
    }

    @Test
    void updatesExistingCalmFileEvenWhenNoOtherRecipeMakesChangesInCycle1() {
        // Regression: when calm-architecture.json is already present in the LST (the post-
        // moderne-cli#3661 reality, where the build-time LST manifest now includes
        // .moderne/context/* files), the cycle-1 placeholder workaround in generate() is
        // skipped because the path is already in the accumulator. The visitor only runs in
        // cycle 2. If no other recipe in the pipeline makes a change in cycle 1, the
        // RecipeScheduler loop breaks after cycle 1 and the existing CALM file never gets
        // updated.
        //
        // This test mirrors the production CLI scheduler call (RunTask.java passes
        // maxCycles=3, minCycles=1) by invoking Recipe.run directly. PopulateServiceEndpoints
        // inserts a data-table row but returns the tree unchanged, contributing no
        // tree-level changes. Without the cycle-1 trigger added in getVisitor(), the
        // existing calm-architecture.json would not be updated.

        Recipe pipeline = new Recipe() {
            @Override
            public String getDisplayName() {
                return "Pipeline: producer + GenerateCalmArchitecture";
            }

            @Override
            public String getDescription() {
                return "Test pipeline.";
            }

            @Override
            public List<Recipe> getRecipeList() {
                return List.of(
                  new PopulateServiceEndpoints(),
                  new GenerateCalmArchitecture()
                );
            }
        };

        SourceFile controller = PlainText.builder()
          .text("package com.example;\npublic class GreetingController {}")
          .sourcePath(java.nio.file.Paths.get("src/main/java/com/example/GreetingController.java"))
          .build();
        SourceFile staleCalm = PlainText.builder()
          .text("{\"stale\": true}")
          .sourcePath(java.nio.file.Paths.get(".moderne/context/calm-architecture.json"))
          .build();
        InMemoryLargeSourceSet lss = new InMemoryLargeSourceSet(List.of(controller, staleCalm));

        // Match production: maxCycles=3, minCycles=1.
        RecipeRun run = pipeline.run(lss, new InMemoryExecutionContext(), 3, 1);

        SourceFile updated = run.getChangeset().getAllResults().stream()
          .filter(r -> r.getAfter() != null && ".moderne/context/calm-architecture.json".equals(r.getAfter().getSourcePath().toString()))
          .findFirst().map(org.openrewrite.Result::getAfter).orElse(null);

        assertThat(updated)
          .as("cycle 2 must run so the existing calm-architecture.json gets updated from data tables")
          .isNotNull();
        assertThat(((PlainText) updated).getText())
          .as("the stale content should have been replaced with real CALM JSON")
          .doesNotContain("\"stale\": true")
          .contains("greeting-controller");
    }

    @Test
    void debugDataTablesAccess() {
        // Test to debug how DATA_TABLES is accessed - same as above but simpler assertion
        // Cycle 1: placeholder created, DATA_TABLES populated by visitor
        // Cycle 2: placeholder updated with real CALM content
        rewriteRun(
          spec -> spec
            .recipes(new PopulateServiceEndpoints(), new GenerateCalmArchitecture())
            .cycles(3)
            .expectedCyclesThatMakeChanges(1),
          text(
            "package com.example;\npublic class GreetingController {}",
            spec -> spec.path("src/main/java/com/example/GreetingController.java")
          ),
          text(
            null,
            spec -> spec
              .path(".moderne/context/calm-architecture.json")
              // Verify CALM file was created with expected content
              .after(content ->
                assertThat(content)
                  .contains("greeting-controller")
                  .contains("GET /greeting")
                  .contains("unique-id")
                  .contains("node-type")
                  .doesNotContain("uniqueId")
                  .doesNotContain("nodeType")
                  .actual())
          )
        );
    }

    /**
     * A fake recipe that populates DatabaseConnections data table.
     */
    public static class PopulateDatabaseConnections extends Recipe {
        transient DatabaseConnections databaseConnections = new DatabaseConnections(this);

        @Override
        public String getDisplayName() {
            return "Populate database connections";
        }

        @Override
        public String getDescription() {
            return "Populates DatabaseConnections data table for testing.";
        }

        @Override
        public TreeVisitor<?, ExecutionContext> getVisitor() {
            return new TreeVisitor<Tree, ExecutionContext>() {
                @Override
                public Tree visit(Tree tree, ExecutionContext ctx) {
                    if (tree instanceof SourceFile sf) {
                        if (sf.getSourcePath().toString().endsWith("OrderRepository.java")) {
                            databaseConnections.insertRow(ctx, new DatabaseConnections.Row(
                              "repository:com.example.order.repository.OrderRepository",
                              sf.getSourcePath().toString(),
                              "Order",
                              "com.example.order.model.Order",
                              "com.example.order.repository.OrderRepository",
                              "Spring Data",
                              "SQL"
                            ));
                        }
                    }
                    return tree;
                }
            };
        }
    }

    /**
     * A variant of PopulateServiceEndpoints that uses sibling packages.
     */
    public static class PopulateServiceEndpointsInControllerPackage extends Recipe {
        transient ServiceEndpoints serviceEndpoints = new ServiceEndpoints(this);

        @Override
        public String getDisplayName() {
            return "Populate service endpoints in controller package";
        }

        @Override
        public String getDescription() {
            return "Populates ServiceEndpoints in a controller sub-package for sibling package testing.";
        }

        @Override
        public TreeVisitor<?, ExecutionContext> getVisitor() {
            return new TreeVisitor<Tree, ExecutionContext>() {
                @Override
                public Tree visit(Tree tree, ExecutionContext ctx) {
                    if (tree instanceof SourceFile sf) {
                        if (sf.getSourcePath().toString().endsWith("OrderController.java")) {
                            serviceEndpoints.insertRow(ctx, new ServiceEndpoints.Row(
                              "endpoint:com.example.order.controller.OrderController#createOrder(OrderDto)",
                              sf.getSourcePath().toString(),
                              "com.example.order.controller.OrderController",
                              "createOrder",
                              "POST",
                              "/api/orders",
                              null,
                              null,
                              "Spring",
                              "com.example.order.controller.OrderController{name=createOrder,return=Order,parameters=[OrderDto]}"
                            ));
                        }
                    }
                    return tree;
                }
            };
        }
    }

    @Test
    void connectsDatabaseToServiceViaSiblingPackage() {
        // Controller in com.example.order.controller, Repository in com.example.order.repository
        // Parent package matching should resolve both to com.example.order base
        rewriteRun(
          spec -> spec
            .recipes(
              new PopulateServiceEndpointsInControllerPackage(),
              new PopulateDatabaseConnections(),
              new GenerateCalmArchitecture()
            )
            .cycles(3)
            .expectedCyclesThatMakeChanges(1),
          text(
            "package com.example.order.controller;\npublic class OrderController {}",
            spec -> spec.path("src/main/java/com/example/order/controller/OrderController.java")
          ),
          text(
            "package com.example.order.repository;\npublic interface OrderRepository {}",
            spec -> spec.path("src/main/java/com/example/order/repository/OrderRepository.java")
          ),
          text(
            null,
            spec -> spec
              .path(".moderne/context/calm-architecture.json")
              .after(content -> {
                  org.assertj.core.api.Assertions.assertThat(content)
                    // Service node exists
                    .contains("order-controller")
                    // Database node exists
                    .contains("order-db")
                    // Connects relationship was created via sibling package matching
                    .contains("order-controller-to-order-db")
                    .contains("\"connects\"");
                  return content;
              })
          )
        );
    }
}
