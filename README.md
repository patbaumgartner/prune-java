# prune-java

`prune-java` is a conservative Java dead-code analysis tool inspired by [knip](https://knip.dev).

It is designed to help teams safely identify unused production code while accounting for Java ecosystem realities such as reflection, framework conventions, generated code, and external API contracts.

## Philosophy

- Conservative by default: prefer false negatives over false positives
- Shared analysis core across all integrations
- CI-first output and stable exit codes
- Autofix-capable architecture with explicit planning before rewrites
- Extensible design for future source, bytecode, and framework-aware analysis

## Current status

This repository contains the initial foundation and scaffolding:

- Multi-module project setup
- Core analyzer interfaces and data model
- CLI command structure (`check` and `fix`)
- Language server skeleton (`prune-lsp`, stdio transport)
- Gradle plugin skeleton
- Maven plugin skeleton
- Report rendering for terminal, GitHub annotation style, and JSON
- Autofix abstractions (`FixAction`, `FixPlan`, `AutofixEngine`)

Actual unused-symbol detection logic is intentionally not implemented yet.

## Coordinates and packages

- Group: `com.patbaumgartner`
- Base packages: `com.patbaumgartner.prune.*`
- Root project name: `prune-java`

## Module layout

- `prune-core`
  - Analysis configuration and report model
  - Analyzer interface and conservative scaffold implementation
  - Autofix planning abstractions
- `prune-cli`
  - Console entry point for local and CI usage
- `prune-lsp`
  - Language server that publishes `prune-core` findings as editor diagnostics over stdio
- `prune-gradle-plugin`
  - Gradle plugin with `pruneCheck` and `pruneFix` tasks
  - Built by its own Gradle build, not by the Maven reactor (see below)
- `prune-maven-plugin`
  - Maven mojos with `check` and `fix` goals, under the `prune` goal prefix
- `samples/`
  - `maven-sample` and `gradle-sample`: realistic fixture projects with deliberate dead
    code and one deliberately unused dependency each, plus an `expected-findings.txt`
    ground-truth manifest (see [samples/README.md](samples/README.md))

## Building

The repository contains two builds:

- a **Maven reactor at the root** for `prune-core`, `prune-cli`, `prune-lsp`, and `prune-maven-plugin`
- a **standalone Gradle build under `prune-gradle-plugin/`**, because a Gradle plugin
  needs the real Gradle API and Maven cannot supply it

Requires JDK 21 or later; all modules compile to Java 17 bytecode.

```bash
./mvnw install                                    # core, CLI, LSP, Maven plugin
cd prune-gradle-plugin && ./gradlew build         # Gradle plugin
```

The Gradle build resolves `prune-core` from the local repository, so `./mvnw install`
must run first — `./mvnw verify` is not enough.

`./mvnw install` also runs the Maven plugin's integration tests, which invoke the packaged
goals against `samples/maven-sample` in a real Maven process. Skip them with
`-Dinvoker.skip=true`. The Gradle build runs the equivalent TestKit check against
`samples/gradle-sample`. Neither resolves the samples' own dependencies, so both stay
offline-safe.

The Maven wrapper pins Maven 3.9.11 and the Gradle wrapper pins Gradle 9.7.1; both
verify a distribution checksum before use.

## CLI and CI usage direction

Current CLI interface, after `./mvnw package`:

```bash
java -jar prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar check
java -jar prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar check --ci
java -jar prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar check --format=json
java -jar prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar fix
```

The Maven plugin exposes the same scaffold as Maven goals, once `./mvnw install` has
published it to the local repository:

```bash
mvn com.patbaumgartner:prune-maven-plugin:0.1.0-SNAPSHOT:check
mvn com.patbaumgartner:prune-maven-plugin:0.1.0-SNAPSHOT:fix
```

Output modes:

- `terminal` (default local output)
- `github` (annotation-friendly output for CI)
- `json` (machine-readable output)

Exit code behavior scaffold:

- `check`: non-zero when unused-code issues are reported
- `fix`: currently always `0` in scaffold mode (real fix result codes will be introduced with rewrite support)
- `2`: usage error (unknown command, unknown option, or unsupported `--format`)

## Language server

`prune-lsp` is a language server built on [lsp4j](https://github.com/eclipse-lsp4j/lsp4j)
that speaks the protocol over stdin/stdout. Point an editor's generic LSP client at:

```bash
java -jar prune-lsp/target/prune-lsp-0.1.0-SNAPSHOT.jar
```

On `initialized`, on every `textDocument/didSave`, and on `workspace/didChangeWatchedFiles`
it runs the same `UnusedCodeAnalyzer` the CLI uses against the first workspace folder
(falling back to `rootUri`) and publishes each `AnalysisIssue` as a diagnostic with source
`prune-java`, the `IssueType` as code, and the `Unnecessary` tag so editors fade the symbol.
The analyzer summary goes to `window/logMessage`. Because detection is still a scaffold, the
server currently publishes no diagnostics — only the log line.

Diagnostics anchor at the line and column parsed from a trailing `:line[:column]` suffix on
`AnalysisIssue.location`, and at `0:0` when the location is a bare path.

Still to come, on top of the same `prune-core` results:

- code actions derived from `FixPlan` for visibility reduction and safe removals
- incremental re-analysis scoped to the saved file instead of the whole project
- consistent results between local IDE, CLI, and CI pipelines once detection lands
