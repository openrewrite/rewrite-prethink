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
import org.jspecify.annotations.Nullable;
import org.openrewrite.Option;
import org.openrewrite.Recipe;
import org.openrewrite.RecipeList;

import java.util.List;

/**
 * The organizational counterpart to {@link UpdatePrethinkContext}: instead of
 * leaving each repository describing only itself, every repository this runs
 * against contributes to one collection on the filesystem, which an agent can
 * then be started in to reason across all of them.
 * <p>
 * Like {@link UpdatePrethinkContext}, this recipe should be included in a recipe
 * list with other recipes that emit CALM entity data table rows; it expects
 * those tables to already be populated. Point it at the same target directory
 * for every repository, and run it repeatedly as repositories change --
 * re-running for a repository replaces exactly that repository's rows.
 * <p>
 * The repositories analyzed are left unchanged. The collection is written
 * directly to the filesystem rather than expressed as source file changes, so
 * this recipe produces no diff to review or commit.
 */
@EqualsAndHashCode(callSuper = false)
@Value
public class UpdateOrganizationalPrethinkContext extends Recipe {

    @Option(displayName = "Target directory",
            description = "The directory the combined context is written to, which may be outside of the " +
                          "repository being analyzed so that every repository contributes to one central " +
                          "collection. Context files are written to `.moderne/context/` within it and agent " +
                          "config files at its root. A relative path resolves against the working directory of " +
                          "the process running the recipe, so an absolute path is recommended.",
            example = "/var/lib/prethink/acme")
    String targetDirectory;

    @Option(displayName = "Repository",
            description = "The name this repository contributes rows under, recorded in the `Repository` column " +
                          "of every combined table. If not specified, it is taken from the git remote as " +
                          "`organization/repository`.",
            required = false,
            example = "acme/orders-service")
    @Nullable
    String repositoryName;

    @Option(displayName = "Data tables to exclude",
            description = "Fully qualified class names of DataTables to leave out of the combined tables. " +
                          "Everything the composite discovered is exported otherwise, so this is only needed " +
                          "when a composite contains recipes whose data tables are not context worth keeping.",
            required = false,
            example = "org.openrewrite.table.SourcesFileResults")
    @Nullable
    List<String> excludeDataTables;

    @Option(displayName = "Target config files",
            description = "Which agent config files to write in the target directory, creating any that do not " +
                          "exist yet. If not specified, `CLAUDE.md` is written.",
            required = false,
            example = "AGENTS.md")
    @Nullable
    List<String> targetConfigFiles;

    @Option(displayName = "Template",
            description = "The template used to generate the context section. The `{{CONTEXT_TABLE}}` placeholder is " +
                          "replaced with the generated context table. If not specified, a bundled default template is used.",
            required = false,
            example = "## Available Context\n\n{{CONTEXT_TABLE}}")
    @Nullable
    String template;

    String displayName = "Update organizational Prethink context";

    String description = "Combine the Prethink context of many repositories into one directory on the " +
            "filesystem, which may live outside all of them, so that a single coding agent started there can " +
            "reason over the whole organization. Each repository contributes rows to combined CSVs keyed by a " +
            "`Repository` column, plus its FINOS CALM architecture, and the agent configuration in the target " +
            "directory is updated to describe them. This recipe expects CALM-related data tables " +
            "(ServiceEndpoints, DatabaseConnections, ExternalServiceCalls, MessagingConnections, etc.) to be " +
            "populated by other recipes in a composite. The repositories analyzed are left unchanged.";

    @Override
    public void buildRecipeList(RecipeList recipes) {
        recipes
                // Collect this repository's CALM architecture into the shared collection
                .recipe(new ExportOrganizationalCalmArchitecture(targetDirectory, repositoryName))

                // Append whatever this composite discovered to the combined tables. No list of
                // data tables is configured, so this exports what the run actually produced --
                // which is what lets the same composite work for architectural discovery, test
                // coverage, code comprehension, or anything else added later.
                .recipe(new ExportOrganizationalContext(
                        targetDirectory,
                        repositoryName,
                        "Codebase Context",
                        "Everything Prethink extracted from every repository in this collection",
                        "A combined inventory of every repository analyzed into this collection: what each " +
                        "one exposes, owns, calls, and depends on, as discovered by the recipes run against " +
                        "it. Use this to understand what a system does before reading any of its source, and " +
                        "to follow an interaction from the repository that starts it to the repository that " +
                        "serves it. Each repository's own architecture diagram is in " +
                        "`architecture/<repository>.json`.",
                        null,
                        excludeDataTables
                ))

                // Describe everything exported so far to the agent. Last, so that it sees
                // the files the recipes above write during this same cycle.
                .recipe(new WriteOrganizationalAgentConfig(targetDirectory, targetConfigFiles, template));
    }
}
