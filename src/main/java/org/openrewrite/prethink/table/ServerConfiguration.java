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

public class ServerConfiguration extends DataTable<ServerConfiguration.Row> {

    public ServerConfiguration(Recipe recipe) {
        super(recipe, "Server configuration",
                "Server configuration properties extracted from application.properties/yml.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The path to the configuration file.")
        String sourcePath;

        @Column(displayName = "Server port",
                description = "The server port (default: 8080).")
        int port;

        @Column(displayName = "SSL enabled",
                description = "Whether SSL/TLS is enabled.")
        boolean sslEnabled;

        @Column(displayName = "Context path",
                description = "The servlet context path.")
        @Nullable
        String contextPath;

        @Column(displayName = "Protocol",
                description = "The protocol (HTTP or HTTPS) based on SSL configuration.")
        String protocol;
    }
}
