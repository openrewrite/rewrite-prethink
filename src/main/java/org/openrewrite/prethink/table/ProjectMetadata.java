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

public class ProjectMetadata extends DataTable<ProjectMetadata.Row> {

    public ProjectMetadata(Recipe recipe) {
        super(recipe, "Project metadata",
                "Project identification including artifact ID, group ID, and name.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The path to the build file (pom.xml or build.gradle).")
        String sourcePath;

        @Column(displayName = "Artifact ID",
                description = "The project's artifact ID (Maven) or project name (Gradle).")
        String artifactId;

        @Column(displayName = "Group ID",
                description = "The project's group ID.")
        @Nullable
        String groupId;

        @Column(displayName = "Name",
                description = "The project's display name.")
        @Nullable
        String name;

        @Column(displayName = "Description",
                description = "The project's description.")
        @Nullable
        String description;

        @Column(displayName = "Version",
                description = "The project's version.")
        @Nullable
        String version;
    }
}
