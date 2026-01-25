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

## Usage

This library provides the core Prethink infrastructure. For a complete solution with architectural discovery recipes, see [Moderne Prethink](https://github.com/moderneinc/rewrite-prethink).

## Contributing

We appreciate all types of contributions. See the [contributing guide](https://github.com/openrewrite/.github/blob/main/CONTRIBUTING.md) for detailed instructions on how to get started.
