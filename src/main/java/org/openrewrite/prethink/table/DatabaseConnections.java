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

public class DatabaseConnections extends DataTable<DatabaseConnections.Row> {

    public DatabaseConnections(Recipe recipe) {
        super(recipe, "Database connections",
                "Database connections and data access patterns in the application.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The path to the source file containing the database access.")
        String sourcePath;

        @Column(displayName = "Entity/Table name",
                description = "The name of the entity or table being accessed.")
        String entityName;

        @Column(displayName = "Entity class",
                description = "The fully qualified name of the entity class (if applicable).")
        @Nullable
        String entityClass;

        @Column(displayName = "Repository class",
                description = "The fully qualified name of the repository or DAO class (if applicable).")
        @Nullable
        String repositoryClass;

        @Column(displayName = "Connection type",
                description = "The type of database connection (JPA, JDBC, Spring Data, MyBatis).")
        String connectionType;

        @Column(displayName = "Database type",
                description = "The type of database if detectable (PostgreSQL, MySQL, MongoDB, etc.).")
        @Nullable
        String databaseType;
    }
}
