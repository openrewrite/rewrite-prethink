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

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.Recipe;
import org.openrewrite.RecipeList;
import org.openrewrite.prethink.calm.GenerateCalmArchitecture;
import org.openrewrite.prethink.table.DataAssets;
import org.openrewrite.prethink.table.DatabaseConnections;
import org.openrewrite.prethink.table.DeploymentArtifacts;
import org.openrewrite.prethink.table.ExternalServiceCalls;
import org.openrewrite.prethink.table.MessagingConnections;
import org.openrewrite.prethink.table.ProjectMetadata;
import org.openrewrite.prethink.table.SecurityConfiguration;
import org.openrewrite.prethink.table.ServerConfiguration;
import org.openrewrite.prethink.table.ServiceEndpoints;

import java.util.Arrays;

/**
 * Core Prethink context recipe that generates CALM architecture and agent configuration.
 * <p>
 * This recipe should be included in a recipe list with other recipes that are emitting CALM entity
 * data table rows. This recipe expects CALM-related data tables to already be populated and will
 * generate the CALM architecture diagram and update agent configuration files accordingly.
 * <p>
 * For a sample complete solution, refer to io.moderne.prethink.UpdatePrethinkContextStarter or
 * io.moderne.prethink.UpdatePrethinkContextNoAiStarter.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class UpdatePrethinkContext extends Recipe {

    @Override
    public String getDisplayName() {
        return "Update Prethink context";
    }

    @Override
    public String getDescription() {
        return "Generate FINOS CALM architecture diagram and update agent configuration files. " +
               "This recipe expects CALM-related data tables (ServiceEndpoints, DatabaseConnections, " +
               "ExternalServiceCalls, MessagingConnections, etc.) to be populated by other recipes in a composite.";
    }

    @Override
    public void buildRecipeList(RecipeList recipes) {
        recipes
                // Generate CALM architecture JSON from discovered components
                .recipe(new GenerateCalmArchitecture())

                // Export CALM architecture context
                .recipe(new ExportContext(
                        "Architecture",
                        "FINOS CALM architecture diagram",
                        "FINOS CALM (Common Architecture Language Model) architecture diagram showing " +
                        "services, databases, external integrations, and messaging connections. Use this " +
                        "to understand the high-level system architecture and component relationships.",
                        Arrays.asList(
                                ServiceEndpoints.class.getName(),
                                DatabaseConnections.class.getName(),
                                ExternalServiceCalls.class.getName(),
                                MessagingConnections.class.getName(),
                                ServerConfiguration.class.getName(),
                                DataAssets.class.getName(),
                                ProjectMetadata.class.getName(),
                                SecurityConfiguration.class.getName(),
                                DeploymentArtifacts.class.getName()
                        )
                ))

                // Update agent config files
                .recipe(new UpdateAgentConfig(null))

                // Update .gitignore to allow committing context files
                .recipe(new UpdateGitignore());
    }
}
