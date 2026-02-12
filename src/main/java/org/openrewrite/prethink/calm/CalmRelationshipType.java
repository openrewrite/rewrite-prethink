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
package org.openrewrite.prethink.calm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The relationship-type object in a CALM relationship.
 * Exactly one of composed-of, connects, or interacts should be non-null.
 */
@Value
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CalmRelationshipType {
    @JsonProperty("composed-of")
    @Nullable ComposedOf composedOf;

    @Nullable Connects connects;

    @Nullable Interacts interacts;

    @Value
    public static class ComposedOf {
        String container;
        List<String> nodes;
    }

    @Value
    public static class Connects {
        CalmNodeInterface source;
        CalmNodeInterface destination;
    }

    @Value
    public static class Interacts {
        String actor;
        List<String> nodes;
    }
}
