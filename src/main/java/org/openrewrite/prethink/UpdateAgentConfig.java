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

import org.openrewrite.prethink.table.ContextRegistry;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.marker.Markers;
import org.openrewrite.text.PlainText;
import org.openrewrite.text.PlainTextVisitor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recipe that updates coding agent configuration files (CLAUDE.md, .cursorrules, etc.)
 * to include references to Moderne Prethink context files.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class UpdateAgentConfig extends ScanningRecipe<UpdateAgentConfig.Accumulator> {

    transient ContextRegistry contextRegistry = new ContextRegistry(this);

    private static final String CONTEXT_SECTION_MARKER = "<!-- prethink-context -->";
    // Match the full context section including both markers
    private static final Pattern CONTEXT_SECTION_PATTERN = Pattern.compile(
            "<!-- prethink-context -->.*?<!-- /prethink-context -->",
            Pattern.DOTALL
    );

    private static final List<String> AGENT_CONFIG_FILES = Arrays.asList(
            "AGENTS.md",
            "CLAUDE.md",
            ".cursorrules",
            ".github/copilot-instructions.md"
    );

    @Option(displayName = "Target config file",
            description = "Which agent config file to update. If not specified, updates all found files.",
            required = false,
            example = "CLAUDE.md")
    @Nullable
    String targetConfigFile;

    @Override
    public String getDisplayName() {
        return "Update agent configuration files";
    }

    @Override
    public String getDescription() {
        return "Update coding agent configuration files (CLAUDE.md, .cursorrules, etc.) " +
               "to include references to Moderne Prethink context files in .moderne/context/.";
    }

    @Value
    public static class Accumulator {
        /**
         * Context entries found from markdown files in .moderne/context/
         */
        List<ContextEntry> contextEntries;
        Set<String> foundConfigFiles;
    }

    @Value
    public static class ContextEntry {
        String displayName;
        String shortDescription;
        String contextFile;
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator(new ArrayList<>(), new HashSet<>());
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    SourceFile sf = (SourceFile) tree;
                    String path = sf.getSourcePath().toString();

                    // Track context markdown files and extract their info
                    if (path.startsWith(".moderne/context/") && path.endsWith(".md")) {
                        if (sf instanceof PlainText) {
                            PlainText pt = (PlainText) sf;
                            ContextEntry entry = parseContextMarkdown(pt.getText(), path);
                            if (entry != null) {
                                acc.getContextEntries().add(entry);
                            }
                        }
                    }

                    // Track agent config files
                    String fileName = sf.getSourcePath().getFileName().toString();
                    if (AGENT_CONFIG_FILES.contains(fileName) ||
                        AGENT_CONFIG_FILES.stream().anyMatch(path::endsWith)) {
                        acc.getFoundConfigFiles().add(path);
                    }
                }
                return tree;
            }
        };
    }

    private @Nullable ContextEntry parseContextMarkdown(String content, String filePath) {
        // Parse the markdown to extract displayName (title) and shortDescription (first subheading)
        String displayName = null;
        String shortDescription = null;

        String[] lines = content.split("\n");
        for (String line : lines) {
            if (line.startsWith("# ") && displayName == null) {
                displayName = line.substring(2).trim();
            } else if (line.startsWith("## ") && shortDescription == null && displayName != null) {
                shortDescription = line.substring(3).trim();
                break;
            }
        }

        if (displayName != null && shortDescription != null) {
            return new ContextEntry(displayName, shortDescription, filePath);
        }
        return null;
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        List<SourceFile> generated = new ArrayList<>();

        // Record context entries to the data table
        for (ContextEntry entry : acc.getContextEntries()) {
            contextRegistry.insertRow(ctx, new ContextRegistry.Row(
                    entry.getDisplayName(),
                    entry.getShortDescription(),
                    entry.getContextFile()
            ));
        }

        // If no context entries found, nothing to do
        if (acc.getContextEntries().isEmpty()) {
            return generated;
        }

        // If no config files exist and we have a target, create it
        if (acc.getFoundConfigFiles().isEmpty()) {
            String target = targetConfigFile != null ? targetConfigFile : "CLAUDE.md";
            PlainText newConfig = PlainText.builder()
                    .id(Tree.randomId())
                    .sourcePath(Paths.get(target))
                    .markers(Markers.EMPTY)
                    .text(generateContextSection(acc.getContextEntries()))
                    .build();
            generated.add(newConfig);
        }

        return generated;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new PlainTextVisitor<ExecutionContext>() {
            @Override
            public PlainText visitText(PlainText text, ExecutionContext ctx) {
                String path = text.getSourcePath().toString();
                String fileName = text.getSourcePath().getFileName().toString();

                // Check if this is a config file we should update
                boolean isConfigFile = AGENT_CONFIG_FILES.contains(fileName) ||
                        AGENT_CONFIG_FILES.stream().anyMatch(path::endsWith);

                if (!isConfigFile) {
                    return text;
                }

                // Skip if targeting a specific file and this isn't it
                if (targetConfigFile != null && !path.equals(targetConfigFile) &&
                    !fileName.equals(targetConfigFile)) {
                    return text;
                }

                // If no context entries found, nothing to do
                if (acc.getContextEntries().isEmpty()) {
                    return text;
                }

                String content = text.getText();
                String newSection = generateContextSection(acc.getContextEntries());

                // Check if context section already exists
                Matcher matcher = CONTEXT_SECTION_PATTERN.matcher(content);
                if (matcher.find()) {
                    // Replace existing section
                    content = matcher.replaceFirst(Matcher.quoteReplacement(newSection));
                } else {
                    // Add new section at the end
                    if (!content.endsWith("\n")) {
                        content += "\n";
                    }
                    content += "\n" + newSection;
                }

                return text.withText(content);
            }
        };
    }

    private String generateContextSection(List<ContextEntry> contextEntries) {
        String template = loadTemplate();
        String contextTable = generateContextTable(contextEntries);
        String content = template.replace("{{CONTEXT_TABLE}}", contextTable);

        return CONTEXT_SECTION_MARKER + "\n" + content + "\n<!-- /prethink-context -->";
    }

    private String generateContextTable(List<ContextEntry> contextEntries) {
        StringBuilder sb = new StringBuilder();
        sb.append("| Context | Description | Details |\n");
        sb.append("|---------|-------------|--------|\n");

        for (ContextEntry entry : contextEntries) {
            sb.append("| ").append(entry.getDisplayName())
              .append(" | ").append(entry.getShortDescription())
              .append(" | [`").append(Paths.get(entry.getContextFile()).getFileName())
              .append("`](").append(entry.getContextFile()).append(") |\n");
        }

        return sb.toString().trim();
    }

    private String loadTemplate() {
        try (InputStream is = getClass().getResourceAsStream("/org/openrewrite/prethink/prompts/agent-config-section.txt")) {
            if (is == null) {
                throw new IllegalStateException("Template file not found: /org/openrewrite/prethink/prompts/agent-config-section.txt");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load template file", e);
        }
    }
}
