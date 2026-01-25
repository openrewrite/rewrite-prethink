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

public class DataAssets extends DataTable<DataAssets.Row> {

    public DataAssets(Recipe recipe) {
        super(recipe, "Data assets",
                "Data entities, DTOs, and records that represent the application's data model.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The path to the source file containing the data asset.")
        String sourcePath;

        @Column(displayName = "Class name",
                description = "The fully qualified name of the data asset class.")
        String className;

        @Column(displayName = "Simple name",
                description = "The simple class name for display.")
        String simpleName;

        @Column(displayName = "Asset type",
                description = "The type of data asset (Entity, Record, DTO, Document, etc.).")
        String assetType;

        @Column(displayName = "Description",
                description = "A description of the data asset based on its fields.")
        @Nullable
        String description;

        @Column(displayName = "Fields",
                description = "Comma-separated list of field names.")
        @Nullable
        String fields;
    }
}
