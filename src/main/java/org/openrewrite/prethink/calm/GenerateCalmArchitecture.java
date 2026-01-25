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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.openrewrite.prethink.table.ClassDescriptions;
import org.openrewrite.prethink.table.DataAssets;
import org.openrewrite.prethink.table.DatabaseConnections;
import org.openrewrite.prethink.table.ExternalServiceCalls;
import org.openrewrite.prethink.table.MessagingConnections;
import org.openrewrite.prethink.table.ProjectMetadata;
import org.openrewrite.prethink.table.SecurityConfiguration;
import org.openrewrite.prethink.table.ServerConfiguration;
import org.openrewrite.prethink.table.ServiceEndpoints;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.text.PlainText;

import java.nio.file.Path;
import java.util.*;

import static org.openrewrite.prethink.Prethink.CONTEXT_DIR;

/**
 * Recipe that generates a FINOS CALM architecture JSON file from discovered
 * service endpoints, database connections, external service calls, and messaging connections.
 * <p>
 * This recipe reads from DATA_TABLES in its visitor phase, after all architectural
 * discovery recipes have populated their data tables.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class GenerateCalmArchitecture extends ScanningRecipe<GenerateCalmArchitecture.Accumulator> {

    private static final String CALM_FILENAME = "calm-architecture.json";
    private static final String CALM_SCHEMA = "https://calm.finos.org/draft/2025-03/meta/calm.json";
    private static final String PLACEHOLDER_CONTENT = "{}";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Override
    public String getDisplayName() {
        return "Generate [CALM](https://calm.finos.org/) architecture";
    }

    @Override
    public String getDescription() {
        return "Generate a FINOS CALM (Common Architecture Language Model) JSON file " +
               "from discovered service endpoints, database connections, external service calls, " +
               "and messaging connections.";
    }

    @Override
    public boolean causesAnotherCycle() {
        return true;
    }

    @Data
    @AllArgsConstructor
    public static class Accumulator {
        final Set<Path> existingContextPaths;
        boolean createdPlaceholder;
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator(new HashSet<>(), false);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    SourceFile sf = (SourceFile) tree;
                    Path path = sf.getSourcePath();
                    if (path.startsWith(CONTEXT_DIR)) {
                        acc.getExistingContextPaths().add(path);
                    }
                }
                return tree;
            }
        };
    }

    @Override
    public Collection<SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        // Cycle 1: Create placeholder file (triggers cycle 2)
        // Cycle 2: Placeholder will be populated in getVisitor() using data tables
        if (ctx.getCycle() == 1) {
            List<SourceFile> generated = new ArrayList<>();
            Path calmPath = CONTEXT_DIR.resolve(CALM_FILENAME);

            if (!acc.getExistingContextPaths().contains(calmPath)) {
                PlainText calmFile = PlainText.builder()
                        .text(PLACEHOLDER_CONTENT)
                        .sourcePath(calmPath)
                        .build();
                generated.add(calmFile);
                acc.setCreatedPlaceholder(true);
            }
            return generated;
        }

        // Cycle 2+: Don't generate new files
        return Collections.emptyList();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                // Skip first cycle only if we just created a placeholder.
                // If the file already existed, process it immediately since
                // Find* recipes have already populated the data tables.
                if (ctx.getCycle() == 1 && acc.isCreatedPlaceholder()) {
                    return tree;
                }
                if (tree instanceof PlainText) {
                    PlainText pt = (PlainText) tree;
                    Path path = pt.getSourcePath();

                    if (path.equals(CONTEXT_DIR.resolve(CALM_FILENAME))) {
                        // Generate CALM content from data tables (now available in visitor phase)
                        String newContent = generateCalmJsonFromDataTables(ctx);

                        // If no architectural data found
                        if (newContent == null) {
                            // Delete if it's a placeholder (empty or our placeholder content)
                            // This works across cycles since accumulators are reset between cycles
                            if (PLACEHOLDER_CONTENT.equals(pt.getText()) || pt.getText().trim().isEmpty()) {
                                return null;
                            }
                            return tree;
                        }

                        // Update with real CALM content if different
                        if (!newContent.equals(pt.getText())) {
                            return pt.withText(newContent);
                        }
                    }
                }
                return tree;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> getTableRows(Map<DataTable<?>, List<?>> allTables, Class<? extends DataTable<?>> tableClass) {
        for (Map.Entry<DataTable<?>, List<?>> entry : allTables.entrySet()) {
            if (entry.getKey().getClass().getName().equals(tableClass.getName())) {
                return (List<T>) entry.getValue();
            }
        }
        return Collections.emptyList();
    }

    private @Nullable String generateCalmJsonFromDataTables(ExecutionContext ctx) {
        Map<DataTable<?>, List<?>> allTables = ctx.getMessage(ExecutionContext.DATA_TABLES);

        if (allTables == null || allTables.isEmpty()) {
            return null;
        }

        List<ServiceEndpoints.Row> endpoints = getTableRows(allTables, ServiceEndpoints.class);
        List<DatabaseConnections.Row> databases = getTableRows(allTables, DatabaseConnections.class);
        List<ExternalServiceCalls.Row> externalCalls = getTableRows(allTables, ExternalServiceCalls.class);
        List<MessagingConnections.Row> messaging = getTableRows(allTables, MessagingConnections.class);

        // Don't generate empty architecture files
        if (endpoints.isEmpty() && databases.isEmpty() && externalCalls.isEmpty() && messaging.isEmpty()) {
            return null;
        }

        CalmBuilder builder = new CalmBuilder(allTables);
        builder.addSystemNode();
        builder.addServiceNodes(endpoints);
        builder.addDataAssetNodes();
        builder.addWebClientNode();
        builder.addDatabaseNodes(databases);
        builder.addExternalServiceNodes(externalCalls);
        builder.addMessagingNodes(messaging);
        builder.addComposedOfRelationships();

        try {
            return OBJECT_MAPPER.writeValueAsString(builder.build());
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private class CalmBuilder {
        private final List<CalmNode> nodes = new ArrayList<>();
        private final List<CalmRelationship> relationships = new ArrayList<>();
        private final Map<String, String> serviceClassToId = new HashMap<>();
        private final List<String> serviceNodeIds = new ArrayList<>();
        private final Map<String, String> aiDescriptionsByClass = new HashMap<>();
        private final List<ServerConfiguration.Row> serverConfigs;
        private final List<DataAssets.Row> dataAssets;
        private final List<ProjectMetadata.Row> projectMetadata;
        private final List<SecurityConfiguration.Row> securityConfigs;
        private final String serverProtocol;
        private final int serverPort;
        private String systemNodeId;

        CalmBuilder(Map<DataTable<?>, List<?>> allTables) {
            this.serverConfigs = getTableRows(allTables, ServerConfiguration.class);
            this.dataAssets = getTableRows(allTables, DataAssets.class);
            this.projectMetadata = getTableRows(allTables, ProjectMetadata.class);
            this.securityConfigs = getTableRows(allTables, SecurityConfiguration.class);

            List<ClassDescriptions.Row> classDescriptions = getTableRows(allTables, ClassDescriptions.class);
            for (ClassDescriptions.Row row : classDescriptions) {
                aiDescriptionsByClass.put(row.getClassName(), row.getDescription());
            }

            if (!serverConfigs.isEmpty()) {
                ServerConfiguration.Row config = serverConfigs.get(0);
                this.serverProtocol = config.getProtocol();
                this.serverPort = config.getPort();
            } else {
                this.serverProtocol = "HTTP";
                this.serverPort = 8080;
            }
        }

        void addSystemNode() {
            if (projectMetadata.isEmpty()) {
                return;
            }
            ProjectMetadata.Row project = projectMetadata.get(0);
            systemNodeId = toKebabCase(project.getArtifactId());

            String systemName = project.getName() != null ? project.getName() : project.getArtifactId();
            String systemDescription = project.getDescription() != null ? project.getDescription() :
                    "System containing " + project.getArtifactId() + " services";

            nodes.add(new CalmNode(systemNodeId, "system", systemName, systemDescription, null));
        }

        void addServiceNodes(List<ServiceEndpoints.Row> endpoints) {
            Map<String, List<ServiceEndpoints.Row>> endpointsByClass = new LinkedHashMap<>();
            for (ServiceEndpoints.Row endpoint : endpoints) {
                if (endpoint != null && endpoint.getServiceClass() != null) {
                    endpointsByClass.computeIfAbsent(endpoint.getServiceClass(), k -> new ArrayList<>()).add(endpoint);
                }
            }

            for (Map.Entry<String, List<ServiceEndpoints.Row>> entry : endpointsByClass.entrySet()) {
                String serviceClass = entry.getKey();
                List<ServiceEndpoints.Row> classEndpoints = entry.getValue();
                if (classEndpoints == null || classEndpoints.isEmpty()) {
                    continue;
                }
                ServiceEndpoints.Row first = classEndpoints.get(0);
                if (first == null) {
                    continue;
                }

                String nodeId = toKebabCase(first.getServiceName());
                serviceClassToId.put(serviceClass, nodeId);

                List<CalmInterface> interfaces = Collections.singletonList(new CalmInterface(nodeId + "-api", serverProtocol, serverPort));
                String description = buildServiceDescription(serviceClass, classEndpoints);

                nodes.add(new CalmNode(nodeId, "service", first.getServiceName(), description, interfaces));
                serviceNodeIds.add(nodeId);
            }
        }

        private String buildServiceDescription(String serviceClass, List<ServiceEndpoints.Row> classEndpoints) {
            String aiDescription = aiDescriptionsByClass.get(serviceClass);
            if (aiDescription != null && !aiDescription.isEmpty()) {
                return aiDescription;
            }
            StringBuilder sb = new StringBuilder("REST API with endpoints: ");
            int count = 0;
            for (ServiceEndpoints.Row ep : classEndpoints) {
                if (count > 0) sb.append(", ");
                sb.append(ep.getHttpMethod()).append(" ").append(ep.getPath());
                if (++count >= 5) {
                    sb.append(" and ").append(classEndpoints.size() - 5).append(" more");
                    break;
                }
            }
            return sb.toString();
        }

        void addDataAssetNodes() {
            Set<String> seen = new HashSet<>();
            for (DataAssets.Row asset : dataAssets) {
                String nodeId = toKebabCase(asset.getSimpleName()) + "-data";
                if (!seen.add(nodeId)) {
                    continue;
                }
                String description = asset.getDescription() != null ? asset.getDescription() :
                        asset.getAssetType() + " " + asset.getSimpleName();
                nodes.add(new CalmNode(nodeId, "data-asset", asset.getSimpleName(), description, null));
            }
        }

        void addWebClientNode() {
            boolean hasCorsConfig = securityConfigs.stream()
                    .anyMatch(sc -> "CORS".equals(sc.getConfigurationType()));
            if (!hasCorsConfig || serviceNodeIds.isEmpty()) {
                return;
            }

            String webClientNodeId = "web-client";
            String origins = securityConfigs.stream()
                    .filter(sc -> "CORS".equals(sc.getConfigurationType()) && sc.getAllowedOrigins() != null)
                    .map(SecurityConfiguration.Row::getAllowedOrigins)
                    .findFirst()
                    .orElse("configured origins");

            nodes.add(new CalmNode(webClientNodeId, "webclient", "Web Client",
                    "Web application client accessing the API from " + origins, null));

            String primaryServiceId = serviceNodeIds.get(0);
            relationships.add(new CalmRelationship(
                    webClientNodeId + "-interacts-" + primaryServiceId,
                    "interacts",
                    new CalmEndpoint(webClientNodeId, null),
                    new CalmDestination(primaryServiceId, primaryServiceId + "-api"),
                    serverProtocol
            ));
        }

        void addDatabaseNodes(List<DatabaseConnections.Row> databases) {
            Set<String> seen = new HashSet<>();
            for (DatabaseConnections.Row db : databases) {
                String nodeId = toKebabCase(db.getEntityName()) + "-db";
                if (!seen.add(nodeId)) {
                    continue;
                }

                String dbType = db.getDatabaseType() != null ? db.getDatabaseType() : "SQL";
                nodes.add(new CalmNode(nodeId, "database", db.getEntityName() + " Store",
                        dbType + " database for " + db.getEntityName() + " data", null));

                String serviceId = findServiceForClass(db.getRepositoryClass());
                if (serviceId == null && db.getEntityClass() != null) {
                    serviceId = findServiceInSamePackage(db.getEntityClass());
                }
                if (serviceId != null) {
                    relationships.add(new CalmRelationship(
                            serviceId + "-to-" + nodeId, "connects",
                            new CalmEndpoint(serviceId, null),
                            new CalmDestination(nodeId, "jdbc"), "JDBC"
                    ));
                }
            }
        }

        void addExternalServiceNodes(List<ExternalServiceCalls.Row> externalCalls) {
            Set<String> seen = new HashSet<>();
            for (ExternalServiceCalls.Row ext : externalCalls) {
                String nodeId = toKebabCase(ext.getTargetService());
                if (!seen.add(nodeId)) {
                    continue;
                }

                nodes.add(new CalmNode(nodeId, "service", ext.getTargetService(),
                        "External " + ext.getClientType() + " service", null));

                String callerServiceId = findServiceForClass(ext.getClientClass());
                if (callerServiceId == null) {
                    callerServiceId = findServiceInSamePackage(ext.getClientClass());
                }
                if (callerServiceId != null) {
                    String protocol = ext.getProtocol() != null ? ext.getProtocol() : "HTTPS";
                    relationships.add(new CalmRelationship(
                            callerServiceId + "-to-" + nodeId, "connects",
                            new CalmEndpoint(callerServiceId, null),
                            new CalmDestination(nodeId, "api"), protocol
                    ));
                }
            }
        }

        void addMessagingNodes(List<MessagingConnections.Row> messaging) {
            Map<String, String> destinationToNodeId = new HashMap<>();
            for (MessagingConnections.Row msg : messaging) {
                String nodeId = toKebabCase(msg.getDestination()) + "-" + msg.getMessagingType().toLowerCase().replace(" ", "-");
                if (!destinationToNodeId.containsKey(msg.getDestination())) {
                    destinationToNodeId.put(msg.getDestination(), nodeId);
                    nodes.add(new CalmNode(nodeId, "network", msg.getDestination(),
                            msg.getMessagingType() + " " + (msg.getRole().equals("consumer") ? "topic/queue" : "destination"), null));
                }

                String serviceId = findServiceForClass(msg.getClassName());
                if (serviceId == null) {
                    serviceId = findServiceInSamePackage(msg.getClassName());
                }
                if (serviceId != null) {
                    String msgNodeId = destinationToNodeId.get(msg.getDestination());
                    String protocol = msg.getMessagingType().contains("Kafka") ? "TCP" : "AMQP";

                    if (msg.getRole().equals("producer")) {
                        relationships.add(new CalmRelationship(
                                serviceId + "-publishes-to-" + msgNodeId, "connects",
                                new CalmEndpoint(serviceId, null),
                                new CalmDestination(msgNodeId, null), protocol
                        ));
                    } else {
                        relationships.add(new CalmRelationship(
                                msgNodeId + "-consumed-by-" + serviceId, "connects",
                                new CalmEndpoint(msgNodeId, null),
                                new CalmDestination(serviceId, null), protocol
                        ));
                    }
                }
            }
        }

        void addComposedOfRelationships() {
            if (systemNodeId == null) {
                return;
            }
            for (String serviceId : serviceNodeIds) {
                relationships.add(new CalmRelationship(
                        systemNodeId + "-contains-" + serviceId, "composed-of",
                        new CalmEndpoint(systemNodeId, null),
                        new CalmDestination(serviceId, null), null
                ));
            }
        }

        private @Nullable String findServiceForClass(@Nullable String className) {
            return className == null ? null : serviceClassToId.get(className);
        }

        private @Nullable String findServiceInSamePackage(@Nullable String className) {
            if (className == null) {
                return null;
            }
            String pkg = className.contains(".") ? className.substring(0, className.lastIndexOf('.')) : "";
            for (Map.Entry<String, String> entry : serviceClassToId.entrySet()) {
                String serviceClass = entry.getKey();
                String servicePkg = serviceClass.contains(".") ? serviceClass.substring(0, serviceClass.lastIndexOf('.')) : "";
                if (servicePkg.equals(pkg) || servicePkg.startsWith(pkg) || pkg.startsWith(servicePkg)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        CalmDocument build() {
            return new CalmDocument(CALM_SCHEMA, nodes, relationships);
        }
    }

    private String toKebabCase(@Nullable String input) {
        if (input == null) {
            return "unknown";
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && result.length() > 0 && result.charAt(result.length() - 1) != '-') {
                    result.append('-');
                }
                result.append(Character.toLowerCase(c));
            } else if (c == ' ' || c == '_') {
                if (result.length() > 0 && result.charAt(result.length() - 1) != '-') {
                    result.append('-');
                }
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
