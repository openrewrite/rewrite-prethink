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

import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.*;
import org.openrewrite.config.CompositeRecipe;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.marker.GitProvenance;
import org.openrewrite.marker.Markers;
import org.openrewrite.prethink.table.ProjectMetadata;
import org.openrewrite.prethink.table.ServiceEndpoints;
import org.openrewrite.prethink.table.TestMapping;
import org.openrewrite.text.PlainText;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The organizational recipes are exercised the way the Moderne CLI runs them --
 * {@code maxCycles=3, minCycles=1} over a {@link CsvDataTableStore} -- and one
 * repository per run, because that is the shape of the problem: many separate
 * runs, each contributing to a single directory on disk.
 */
class UpdateOrganizationalPrethinkContextTest {

    @DocumentExample
    @Test
    void combinesEveryRepositoryIntoOneCollection(@TempDir Path collection, @TempDir Path dataTables) throws IOException {
        analyze(collection, dataTables.resolve("orders"), "acme/orders-service", "orders-service");
        analyze(collection, dataTables.resolve("shipping"), "acme/shipping-service", "shipping-service");

        // One combined table, with every row attributed to the repository it came from.
        String endpoints = read(collection.resolve(".moderne/context/service-endpoints.csv"));
        assertThat(endpoints.split("\n")[0]).startsWith("Repository,");
        assertThat(endpoints)
          .contains("acme/orders-service,endpoint:com.example.OrdersServiceController")
          .contains("acme/shipping-service,endpoint:com.example.ShippingServiceController");

        // An index of what the collection covers.
        assertThat(read(collection.resolve(".moderne/context/repositories.csv")))
          .contains("acme/orders-service,acme,https://github.com/acme/orders-service.git,main")
          .contains("acme/shipping-service,acme,https://github.com/acme/shipping-service.git,main");

        // A description of the combined tables, explaining the Repository column.
        assertThat(read(collection.resolve(".moderne/context/codebase-context.md")))
          .contains("# Codebase Context")
          .contains("combined across 2 repositories")
          .contains("| Repository | The repository this row was extracted from. |")
          // Paths are relative to the collection root, which is where the agent reading them runs.
          .contains("[`.moderne/context/service-endpoints.csv`](.moderne/context/service-endpoints.csv)")
          .contains("[`.moderne/context/project-metadata.csv`](.moderne/context/project-metadata.csv)");

        // Each repository's own architecture, filed under the same name.
        assertThat(collection.resolve(".moderne/context/architecture/acme/orders-service.json")).exists();
        assertThat(read(collection.resolve(".moderne/context/architecture/acme/shipping-service.json")))
          .contains("shipping-service-controller");
        assertThat(read(collection.resolve(".moderne/context/calm-architecture.md")))
          .contains(".moderne/context/architecture/<repository>.json");

        // Agent instructions at the root of the collection, describing all of it.
        assertThat(read(collection.resolve("CLAUDE.md")))
          .contains("<!-- prethink-context -->")
          .contains("<!-- /prethink-context -->")
          .contains("## Moderne Prethink Organizational Context")
          .contains("| Codebase Context |")
          .contains("(.moderne/context/codebase-context.md)")
          .contains("| CALM Architecture |")
          .contains("(.moderne/context/calm-architecture.md)");
    }

    @Test
    void leavesTheAnalyzedRepositoryUnchanged(@TempDir Path collection, @TempDir Path dataTables) {
        RecipeRun run = analyze(collection, dataTables, "acme/orders-service", "orders-service");

        assertThat(run.getChangeset().getAllResults())
          .as("the collection is written to the filesystem, not to the repository being analyzed")
          .isEmpty();
    }

    @Test
    void reExportingARepositoryReplacesItsRows(@TempDir Path collection, @TempDir Path dataTables) throws IOException {
        analyze(collection, dataTables.resolve("orders-1"), "acme/orders-service", "orders-service");
        analyze(collection, dataTables.resolve("shipping"), "acme/shipping-service", "shipping-service");
        analyze(collection, dataTables.resolve("orders-2"), "acme/orders-service", "orders-service");

        String endpoints = read(collection.resolve(".moderne/context/service-endpoints.csv"));
        assertThat(occurrences(endpoints, "acme/orders-service,"))
          .as("re-analyzing a repository replaces its rows rather than appending them again")
          .isEqualTo(1);
        assertThat(occurrences(endpoints, "acme/shipping-service,"))
          .as("another repository's rows are left untouched")
          .isEqualTo(1);
        assertThat(occurrences(read(collection.resolve(".moderne/context/repositories.csv")), "acme/orders-service,"))
          .isEqualTo(1);
    }

