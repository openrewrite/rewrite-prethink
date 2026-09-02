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

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.marker.Markers;
import org.openrewrite.prethink.table.ContextRegistry;
import org.openrewrite.text.PlainText;
import org.openrewrite.text.PlainTextVisitor;

import java.nio.file.Paths;
import java.util.*;

import static java.util.Collections.emptyList;
import static org.openrewrite.PathUtils.separatorsToSystem;
import static org.openrewrite.PathUtils.separatorsToUnix;

/**
 * Recipe that updates coding agent configuration files (CLAUDE.md, .cursorrules, etc.)
 * to include references to Moderne Prethink context files.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class UpdateAgentConfig extends ScanningRecipe<UpdateAgentConfig.Accumulator> {

    transient ContextRegistry contextRegistry = new ContextRegistry(this);

    static final String TEMPLATE_RESOURCE = "/org/openrewrite/prethink/prompts/agent-config-section.txt";

    private static final List<String> AGENT_CONFIG_FILES = Arrays.asList(
            "AGENTS.md",
            "CLAUDE.md",
            ".cursorrules",
            ".github/copilot-instructions.md"
    );

    @Option(displayName = "Target config files",
            description = "Which agent config files to update, creating any that do not exist yet. " +
                    "If not specified, updates all found files, creating `CLAUDE.md` when none exist.",
            required = false,
            example = "CLAUDE.md")
    @Nullable
    List<String> targetConfigFiles;

    @Option(displayName = "Template",
            description = "The template used to generate the context section. The `{{CONTEXT_TABLE}}` placeholder is " +
                          "replaced with the generated context table. If not specified, a bundled default template is used.",
            required = false,
            example = "## Available Context\n\n{{CONTEXT_TABLE}}")
    @Nullable
    String template;

    String displayName = "Update agent configuration files";

    String description = "Update coding agent configuration files (CLAUDE.md, .cursorrules, etc.) " +
               "to include references to Moderne Prethink context files in .moderne/context/.";

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
                    if (path.startsWith(separatorsToSystem(".moderne/context/")) && path.endsWith(".md")) {
                        if (sf instanceof PlainText) {
                            PlainText pt = (PlainText) sf;
                            ContextEntry entry = AgentConfigSection.parse(pt.getText(), path);
                            if (entry != null) {
                                acc.getContextEntries().add(entry);
                            }
                        }
                    }

                    // Track agent config files
                    String fileName = sf.getSourcePath().getFileName().toString();
                    if (isConfigFile(path, fileName)) {
                        acc.getFoundConfigFiles().add(path);
                    }
                }
                return tree;
            }
        };
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

        List<String> targets = targets();
        if (targets.isEmpty()) {
            // No targets specified: create CLAUDE.md only when no config files exist at all
            if (acc.getFoundConfigFiles().isEmpty()) {
                generated.add(newConfigFile("CLAUDE.md", acc));
            }
        } else {
            // Targets specified: create each target that does not exist yet
            for (String target : targets) {
                boolean exists = acc.getFoundConfigFiles().stream()
                        .anyMatch(path -> matchesTarget(path, target));
                if (!exists) {
                    generated.add(newConfigFile(target, acc));
                }
            }
        }

        return generated;
    }

    private List<String> targets() {
        if (targetConfigFiles == null) {
            return emptyList();
        }
        List<String> targets = new ArrayList<>(targetConfigFiles.size());
        for (String target : targetConfigFiles) {
            if (target != null && !target.trim().isEmpty()) {
                targets.add(target.trim());
            }
        }
        return targets;
    }

    private PlainText newConfigFile(String target, Accumulator acc) {
        return PlainText.builder()
                .id(Tree.randomId())
                .sourcePath(Paths.get(separatorsToSystem(target)))
                .markers(Markers.EMPTY)
                .text(generateContextSection(acc.getContextEntries()))
                .build();
    }

    private boolean isConfigFile(String path, String fileName) {
        if (AGENT_CONFIG_FILES.contains(separatorsToUnix(fileName)) ||
            AGENT_CONFIG_FILES.stream().anyMatch(separatorsToUnix(path)::endsWith)) {
            return true;
        }
        return targets().stream().anyMatch(target -> matchesTarget(path, target));
    }

    private boolean matchesTarget(String path, String target) {
        String unixPath = separatorsToUnix(path);
        String unixTarget = separatorsToUnix(target);
        return unixPath.equals(unixTarget) ||
               unixPath.endsWith("/" + unixTarget) ||
               Paths.get(path).getFileName().toString().equals(unixTarget);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new PlainTextVisitor<ExecutionContext>() {
            @Override
            public PlainText visitText(PlainText text, ExecutionContext ctx) {
                String path = text.getSourcePath().toString();
                String fileName = text.getSourcePath().getFileName().toString();

                // Check if this is a config file we should update
                if (!isConfigFile(path, fileName)) {
                    return text;
                }

                // Skip if targeting specific files and this isn't one of them
                if (targetConfigFiles != null && !targetConfigFiles.isEmpty() &&
                    targetConfigFiles.stream().noneMatch(target -> matchesTarget(path, target))) {
                    return text;
                }

                // If no context entries found, nothing to do
                if (acc.getContextEntries().isEmpty()) {
                    return text;
                }

                return text.withText(AgentConfigSection.apply(text.getText(), generateContextSection(acc.getContextEntries())));
            }
        };
    }

    private String generateContextSection(List<ContextEntry> contextEntries) {
        return AgentConfigSection.render(contextEntries, template, TEMPLATE_RESOURCE);
    }
}
