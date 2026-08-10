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
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

/**
 * Which data tables each exported context is made of.
 * <p>
 * A context groups data tables under one name and description, but that grouping
 * lives only in the arguments of the recipe that exports it, where nothing else
 * in the run can see it. Recording it here lets a recipe that discovers tables
 * rather than being told about them -- the organizational export in particular --
 * present them under the same contexts, instead of as one undifferentiated pile.
 */
public class ContextTables extends DataTable<ContextTables.Row> {

    public ContextTables(Recipe recipe) {
        super(recipe, "Context tables",
                "The data tables each exported context is composed of, one row per context and table.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Context",
                description = "The display name of the context the data table belongs to.")
        String context;

        @Column(displayName = "Short description",
                description = "A brief description of what context this provides.")
        String shortDescription;

        @Column(displayName = "Long description",
                description = "A detailed description of the context and how to use it.")
        String longDescription;

        @Column(displayName = "Context file",
                description = "Path to the markdown file describing this context.")
        String contextFile;

        @Column(displayName = "Data table",
                description = "Fully qualified class name of a data table the context is composed of.")
        String dataTable;
    }
}