    @Test
    void dropsWhatARepositoryNoLongerContributes(@TempDir Path collection, @TempDir Path dataTables) throws IOException {
        analyze(collection, dataTables.resolve("orders"), "acme/orders-service", "orders-service");
        analyze(collection, dataTables.resolve("shipping"), "acme/shipping-service", "shipping-service");

        // The orders service is analyzed again, this time discovering nothing.
        analyze(collection, dataTables.resolve("orders-empty"), "acme/orders-service", null);

        String endpoints = read(collection.resolve(".moderne/context/service-endpoints.csv"));
        assertThat(endpoints)
          .as("rows a repository no longer produces are purged rather than left stale")
          .doesNotContain("acme/orders-service");
        assertThat(endpoints).contains("acme/shipping-service");
        assertThat(collection.resolve(".moderne/context/architecture/acme/orders-service.json"))
          .as("an architecture that no longer exists is removed")
          .doesNotExist();
        assertThat(collection.resolve(".moderne/context/architecture/acme/shipping-service.json")).exists();
    }

    @Test
    void keepsWhatTheAgentConfigAlreadySays(@TempDir Path collection, @TempDir Path dataTables) throws IOException {
        Files.write(collection.resolve("CLAUDE.md"), "# Our platform\n\nAsk before deploying.\n".getBytes(UTF_8));

        analyze(collection, dataTables, "acme/orders-service", "orders-service");

        assertThat(read(collection.resolve("CLAUDE.md")))
          .contains("# Our platform")
          .contains("Ask before deploying.")
          .contains("<!-- prethink-context -->");
    }

    @Test
    void namesRepositoriesExplicitlyWhenAsked(@TempDir Path collection, @TempDir Path dataTables) throws IOException {
        analyze(dataTables, "acme/orders-service", "orders-service",
          new UpdateOrganizationalPrethinkContext(collection.toString(), "monorepo/orders", null, null, null));

        assertThat(read(collection.resolve(".moderne/context/service-endpoints.csv")))
          .as("an explicit repository name wins over the git remote")
          .contains("monorepo/orders,")
          .doesNotContain("acme/orders-service,");
        assertThat(collection.resolve(".moderne/context/architecture/monorepo/orders.json")).exists();
    }

    @Test
    void fallsBackToTheProjectNameWithoutAGitRemote(@TempDir Path collection, @TempDir Path dataTables) throws IOException {
        analyze(collection, dataTables, null, "orders-service");

        assertThat(read(collection.resolve(".moderne/context/service-endpoints.csv")))
          .contains("orders-service,endpoint:");
    }

    @Test
    void writesEveryRequestedAgentConfigFile(@TempDir Path collection, @TempDir Path dataTables) throws IOException {
        analyze(dataTables, "acme/orders-service", "orders-service",
          new UpdateOrganizationalPrethinkContext(
            collection.toString(), null, null, List.of("AGENTS.md", ".github/copilot-instructions.md"), null));

        assertThat(read(collection.resolve("AGENTS.md"))).contains("<!-- prethink-context -->");
        assertThat(read(collection.resolve(".github/copilot-instructions.md"))).contains("<!-- prethink-context -->");
        assertThat(collection.resolve("CLAUDE.md")).doesNotExist();
    }

    /**
     * A combined table outlives any single version of the data table that feeds
     * it, so columns are matched by name: a table that gains, loses, or reorders
     * a column must not scramble the rows other repositories already contributed.
     */
    @Test
    void survivesASchemaChangeBetweenExports(@TempDir Path collection) throws IOException {
        Path csv = collection.resolve("service-endpoints.csv");
        Files.write(csv, ("Repository,Path,HTTP method,Retired column\n" +
                          "acme/orders-service,/api/orders,GET,gone\n").getBytes(UTF_8));

        OrganizationalContext.mergeCsv(csv, "acme/shipping-service",
          new String[]{"Repository", "HTTP method", "Path", "Framework"}, writer -> {
              writer.writeRow("acme/shipping-service", "POST", "/api/shipments", "Spring");
              return 1;
          });

        assertThat(read(csv))
          .contains("Repository,HTTP method,Path,Framework")
          .contains("acme/orders-service,GET,/api/orders,")
          .contains("acme/shipping-service,POST,/api/shipments,Spring")
          .doesNotContain("gone");
    }

