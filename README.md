<p align="center">
  <a href="https://docs.openrewrite.org">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://github.com/openrewrite/rewrite/raw/main/doc/logo-oss-dark.svg">
      <source media="(prefers-color-scheme: light)" srcset="https://github.com/openrewrite/rewrite/raw/main/doc/logo-oss-light.svg">
      <img alt="OpenRewrite Logo" src="https://github.com/openrewrite/rewrite/raw/main/doc/logo-oss-light.svg" width='600px'>
    </picture>
  </a>
</p>

<div align="center">
  <h1>rewrite-prethink</h1>
</div>

<div align="center">

<!-- Keep the gap above this line, otherwise they won't render correctly! -->
[![ci](https://github.com/openrewrite/rewrite-prethink/actions/workflows/ci.yml/badge.svg)](https://github.com/openrewrite/rewrite-prethink/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/org.openrewrite.recipe/rewrite-prethink.svg)](https://mvnrepository.com/artifact/org.openrewrite.recipe/rewrite-prethink)
[![Revved up by Develocity](https://img.shields.io/badge/Revved%20up%20by-Develocity-06A0CE?logo=Gradle&labelColor=02303A)](https://ge.openrewrite.org/scans)
[![Contributing Guide](https://img.shields.io/badge/Contributing-Guide-informational)](https://github.com/openrewrite/.github/blob/main/CONTRIBUTING.md)
</div>

### What is this?

This project implements a [Rewrite module](https://github.com/openrewrite/rewrite) that generates trusted context for LLM coding agents like Claude Code, Cursor, and GitHub Copilot.

**Prethink** pre-generates knowledge about your codebase and stores it in `.moderne/context/` as CSV files with accompanying markdown documentation. This context helps coding agents understand your codebase without consuming precious context window space reading source files.

## Features

- **FINOS CALM Architecture**: Generate architecture diagrams following the [FINOS CALM](https://calm.finos.org/) (Common Architecture Language Model) standard
- **Context Export**: Export data tables to CSV with markdown documentation for agent consumption
- **Agent Configuration**: Automatically update coding agent configuration files to reference generated context

## CALM Architecture

Prethink generates FINOS CALM architecture diagrams by analyzing your codebase for:

- **Services**: REST controllers (Spring MVC, JAX-RS, Micronaut, Quarkus)
- **Databases**: JPA entities, Spring Data repositories, JDBC templates
- **External Services**: RestTemplate, WebClient, Feign clients, Apache HttpClient
- **Messaging**: Kafka, RabbitMQ, JMS, Spring Cloud Stream

The generated `calm-architecture.json` follows the CALM schema and can be visualized with CALM-compatible tools.

## Data Tables

Prethink provides data tables for architectural components:

| Table | Description |
|-------|-------------|
| `ServiceEndpoints` | REST API endpoints with HTTP methods and paths |
| `DatabaseConnections` | Database entities and repositories |
| `ExternalServiceCalls` | Outbound HTTP client calls |
| `MessagingConnections` | Message queue producers and consumers |
| `ServerConfiguration` | Server port, protocol, and context path |
| `SecurityConfiguration` | Security settings including CORS and OAuth2 |
| `DataAssets` | Domain entities, DTOs, and records |
| `DeploymentArtifacts` | Dockerfile, Kubernetes, and docker-compose files |
| `ProjectMetadata` | Project coordinates and description |

## Agent Configuration

Prethink automatically updates coding agent configuration files to reference the generated context:

- `CLAUDE.md` - Claude Code
- `.cursorrules` - Cursor
- `.github/copilot-instructions.md` - GitHub Copilot

## Organizational Context

`org.openrewrite.prethink.UpdateOrganizationalPrethinkContext` combines many repositories into a single
collection instead of leaving each one describing only itself. Point it at the same `targetDirectory` for every
repository you analyze — the directory can live outside all of them — and start a coding agent there to reason
across the whole organization:

```
<targetDirectory>/
├── CLAUDE.md                                     agent instructions for the whole collection
└── .moderne/context/
    ├── repositories.csv                          every repository included, with its origin and branch
    ├── tables.csv                                catalog of the combined tables
    ├── service-endpoints.csv                     one combined table per data table, keyed by `Repository`
    ├── codebase-context.md                       schema and usage of the combined tables
    ├── calm-architecture.md                      how to read the per-repository diagrams
    └── architecture/<organization>/<repo>.json   each repository's FINOS CALM architecture
```

Every combined CSV carries a leading `Repository` column identifying where a row came from, so an agent can
scope a question to one system, compare systems, or follow an interaction between them. Re-analyzing a
repository replaces exactly that repository's rows, so the collection can be refreshed one repository at a time.

**It combines whatever your composite discovers.** No list of data tables is configured, so the recipe exports
the tables the run actually produced — architectural discovery, test coverage, code comprehension, or anything
added later — reading each table's own display name, description, and column schema to document it. Use
`excludeDataTables` when a composite contains recipes whose tables aren't context worth keeping, or declare
`ExportOrganizationalContext` directly if you want tables grouped into several named contexts.

The repositories analyzed are left unchanged: the collection is written directly to the filesystem rather than
expressed as source file changes, so there is no diff to review or commit. Use
`UpdatePrethinkContext` instead when you want the context committed alongside the code it describes.

## Usage

This library provides the core Prethink infrastructure. For a complete solution with architectural discovery recipes, see [Moderne Prethink](https://docs.moderne.io/user-documentation/agent-tools/prethink/).

## Contributing

We appreciate all types of contributions. See the [contributing guide](https://github.com/openrewrite/.github/blob/main/CONTRIBUTING.md) for detailed instructions on how to get started.
