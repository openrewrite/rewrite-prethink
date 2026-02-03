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

public class ServiceComponents extends DataTable<ServiceComponents.Row> {

    public ServiceComponents(Recipe recipe) {
        super(recipe, "Service components",
                "Service layer components (@Service, @Component, @Named) in the application.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Entity ID",
                description = "Unique identifier for this service component (format: service:{className}).")
        String entityId;

        @Column(displayName = "Source path",
                description = "The path to the source file containing the service component.")
        String sourcePath;

        @Column(displayName = "Class name",
                description = "The fully qualified name of the service component class.")
        String className;

        @Column(displayName = "Simple name",
                description = "The simple class name without package.")
        String simpleName;

        @Column(displayName = "Component type",
                description = "The type of component annotation (Service, Component, Repository, Named).")
        String componentType;

        @Column(displayName = "Framework",
                description = "The framework providing the annotation (Spring, Jakarta CDI, etc.).")
        String framework;

        @Column(displayName = "Description",
                description = "Description from class-level documentation if available.")
        @Nullable
        String description;
    }
}