    @Test
    void keepsColumnsAlignedWhenATableHasARepositoryColumnOfItsOwn(@TempDir Path collection) throws IOException {
        Path csv = collection.resolve("repository-owners.csv");
        String[] headers = {"Repository", "Repository", "Owner"};

        OrganizationalContext.mergeCsv(csv, "acme/orders-service", headers, writer -> {
            writer.writeRow("acme/orders-service", "internal-orders", "payments-team");
            return 1;
        });
        OrganizationalContext.mergeCsv(csv, "acme/shipping-service", headers, writer -> {
            writer.writeRow("acme/shipping-service", "internal-shipping", "logistics-team");
            return 1;
        });

        assertThat(read(csv))
          .contains("acme/orders-service,internal-orders,payments-team")
          .contains("acme/shipping-service,internal-shipping,logistics-team");
    }

    /**
     * The composite configures no list of data tables, so a context that only a
     * different Prethink composite produces is exported without this module
     * knowing the table exists.
     */
    @Test
    void exportsWhateverTheCompositeDiscovered(@TempDir Path collection, @TempDir Path dataTables) throws IOException {
        run(dataTables, "acme/orders-service",
          List.of(new PopulateArchitecture("orders-service"), new PopulateTestMapping("OrdersControllerTest")),
          new UpdateOrganizationalPrethinkContext(collection.toString(), null, null, null, null));

        assertThat(read(collection.resolve(".moderne/context/test-mapping.csv")))
          .as("a data table outside any hardcoded list is still combined")
          .contains("acme/orders-service,src/test/java/com/example/OrdersControllerTest.java");
        assertThat(read(collection.resolve(".moderne/context/codebase-context.md")))
          .contains("[`.moderne/context/test-mapping.csv`](.moderne/context/test-mapping.csv)")
          .contains("| Test method | ");
        assertThat(read(collection.resolve(".moderne/context/tables.csv")))
          .contains("test-mapping.csv,org.openrewrite.prethink.table.TestMapping,Test mapping");
    }

    /**
     * Every recipe artifact contributing to a run is loaded by its own classloader,
     * so most of what a composite discovers is declared by classes this module
     * cannot resolve by name. A table has to be described from the instance the
     * store holds rather than looked up, or the run exports only its own tables.
     */
    @Test
    void combinesTablesDeclaredByAnotherRecipeArtifact(@TempDir Path collection, @TempDir Path dataTables,
                                                       @TempDir Path artifact) throws Exception {
        run(dataTables, "acme/orders-service",
          List.of(new PopulateArchitecture("orders-service"), new PopulateForeignTable(foreignTable(artifact))),
          new UpdateOrganizationalPrethinkContext(collection.toString(), null, null, null, null));

        assertThat(read(collection.resolve(".moderne/context/foreign-metrics.csv")))
          .as("a table only another artifact's classloader can resolve is still combined")
          .contains("Repository,Class,Score")
          .contains("acme/orders-service,com.example.Controller,42");
        assertThat(read(collection.resolve(".moderne/context/tables.csv")))
          .contains("foreign-metrics.csv,foreign.ForeignMetrics,Foreign metrics");
        assertThat(read(collection.resolve(".moderne/context/codebase-context.md")))
          .as("and is documented from the columns that foreign class declares")
          .contains("| Class | The class measured |");
    }

