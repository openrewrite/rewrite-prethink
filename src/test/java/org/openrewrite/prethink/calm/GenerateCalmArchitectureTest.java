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
import org.openrewrite.prethink.table.ServiceEndpoints;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.util.Map;
import java.util.List;

import static org.openrewrite.test.SourceSpecs.text;

class GenerateCalmArchitectureTest implements RewriteTest {

    /**
     * A fake recipe that just populates the ServiceEndpoints data table.
     */
    static class PopulateServiceEndpoints extends Recipe {
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
                    if (tree instanceof SourceFile) {
                        SourceFile sf = (SourceFile) tree;
                        if (sf.getSourcePath().toString().endsWith("GreetingController.java")) {
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
                            System.out.println("[TEST] Inserted ServiceEndpoints row");
                        }
                    }
                    return tree;
                }
            };
        }
    }

    @Test
    void generatesNothingWithNoDataTables() {
        // Without any data tables populated, the recipe creates a placeholder in cycle 1,
        // then deletes it in cycle 2 when it finds no architectural data.
        // Cycle 1: placeholder created (change)
        // Cycle 2: placeholder deleted (change)
        rewriteRun(
            spec -> spec
                .recipe(new GenerateCalmArchitecture())
                .cycles(2)
                .expectedCyclesThatMakeChanges(2),
            text(
                "test content",
                spec -> spec.path("README.md")
            )
        );
    }

    @Test
    void generatesCalmWhenServiceEndpointsPopulated() {
        // Test with both recipes - PopulateServiceEndpoints populates DATA_TABLES,
        // then GenerateCalmArchitecture creates placeholder in cycle 1 and updates it in cycle 2
        // Cycle 1: placeholder created, DATA_TABLES populated by visitor
        // Cycle 2: placeholder updated with real CALM content
        rewriteRun(
            spec -> spec
                .recipes(new PopulateServiceEndpoints(), new GenerateCalmArchitecture())
                .cycles(3)
                .expectedCyclesThatMakeChanges(3), // DEBUG: see what's happening
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
    void debugDataTablesAccess() {
        // Test to debug how DATA_TABLES is accessed - same as above but simpler assertion
        // Cycle 1: placeholder created, DATA_TABLES populated by visitor
        // Cycle 2: placeholder updated with real CALM content
        rewriteRun(
            spec -> spec
                .recipes(new PopulateServiceEndpoints(), new GenerateCalmArchitecture())
                .cycles(3)
                .expectedCyclesThatMakeChanges(3), // DEBUG: see what's happening
            text(
                "package com.example;\npublic class GreetingController {}",
                spec -> spec.path("src/main/java/com/example/GreetingController.java")
            ),
            text(
                null,
                spec -> spec
                    .path(".moderne/context/calm-architecture.json")
                    .after(content -> {
                        // Verify CALM file was created with expected content
                        org.assertj.core.api.Assertions.assertThat(content)
                            .contains("greeting-controller")
                            .contains("GET /greeting")
                            .contains("unique-id")
                            .contains("node-type")
                            .doesNotContain("uniqueId")
                            .doesNotContain("nodeType");
                        return content;
                    })
            )
        );
    }
}
