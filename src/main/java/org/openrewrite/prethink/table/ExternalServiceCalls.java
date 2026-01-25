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

public class ExternalServiceCalls extends DataTable<ExternalServiceCalls.Row> {

    public ExternalServiceCalls(Recipe recipe) {
        super(recipe, "External service calls",
                "Outbound HTTP/REST calls to external services.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The path to the source file containing the external service call.")
        String sourcePath;

        @Column(displayName = "Client class",
                description = "The fully qualified name of the class making the external call.")
        String clientClass;

        @Column(displayName = "Target service",
                description = "The name or URL of the target external service.")
        String targetService;

        @Column(displayName = "Client type",
                description = "The type of HTTP client used (RestTemplate, WebClient, Feign, etc.).")
        String clientType;

        @Column(displayName = "Protocol",
                description = "The protocol used (HTTP, HTTPS).")
        @Nullable
        String protocol;

        @Column(displayName = "Base URL",
                description = "The base URL for the external service if configured.")
        @Nullable
        String baseUrl;
    }
}
