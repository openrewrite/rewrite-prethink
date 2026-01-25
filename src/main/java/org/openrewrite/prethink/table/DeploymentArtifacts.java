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

public class DeploymentArtifacts extends DataTable<DeploymentArtifacts.Row> {

    public DeploymentArtifacts(Recipe recipe) {
        super(recipe, "Deployment artifacts",
                "Deployment configuration files (Dockerfile, Kubernetes manifests, docker-compose).");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The path to the deployment artifact file.")
        String sourcePath;

        @Column(displayName = "Artifact type",
                description = "The type of deployment artifact (Dockerfile, Kubernetes, docker-compose).")
        String artifactType;

        @Column(displayName = "Container image",
                description = "The base container image if detected.")
        @Nullable
        String containerImage;

        @Column(displayName = "Exposed port",
                description = "Port exposed by the container.")
        @Nullable
        Integer exposedPort;

        @Column(displayName = "Description",
                description = "Description of the deployment artifact.")
        @Nullable
        String description;
    }
}