    /**
     * A composite groups its tables into named contexts, and a collection is only
     * as navigable as the per-repository context it combines, so it lays those
     * same contexts out rather than presenting one undifferentiated pile.
     */
    @Test
    void groupsTablesIntoTheContextsTheCompositeDeclared(@TempDir Path collection, @TempDir Path dataTables) throws IOException {
        run(dataTables, "acme/orders-service",
          List.of(new PopulateArchitecture("orders-service"), new PopulateTestMapping("OrdersControllerTest"),
            new ExportContext("Test Coverage", "Maps tests to implementations",
              "Which tests cover which implementation methods.",
              List.of("org.openrewrite.prethink.table.TestMapping"))),
          new UpdateOrganizationalPrethinkContext(collection.toString(), null, null, null, null));

        assertThat(read(collection.resolve(".moderne/context/test-coverage.md")))
          .as("a declared context is documented under its own name")
          .contains("# Test Coverage")
          .contains("Maps tests to implementations")
          .contains("[`.moderne/context/test-mapping.csv`](.moderne/context/test-mapping.csv)");
        assertThat(read(collection.resolve(".moderne/context/codebase-context.md")))
          .as("and is no longer described by the umbrella, which keeps what no context claimed")
          .doesNotContain("test-mapping.csv")
          .contains("service-endpoints.csv");

        assertThat(read(collection.resolve("CLAUDE.md")))
          .as("so the agent is pointed at each context, not at one entry covering everything")
          .contains("| Test Coverage |")
          .contains("(.moderne/context/test-coverage.md)")
          .contains("| Codebase Context |");

        assertThat(read(collection.resolve(".moderne/context/contexts.csv")))
          .contains("Test Coverage,test-coverage.md,Maps tests to implementations");
        assertThat(read(collection.resolve(".moderne/context/tables.csv")))
          .contains("test-mapping.csv,org.openrewrite.prethink.table.TestMapping,Test mapping")
          .contains(",Test Coverage");
    }

    /**
     * The catalog is what makes removal exact: a context is this recipe's to
     * delete because it is listed there, which is what keeps the sweep away from
     * markdown the other organizational recipes write into the same directory.
     */
    @Test
    void forgetsAContextOnceNoTableBacksIt(@TempDir Path collection, @TempDir Path dataTables) throws IOException {
        run(dataTables.resolve("with-coverage"), "acme/orders-service",
          List.of(new PopulateArchitecture("orders-service"), new PopulateTestMapping("OrdersControllerTest"),
            new ExportContext("Test Coverage", "Maps tests to implementations",
              "Which tests cover which implementation methods.",
              List.of("org.openrewrite.prethink.table.TestMapping"))),
          new UpdateOrganizationalPrethinkContext(collection.toString(), null, null, null, null));
        assertThat(collection.resolve(".moderne/context/test-coverage.md")).exists();

        // The repository is analyzed again, this time by a composite without the
        // test-coverage recipes at all.
        analyze(collection, dataTables.resolve("without-coverage"), "acme/orders-service", "orders-service");

        assertThat(collection.resolve(".moderne/context/test-coverage.md"))
          .as("a context nothing backs any more is removed with its markdown")
          .doesNotExist();
        assertThat(collection.resolve(".moderne/context/contexts.csv"))
          .as("and with no contexts left to catalog, the catalog goes with them")
          .doesNotExist();
        assertThat(collection.resolve(".moderne/context/calm-architecture.md"))
          .as("markdown another recipe owns is left alone by that sweep")
          .exists();
    }

    @Test
    void excludesDataTablesOnRequest(@TempDir Path collection, @TempDir Path dataTables) throws IOException {
        run(dataTables, "acme/orders-service",
          List.of(new PopulateArchitecture("orders-service"), new PopulateTestMapping("OrdersControllerTest")),
          new UpdateOrganizationalPrethinkContext(collection.toString(), null,
            List.of("org.openrewrite.prethink.table.TestMapping"), null, null));

        assertThat(collection.resolve(".moderne/context/test-mapping.csv")).doesNotExist();
        assertThat(collection.resolve(".moderne/context/service-endpoints.csv")).exists();
    }

