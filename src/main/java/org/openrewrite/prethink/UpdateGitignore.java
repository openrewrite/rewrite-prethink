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
import org.openrewrite.text.PlainText;
import org.openrewrite.text.PlainTextVisitor;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.openrewrite.prethink.Prethink.CONTEXT_DIR;

/**
 * Updates .gitignore to allow committing the .moderne/context/ directory while
 * ignoring other files in .moderne/.
 * <p>
 * This recipe only modifies .gitignore when context files actually exist in
 * .moderne/context/. If no context files were generated (e.g. no supported
 * frameworks detected), .gitignore is left unchanged.
 * <p>
 * This recipe transforms:
 * <pre>
 * .moderne/
 * </pre>
 * into:
 * <pre>
 * .moderne/*
 * !.moderne/context/
 * </pre>
 * <p>
 * If .gitignore exists but has no .moderne entry, the entries will be appended.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class UpdateGitignore extends ScanningRecipe<AtomicBoolean> {

    private static final Path GITIGNORE_PATH = Paths.get(".gitignore");

    // Pattern to match .moderne/ or .moderne on its own line (not .moderne/*)
    // Captures everything up to and including the line ending
    private static final Pattern MODERNE_DIR_PATTERN = Pattern.compile(
            "^\\.moderne/?[ \\t]*\\r?\\n",
            Pattern.MULTILINE
    );

    // The entries we want in the gitignore
    private static final String MODERNE_WILDCARD = ".moderne/*";
    private static final String CONTEXT_EXCEPTION = "!.moderne/context/";

    String displayName = "Update .gitignore for Prethink context";

    String description = "Updates .gitignore to allow committing the `.moderne/context/` directory while " +
            "ignoring other files in `.moderne/`. Only modifies .gitignore when context files exist " +
            "in `.moderne/context/`. Transforms `.moderne/` into `.moderne/*` " +
            "with an exception for `!.moderne/context/`.";

    @Override
    public AtomicBoolean getInitialValue(ExecutionContext ctx) {
        return new AtomicBoolean(false);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(AtomicBoolean contextFilesExist) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile && !contextFilesExist.get()) {
                    SourceFile sourceFile = (SourceFile) tree;
                    Path path = sourceFile.getSourcePath();
                    if (path.startsWith(CONTEXT_DIR) &&
                        path.toString().endsWith(".csv") &&
                        sourceFile instanceof PlainText) {
                        String text = ((PlainText) sourceFile).getText();
                        // Only count CSV files that have data rows beyond comments and headers
                        if (hasDataRows(text)) {
                            contextFilesExist.set(true);
                        }
                    }
                }
                return tree;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(AtomicBoolean contextFilesExist) {
        if (!contextFilesExist.get()) {
            return TreeVisitor.noop();
        }
        return new PlainTextVisitor<ExecutionContext>() {
            @Override
            public PlainText visitText(PlainText text, ExecutionContext ctx) {
                if (GITIGNORE_PATH.equals(text.getSourcePath())) {
                    String content = text.getText();
                    String updated = updateGitignoreContent(content);
                    if (!content.equals(updated)) {
                        return text.withText(updated);
                    }
                }
                return text;
            }
        };
    }

    /**
     * Updates the gitignore content to include the correct .moderne patterns.
     *
     * @param content The current gitignore content
     * @return The updated gitignore content
     */
    static boolean hasDataRows(String text) {
        boolean pastHeaders = false;
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("#")) {
                continue;
            }
            if (!pastHeaders) {
                // First non-comment line is the column header
                pastHeaders = true;
                continue;
            }
            if (!line.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    static String updateGitignoreContent(String content) {
        // Check if already has the correct pattern
        if (content.contains(MODERNE_WILDCARD) && content.contains(CONTEXT_EXCEPTION)) {
            return content;
        }

        // Check if it has .moderne/ that needs to be replaced
        Matcher matcher = MODERNE_DIR_PATTERN.matcher(content);
        if (matcher.find()) {
            // Replace .moderne/ with .moderne/* and add the exception
            // The pattern includes the newline, so we add our lines and a trailing newline
            return matcher.replaceAll(MODERNE_WILDCARD + "\n" + CONTEXT_EXCEPTION + "\n");
        }

        // Handle case where .moderne is at the end of file (no trailing newline)
        // Use (?m) for multiline mode so ^ and $ match line boundaries
        if (content.matches("(?s)(?m).*^\\.moderne/?[ \\t]*$")) {
            return content.replaceAll("(?m)^\\.moderne/?[ \\t]*$", MODERNE_WILDCARD + "\n" + CONTEXT_EXCEPTION);
        }

        // Check if it has .moderne/* but missing the exception
        if (content.contains(MODERNE_WILDCARD) && !content.contains(CONTEXT_EXCEPTION)) {
            // Add the exception after .moderne/*
            return content.replace(MODERNE_WILDCARD, MODERNE_WILDCARD + "\n" + CONTEXT_EXCEPTION);
        }

        // No .moderne entry exists - append the entries
        // Preserve the original ending style (with or without trailing newline)
        boolean hasTrailingNewline = content.endsWith("\n");
        StringBuilder sb = new StringBuilder(content);
        if (!content.isEmpty() && !hasTrailingNewline) {
            sb.append("\n");
        }
        if (!content.isEmpty()) {
            sb.append("\n");
        }
        sb.append("# Moderne CLI\n");
        sb.append(MODERNE_WILDCARD).append("\n");
        sb.append(CONTEXT_EXCEPTION);
        if (hasTrailingNewline) {
            sb.append("\n");
        }
        return sb.toString();
    }
}
