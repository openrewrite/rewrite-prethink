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
import org.openrewrite.prethink.UpdateAgentConfig.ContextEntry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static java.util.Collections.singletonList;
import static org.openrewrite.PathUtils.separatorsToSystem;
import static org.openrewrite.PathUtils.separatorsToUnix;
import static org.openrewrite.prethink.OrganizationalContext.*;

/**
 * Write the coding agent configuration for a combined, multi-repository Prethink
 * collection.
 * <p>
 * This is the organizational counterpart to {@link UpdateAgentConfig}: it
 * maintains the same marked section, but in an agent config file at the root of
 * the collection rather than in any repository, and it describes context tables
 * that span repositories. The available context is read from the markdown files
 * already in the collection, so it reflects everything exported there so far,
 * not just what the repository currently being analyzed contributed.
 * <p>
 * Place this after the exporting recipes in a composite so that it observes
 * their writes.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class WriteOrganizationalAgentConfig extends ScanningRecipe<WriteOrganizationalAgentConfig.Accumulator> {

    static final String TEMPLATE_RESOURCE = "/org/openrewrite/prethink/prompts/organizational-agent-config-section.txt";

    @Option(displayName = "Target directory",
            description = "The directory holding the combined context, which may be outside of the repository " +
                          "being analyzed. Agent config files are written at its root, describing the context " +
                          "in its `.moderne/context/` directory. A relative path, and no path at all, resolve " +
                          "against the working directory of the process running the recipe, so an absolute " +
                          "path is recommended.",
            required = false,
            example = "/var/lib/prethink/acme")
    @Nullable
    String targetDirectory;

    @Option(displayName = "Target config files",
            description = "Which agent config files to write in the target directory, creating any that do not " +
                          "exist yet. If not specified, `CLAUDE.md` is written.",
            required = false,
            example = "AGENTS.md")
    @Nullable
    List<String> targetConfigFiles;

    @Option(displayName = "Template",
            description = "The template used to generate the context section. The `{{CONTEXT_TABLE}}` placeholder is " +
                          "replaced with the generated context table. If not specified, a bundled default template is used.",
            required = false,
            example = "## Available Context\n\n{{CONTEXT_TABLE}}")
    @Nullable
    String template;

    String displayName = "Write organizational agent configuration files";

    String description = "Write coding agent configuration files (`CLAUDE.md`, `AGENTS.md`, etc.) at the root " +
            "of a combined Prethink collection, describing the context of every repository exported there. " +
            "The files are written directly to the filesystem; the repository being analyzed is left unchanged.";

    @Override
    public boolean causesAnotherCycle() {
        return true;
    }

    public static class Accumulator {
        /**
         * The config is a single filesystem update rather than a per-file edit,
         * so it happens on the first source file visited and is skipped for
         * every other.
         */
        boolean written;
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        // Nothing about the repository being analyzed matters here: the context described
        // is read from the collection itself. The accumulator only carries the write guard.
        return TreeVisitor.noop();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (ctx.getCycle() == 1) {
                    // The context this describes is exported in cycle 2, once sibling recipes have
                    // populated their data tables. This recipe changes no source file, so ask for
                    // that cycle explicitly rather than relying on another recipe to trigger it.
                    ctx.putMessage(Prethink.CYCLE_TRIGGER, true);
                } else if (ctx.getCycle() == 2) {
                    write(acc);
                }
                return tree;
            }
        };
    }

    private void write(Accumulator acc) {
        synchronized (acc) {
            if (acc.written) {
                return;
            }
            acc.written = true;
            try {
                Layout layout = layout(targetDirectory);
                locked(layout, () -> write(layout));
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Unable to write the organizational agent configuration to " + targetPath(targetDirectory), e);
            }
        }
    }

    private void write(Layout layout) throws IOException {
        List<ContextEntry> contextEntries = contextEntries(layout);
        if (contextEntries.isEmpty()) {
            return;
        }
        String section = AgentConfigSection.render(contextEntries, template, TEMPLATE_RESOURCE);
        for (String target : targets()) {
            Path configFile = layout.root.resolve(separatorsToSystem(target));
            String existing = OrganizationalContext.read(configFile);
            writeIfChanged(configFile, existing.isEmpty() ? section : AgentConfigSection.apply(existing, section));
        }
    }

    /**
     * The context available in the collection, read from the markdown files
     * exported there. Paths are recorded relative to the collection root, which
     * is where the agent config -- and the agent reading it -- sits.
     */
    private List<ContextEntry> contextEntries(Layout layout) throws IOException {
        if (!Files.isDirectory(layout.context)) {
            return Collections.emptyList();
        }
        List<ContextEntry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(layout.context)) {
            for (Path file : sorted(files)) {
                if (!file.getFileName().toString().endsWith(".md")) {
                    continue;
                }
                ContextEntry entry = AgentConfigSection.parse(
                        OrganizationalContext.read(file),
                        separatorsToUnix(layout.root.relativize(file).toString()));
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }
        return entries;
    }

    private List<Path> sorted(Stream<Path> files) {
        List<Path> paths = new ArrayList<>();
        files.forEach(paths::add);
        Collections.sort(paths);
        return paths;
    }

    private List<String> targets() {
        if (targetConfigFiles == null) {
            return singletonList("CLAUDE.md");
        }
        List<String> targets = new ArrayList<>(targetConfigFiles.size());
        for (String target : targetConfigFiles) {
            if (target != null && !target.trim().isEmpty()) {
                targets.add(target.trim());
            }
        }
        return targets.isEmpty() ? singletonList("CLAUDE.md") : targets;
    }
}
