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

import org.jspecify.annotations.Nullable;
import org.openrewrite.prethink.UpdateAgentConfig.ContextEntry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.joining;

/**
 * The Prethink section of a coding agent's configuration file: how it is
 * discovered in a context markdown file, rendered, and merged into whatever the
 * agent config already says.
 * <p>
 * Shared by {@link UpdateAgentConfig}, which maintains the section in the
 * repository being analyzed, and {@link WriteOrganizationalAgentConfig}, which
 * maintains it in a central collection describing many repositories. The two
 * differ only in the template they render and where the files they read and
 * write live.
 */
final class AgentConfigSection {

    static final String START_MARKER = "<!-- prethink-context -->";
    static final String END_MARKER = "<!-- /prethink-context -->";
    static final String CONTEXT_TABLE_PLACEHOLDER = "{{CONTEXT_TABLE}}";

    /** Matches the full context section, including both markers. */
    static final Pattern SECTION_PATTERN = Pattern.compile(
            "<!-- prethink-context -->.*?<!-- /prethink-context -->",
            Pattern.DOTALL
    );

    private AgentConfigSection() {
    }

    /**
     * Extract the display name (the title) and short description (the first
     * subheading) from a context markdown file, or {@code null} when the file
     * does not carry both.
     */
    static @Nullable ContextEntry parse(String markdown, String contextFile) {
        String displayName = null;
        String shortDescription = null;

        for (String line : markdown.split("\n")) {
            if (line.startsWith("# ") && displayName == null) {
                displayName = line.substring(2).trim();
            } else if (line.startsWith("## ") && displayName != null) {
                shortDescription = line.substring(3).trim();
                break;
            }
        }

        if (displayName != null && shortDescription != null) {
            return new ContextEntry(displayName, shortDescription, contextFile);
        }
        return null;
    }

    /**
     * Render the marked section that describes the available context.
     */
    static String render(List<ContextEntry> contextEntries, @Nullable String template, String defaultTemplateResource) {
        String resolved = template != null ? template : loadTemplate(defaultTemplateResource);
        List<ContextEntry> sorted = new ArrayList<>(contextEntries);
        sorted.sort(Comparator.comparing(ContextEntry::getDisplayName));
        String contextTable = table(sorted);
        // Replace the placeholder with the generated table. If the template omits the placeholder,
        // append the table at the end so the context is never silently dropped.
        String content = resolved.contains(CONTEXT_TABLE_PLACEHOLDER) ?
                resolved.replace(CONTEXT_TABLE_PLACEHOLDER, contextTable) :
                resolved + "\n\n" + contextTable;

        return START_MARKER + "\n" + content + "\n" + END_MARKER;
    }

    static String table(List<ContextEntry> contextEntries) {
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

    /**
     * Replace the existing section in an agent config file, or append it when
     * the file has none yet, leaving everything the file already says intact.
     */
    static String apply(String content, String section) {
        Matcher matcher = SECTION_PATTERN.matcher(content);
        if (matcher.find()) {
            return matcher.replaceFirst(Matcher.quoteReplacement(section));
        }
        String updated = content;
        if (!updated.endsWith("\n")) {
            updated += "\n";
        }
        return updated + "\n" + section;
    }

    static String loadTemplate(String resource) {
        try (InputStream is = AgentConfigSection.class.getResourceAsStream(resource)) {
            if (is == null) {
                throw new IllegalStateException("Template file not found: " + resource);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(joining("\n"));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load template file", e);
        }
    }
}