    /**
     * With the table set discovered per run rather than configured, a repository
     * that produces fewer tables than another must not erase the description of
     * the tables it happens not to contribute to.
     */
    @Test
    void keepsDescribingTablesOtherRepositoriesContribute(@TempDir Path collection, @TempDir Path dataTables) throws IOException {
        analyze(collection, dataTables.resolve("orders"), "acme/orders-service", "orders-service");

        // A library repository: project metadata, but no endpoints at all.
        run(dataTables.resolve("commons"), "acme/commons",
          List.of(new PopulateArchitecture("commons", false)),
          new UpdateOrganizationalPrethinkContext(collection.toString(), null, null, null, null));

        assertThat(read(collection.resolve(".moderne/context/service-endpoints.csv")))
          .as("the narrower repository leaves the other's rows alone")
          .contains("acme/orders-service");
        assertThat(read(collection.resolve(".moderne/context/codebase-context.md")))
          .as("and still describes the table it contributed nothing to")
          .contains("[`.moderne/context/service-endpoints.csv`](.moderne/context/service-endpoints.csv)")
          .contains("| HTTP method | ");
    }

    /**
     * The sweep that keeps a discovered table set honest: a table this repository
     * stops producing is not in the run's store at all, so its stale rows can only
     * be found by looking at the collection.
     */
    @Test
    void purgesTablesThisRepositoryStoppedProducing(@TempDir Path collection, @TempDir Path dataTables) throws IOException {
        run(dataTables.resolve("orders-1"), "acme/orders-service",
          List.of(new PopulateArchitecture("orders-service"), new PopulateTestMapping("OrdersControllerTest")),
          new UpdateOrganizationalPrethinkContext(collection.toString(), null, null, null, null));
        run(dataTables.resolve("shipping"), "acme/shipping-service",
          List.of(new PopulateArchitecture("shipping-service"), new PopulateTestMapping("ShippingControllerTest")),
          new UpdateOrganizationalPrethinkContext(collection.toString(), null, null, null, null));

        // The orders service is analyzed again, this time without the test-coverage recipe.
        analyze(collection, dataTables.resolve("orders-2"), "acme/orders-service", "orders-service");

        String testMapping = read(collection.resolve(".moderne/context/test-mapping.csv"));
        assertThat(testMapping)
          .as("rows from a table the run no longer discovers are swept out")
          .doesNotContain("acme/orders-service");
        assertThat(testMapping).contains("acme/shipping-service");
        assertThat(read(collection.resolve(".moderne/context/service-endpoints.csv")))
          .as("the tables it still produces are refreshed as usual")
          .contains("acme/orders-service");
    }

    @Test
    void forgetsATableOnceNoRepositoryProducesIt(@TempDir Path collection, @TempDir Path dataTables) throws IOException {
        run(dataTables.resolve("orders-1"), "acme/orders-service",
          List.of(new PopulateArchitecture("orders-service"), new PopulateTestMapping("OrdersControllerTest")),
          new UpdateOrganizationalPrethinkContext(collection.toString(), null, null, null, null));

        analyze(collection, dataTables.resolve("orders-2"), "acme/orders-service", "orders-service");

        assertThat(collection.resolve(".moderne/context/test-mapping.csv"))
          .as("a table left with no rows at all is removed rather than left as headers")
          .doesNotExist();
        assertThat(read(collection.resolve(".moderne/context/tables.csv")))
          .doesNotContain("test-mapping.csv");
        assertThat(read(collection.resolve(".moderne/context/codebase-context.md")))
          .doesNotContain("test-mapping.csv");
    }

    /**
     * Every repository in a `mod run` is analyzed by one process, so there is no
     * per-repository directory a recipe could default to -- only the directory
     * the run itself was started in, which is the collection root when the run is
     * started from it.
     */
    @Test
    void collectsIntoTheDirectoryTheRunStartedInWhenNoTargetIsNamed() {
        assertThat(new UpdateOrganizationalPrethinkContext(null, null, null, null, null)
          .getDescriptor().getOptions())
          .filteredOn(option -> "targetDirectory".equals(option.getName()))
          .singleElement()
          .satisfies(option -> assertThat(option.isRequired()).isFalse());

        assertThat(OrganizationalContext.targetPath(null))
          .isEqualTo(Paths.get("").toAbsolutePath().normalize());
    }

    @Test
    void repositoryNamesAreSafeToUseAsPaths() {
        assertThat(OrganizationalContext.sanitize("acme/orders-service")).isEqualTo("acme/orders-service");
        assertThat(OrganizationalContext.sanitize("git@github.com:acme/orders.git"))
          .isEqualTo("git-github.com-acme/orders.git");
        assertThat(OrganizationalContext.sanitize("../../etc/passwd")).isEqualTo("etc/passwd");
        assertThat(OrganizationalContext.sanitize("/")).isEqualTo(OrganizationalContext.UNKNOWN_REPOSITORY);
    }

