# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## About the project

easyTask is an open-source task tracker inspired by Jira, but it must not copy Jira's implementation, UI, naming, or proprietary behavior.

It must support two deployment models from the same core codebase:
- self-hosted installation on a user/server machine
- hosted Cloud version

Performance, maintainability, and simple deployment are core requirements. When proposing architecture, libraries, or infra (auth, storage, multi-tenancy, billing, etc.), favor options that work in both deployment models — avoid baking in cloud-only assumptions (e.g. a hard dependency on a specific managed service) without an equivalent self-hosted path.

## Project state

Currently a freshly generated Spring Boot 4.1.0 / Java 21 skeleton (single `@SpringBootApplication` entry point, no controllers/services/repositories/entities yet). Dependencies are already declared for a typical REST + persistence + auth stack: Spring Web MVC, Spring Data JPA, Spring Security, and PostgreSQL (runtime driver). Lombok is available for boilerplate reduction.

There is no datasource configured yet in `src/main/resources/application.properties` (only `spring.application.name=easyTask` is set) — a PostgreSQL connection (`spring.datasource.*`) will need to be added before JPA repositories/entities can run.

## Commands

Use the Maven wrapper (`mvnw.cmd` on Windows / `./mvnw` in bash) so the build doesn't depend on a locally installed Maven version.

```
mvnw.cmd clean package          # build
mvnw.cmd spring-boot:run        # run the app locally
mvnw.cmd test                   # run full test suite
mvnw.cmd test -Dtest=EasyTaskApplicationTests              # run a single test class
mvnw.cmd test -Dtest=EasyTaskApplicationTests#contextLoads # run a single test method
```

There is no linter/formatter plugin configured in `pom.xml` yet.

## Architecture

Standard Maven/Spring Boot layout, package root `com.easytask`:
- `src/main/java/com/easytask/EasyTaskApplication.java` — application entry point.
- `src/main/resources/application.properties` — configuration.
- `src/test/java/com/easytask/` — tests (JUnit 5 via `spring-boot-starter-test` equivalents: `-webmvc-test`, `-data-jpa-test`, `-security-test`).

As controllers, services, repositories, and entities are added, follow the package-by-layer convention implied by the starter (`com.easytask.<layer>`) unless the user establishes a different convention.

Because Spring Security is on the classpath, any new HTTP endpoint is secured by default until a `SecurityFilterChain`/`SecurityConfig` bean is introduced — expect 401/403 on new endpoints until security config is added.

## Subagents

- `database-architect` (`.claude/agents/database-architect.md`) — consult/delegate to this agent for any task touching schema design, PostgreSQL tables/indexes/constraints, Flyway/Liquibase migrations, JPA entities/repositories, transactions, locking, or query performance.
- `backend-architect` (`.claude/agents/backend-architect.md`) — consult/delegate for system-level architecture: package/module structure, REST API design, auth/multi-tenancy architecture, self-hosted-vs-Cloud configuration strategy, new dependency choices.
- `code-reviewer` (`.claude/agents/code-reviewer.md`) — consult/delegate for reviewing Java/Spring code changes (controllers, services, DTOs, config) for correctness, security, readability, and performance before merging.

All three agents are advisory-only (Read/Grep/Glob/Bash, no edits) and cross-reference each other to avoid overlapping scope.