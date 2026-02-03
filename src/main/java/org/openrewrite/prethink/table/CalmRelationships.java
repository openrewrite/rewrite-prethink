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

/**
 * Method call graph for discovering transitive relationships between architectural entities.
 * Records all method-to-method calls within the repository, marking entity boundaries.
 */
public class CalmRelationships extends DataTable<CalmRelationships.Row> {

    public CalmRelationships(Recipe recipe) {
        super(recipe, "CALM relationships",
                "Method call graph for discovering relationships between architectural entities. " +
                "Records all method calls within the repository with entity markers for graph traversal.");
    }

    @Value
    public static class Row {
        @Column(displayName = "From class",
                description = "Fully qualified name of the calling class (enables hash lookup).")
        String fromClass;

        @Column(displayName = "From method signature",
                description = "Full signature of the calling method.")
        String fromMethodSignature;

        @Column(displayName = "To class",
                description = "Fully qualified name of the called class (enables hash lookup).")
        String toClass;

        @Column(displayName = "To method signature",
                description = "Full signature of the called method.")
        String toMethodSignature;

        @Column(displayName = "Caller entity ID",
                description = "Entity ID if the calling class is a known entity, null otherwise.")
        @Nullable
        String callerEntityId;

        @Column(displayName = "Called entity ID",
                description = "Entity ID if the called class is a known entity, null otherwise.")
        @Nullable
        String calledEntityId;

        @Column(displayName = "Source path",
                description = "The source file where the method call was found.")
        String sourcePath;
    }
}