    private RecipeRun analyze(Path collection, Path dataTables, @Nullable String origin, @Nullable String artifactId) {
        return analyze(dataTables, origin, artifactId,
          new UpdateOrganizationalPrethinkContext(collection.toString(), null, null, null, null));
    }

    private RecipeRun analyze(Path dataTables, @Nullable String origin, @Nullable String artifactId, Recipe organizational) {
        return run(dataTables, origin, List.of(new PopulateArchitecture(artifactId)), organizational);
    }

    /**
     * One repository's analysis: whatever discovery recipes the composite happens
     * to contain, followed by the organizational export.
     */
    private RecipeRun run(Path dataTables, @Nullable String origin, List<Recipe> discovery, Recipe organizational) {
        List<Recipe> recipes = new ArrayList<>(discovery);
        recipes.add(organizational);
        return new CompositeRecipe(recipes)
          .run(new InMemoryLargeSourceSet(sources(origin)), executionContext(dataTables), 3, 1);
    }

    private ExecutionContext executionContext(Path dataTables) {
        ExecutionContext ctx = new InMemoryExecutionContext();
        DataTableExecutionContextView.view(ctx).setDataTableStore(new CsvDataTableStore(dataTables));
        return ctx;
    }

    /**
     * One source file, optionally carrying the git remote a repository name is
     * derived from.
     */
    private List<SourceFile> sources(@Nullable String origin) {
        PlainText source = PlainText.builder()
          .sourcePath(Path.of("src/main/java/com/example/Controller.java"))
          .text("package com.example;\npublic class Controller {}")
          .build();
        if (origin != null) {
            source = source.withMarkers(Markers.build(List.of(new GitProvenance(
              Tree.randomId(), "https://github.com/" + origin + ".git", "main", "abc1234", null, null, null))));
        }
        return List.of(source);
    }

    /**
     * A data table declared outside this module entirely: compiled into its own
     * directory and loaded by its own classloader, the way the CLI loads each
     * recipe artifact, so that its class is unresolvable by name from here.
     */
    private Class<?> foreignTable(Path artifact) throws Exception {
        Path source = artifact.resolve("ForeignMetrics.java");
        Files.writeString(source, """
          package foreign;

          import org.openrewrite.Column;
          import org.openrewrite.DataTable;
          import org.openrewrite.Recipe;

          public class ForeignMetrics extends DataTable<ForeignMetrics.Row> {
              public ForeignMetrics(Recipe recipe) {
                  super(recipe, "Foreign metrics", "Metrics declared by another recipe artifact.");
              }

              public record Row(
                      @Column(displayName = "Class", description = "The class measured")
                      String className,

                      @Column(displayName = "Score", description = "The score of the class")
                      int score) {
              }
          }
          """);

        Path classes = artifact.resolve("classes");
        int compiled = ToolProvider.getSystemJavaCompiler().run(null, null, null,
          "-cp", System.getProperty("java.class.path"), "-d", classes.toString(), source.toString());
        assertThat(compiled).as("the foreign artifact compiles").isZero();

        // Parented to the loader that has rewrite-core, so DataTable is the same type on
        // both sides, exactly as it is for a recipe artifact loaded by the CLI.
        URLClassLoader artifactLoader = new URLClassLoader(
          new URL[]{classes.toUri().toURL()}, DataTable.class.getClassLoader());
        return artifactLoader.loadClass("foreign.ForeignMetrics");
    }

    private String read(Path file) throws IOException {
        assertThat(file).exists();
        return new String(Files.readAllBytes(file), UTF_8);
    }

