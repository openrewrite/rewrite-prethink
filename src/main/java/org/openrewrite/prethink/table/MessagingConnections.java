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

public class MessagingConnections extends DataTable<MessagingConnections.Row> {

    public MessagingConnections(Recipe recipe) {
        super(recipe, "Messaging connections",
                "Message queue producers and consumers in the application.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Entity ID",
                description = "Unique identifier for this messaging entity (format: messaging:{className}#{methodName}:{role}).")
        String entityId;

        @Column(displayName = "Source path",
                description = "The path to the source file containing the messaging component.")
        String sourcePath;

        @Column(displayName = "Class name",
                description = "The fully qualified name of the class containing the listener/producer.")
        String className;

        @Column(displayName = "Method name",
                description = "The name of the method that handles or sends messages.")
        @Nullable
        String methodName;

        @Column(displayName = "Destination",
                description = "The topic or queue name.")
        String destination;

        @Column(displayName = "Role",
                description = "Whether this is a producer, consumer, or both.")
        String role;

        @Column(displayName = "Messaging type",
                description = "The messaging system (Kafka, RabbitMQ, JMS, etc.).")
        String messagingType;

        @Column(displayName = "Method signature",
                description = "The full method signature for linking to method descriptions.")
        @Nullable
        String methodSignature;
    }
}
