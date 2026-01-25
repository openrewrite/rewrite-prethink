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

public class SecurityConfiguration extends DataTable<SecurityConfiguration.Row> {

    public SecurityConfiguration(Recipe recipe) {
        super(recipe, "Security configuration",
                "Security configuration including authentication methods, CORS settings, and OAuth2 configuration.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The path to the source file containing the security configuration.")
        String sourcePath;

        @Column(displayName = "Configuration type",
                description = "The type of security configuration (WebSecurity, OAuth2, CORS, etc.).")
        String configurationType;

        @Column(displayName = "Auth method",
                description = "Authentication method if detected (Basic, OAuth2, JWT, etc.).")
        @Nullable
        String authMethod;

        @Column(displayName = "Allowed origins",
                description = "CORS allowed origins if configured.")
        @Nullable
        String allowedOrigins;

        @Column(displayName = "Description",
                description = "Description of the security configuration.")
        @Nullable
        String description;
    }
}