    private int occurrences(String content, String needle) {
        int count = 0;
        for (int at = content.indexOf(needle); at >= 0; at = content.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    /**
     * Populates the architecture data tables the way the production discovery
     * recipes do, with rows distinctive enough to tell one repository's
     * contribution from another's. A null artifact id populates nothing, standing
     * in for a repository where no architecture is discovered.
     */
    @Getter
    public static class PopulateArchitecture extends Recipe {
        transient ServiceEndpoints serviceEndpoints = new ServiceEndpoints(this).withGroup("architecture");
        transient ProjectMetadata projectMetadata = new ProjectMetadata(this).withGroup("architecture");

        private final @Nullable String artifactId;
        private final boolean endpoints;

        public PopulateArchitecture(@Nullable String artifactId) {
            this(artifactId, true);
        }

        public PopulateArchitecture(@Nullable String artifactId, boolean endpoints) {
            this.artifactId = artifactId;
            this.endpoints = endpoints;
        }

        String displayName = "Populate architecture tables";
        String description = "Populates architecture data tables for one repository.";

        @Override
        public TreeVisitor<?, ExecutionContext> getVisitor() {
            return new TreeVisitor<>() {
                @Override
                public Tree visit(Tree tree, ExecutionContext ctx) {
                    if (artifactId != null && tree instanceof SourceFile sf &&
                        sf.getSourcePath().toString().endsWith("Controller.java")) {
                        String controller = "com.example." + camelCase(artifactId) + "Controller";
                        projectMetadata.insertRow(ctx, new ProjectMetadata.Row(
                          "pom.xml", artifactId, "com.example", artifactId,
                          "The " + artifactId, "1.0.0", null, null));
                        if (endpoints) {
                            serviceEndpoints.insertRow(ctx, new ServiceEndpoints.Row(
                              "endpoint:" + controller + "#list()",
                              "src/main/java/com/example/Controller.java",
                              controller, "list", "GET", "/api/" + artifactId,
                              "application/json", "", "Spring", "list()"));
                        }
                    }
                    return tree;
                }
            };
        }

        private String camelCase(String kebab) {
            StringBuilder camel = new StringBuilder();
            for (String segment : kebab.split("-")) {
                if (!segment.isEmpty()) {
                    camel.append(Character.toUpperCase(segment.charAt(0))).append(segment.substring(1));
                }
            }
            return camel.toString();
        }
    }

    /**
     * Populates a data table whose class this module has no compile-time access
     * to, standing in for the tables every other recipe artifact declares.
     */
    @Getter
    public static class PopulateForeignTable extends Recipe {
        private final transient DataTable<Object> metrics;
        private final transient Constructor<?> row;

        @SuppressWarnings("unchecked")
        public PopulateForeignTable(Class<?> tableClass) throws Exception {
            metrics = (DataTable<Object>) tableClass.getConstructor(Recipe.class).newInstance(this);
            row = Class.forName(tableClass.getName() + "$Row", false, tableClass.getClassLoader())
              .getConstructor(String.class, int.class);
        }

        String displayName = "Populate a foreign data table";
        String description = "Populates a data table declared by another recipe artifact.";

        @Override
        public TreeVisitor<?, ExecutionContext> getVisitor() {
            return new TreeVisitor<>() {
                @Override
                public Tree visit(Tree tree, ExecutionContext ctx) {
                    if (tree instanceof SourceFile sf && sf.getSourcePath().toString().endsWith("Controller.java")) {
                        try {
                            metrics.insertRow(ctx, row.newInstance("com.example.Controller", 42));
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    return tree;
                }
            };
        }
    }

    /**
     * Populates a data table that was never in the composite's hardcoded list,
     * standing in for the contexts a different Prethink composite discovers.
     */
    @Getter
    public static class PopulateTestMapping extends Recipe {
        transient TestMapping testMapping = new TestMapping(this).withGroup("test-coverage");

        private final String testClass;

        public PopulateTestMapping(String testClass) {
            this.testClass = testClass;
        }

        String displayName = "Populate test mapping";
        String description = "Populates the test mapping data table for one repository.";

        @Override
        public TreeVisitor<?, ExecutionContext> getVisitor() {
            return new TreeVisitor<>() {
                @Override
                public Tree visit(Tree tree, ExecutionContext ctx) {
                    if (tree instanceof SourceFile sf && sf.getSourcePath().toString().endsWith("Controller.java")) {
                        testMapping.insertRow(ctx, new TestMapping.Row(
                          "src/test/java/com/example/" + testClass + ".java",
                          "com.example." + testClass, "list()",
                          "src/main/java/com/example/Controller.java",
                          "com.example.Controller", "list()", null, null));
                    }
                    return tree;
                }
            };
        }
    }
}
