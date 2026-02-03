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

public class ServiceEndpoints extends DataTable<ServiceEndpoints.Row> {

    public ServiceEndpoints(Recipe recipe) {
        super(recipe, "Service endpoints",
                "REST/HTTP endpoints exposed by the application.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Entity ID",
                description = "Unique identifier for this endpoint entity (format: endpoint:{className}#{methodSignature}).")
        String entityId;

        @Column(displayName = "Source path",
                description = "The path to the source file containing the endpoint.")
        String sourcePath;

        @Column(displayName = "Service class",
                description = "The fully qualified name of the controller or resource class.")
        String serviceClass;

        @Column(displayName = "Method name",
                description = "The name of the endpoint method.")
        String methodName;

        @Column(displayName = "HTTP method",
                description = "The HTTP method (GET, POST, PUT, DELETE, PATCH, etc.).")
        String httpMethod;

        @Column(displayName = "Path",
                description = "The URL path pattern for the endpoint.")
        String path;

        @Column(displayName = "Produces",
                description = "Content types the endpoint produces (e.g., application/json).")
        @Nullable
        String produces;

        @Column(displayName = "Consumes",
                description = "Content types the endpoint consumes (e.g., application/json).")
        @Nullable
        String consumes;

        @Column(displayName = "Framework",
                description = "The web framework used (Spring, JAX-RS, Micronaut, Quarkus).")
        String framework;

        @Column(displayName = "Method signature",
                description = "The full method signature for linking to method descriptions.")
        @Nullable
        String methodSignature;
    }
}
