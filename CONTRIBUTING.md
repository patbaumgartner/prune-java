# Contributing

Thanks for taking the time. This is a small project, so the process is short.

## Build

JDK 21 or later is the only prerequisite; the wrappers take care of Maven and Gradle.

```bash
./mvnw install                                   # core, CLI, language server, Maven plugin + its integration test
cd prune-gradle-plugin && ./gradlew build        # the Gradle plugin, against the prune-core you just installed
```

There are two builds because a Gradle plugin needs the real Gradle API, which Maven cannot
supply. The Gradle build resolves `prune-core` from your local repository, so `./mvnw install`
(not `verify`) has to run first, and again after every change to `prune-core`. CI runs exactly
these two commands on JDK 21 and 25, the Maven half once more under Maven 4, and rebuilds every
published jar twice to prove the build is reproducible.

To try a change against the fixture projects, see
[samples/README.md](samples/README.md#running-the-tools-by-hand).

## Design constraints

A few rules shape most of the code. Please keep them; [AGENTS.md](AGENTS.md) is written for
coding agents but is the most complete statement of them.

**1. Conservative, always.** A finding that names live code is a defect, because `fix` deletes
what `check` reports. Every guard in the analyzer exists for a concrete false positive
(annotations, `main`, string literals, serialization, Javadoc on public types, `module-info`,
Lombok logging, runtime-only dependencies). Removing one needs a test proving the false
positive is gone, not just a passing sample; a missed finding is always the acceptable failure.

**2. `prune-core` has zero runtime dependencies.** The lexer, the structural parser, the pom and
Gradle readers, and the JSON writer are hand-rolled on purpose. Do not add Jackson, Gson, or a
parser library; do not add picocli to the CLI. The language server depends on lsp4j because it
*is* the protocol, and on nothing else.

**3. Adapters stay thin.** The CLI, the language server, and the two build plugins each build an
`AnalysisConfig`, call the analyzer, optionally hand the report to `SourceAutofixEngine`, and
render. Anything reusable belongs in `prune-core`, which must never learn about Maven, Gradle, a
CLI framework, or lsp4j.

**4. The samples are ground truth.** `samples/*/expected-findings.txt` lists exactly what the
analyzer must report and what it must never report, and the tests assert set equality. When a
rule changes, the manifests and the README table change in the same commit. Never edit a
manifest to match whatever the analyzer happens to emit, and never make a sample reference
prune-java.

## Tests

Every behavioural change needs a test that fails without it. Conventions are in
[.github/instructions/tests.instructions.md](.github/instructions/tests.instructions.md): JUnit 5,
no mocking library, hand-built seams for the entry points, exact-string assertions for the
renderers. The existing suites show the expected shape:

- `ConservativeUnusedCodeAnalyzerTest` — one test per rule and per guard
- `SourceAutofixEngineTest` — every rewrite, including what it refuses to touch
- `SampleProjectFixtureTest` — the samples against their manifests
- `PruneCliApplicationTest`, `PruneMojoTest`, `PruneGradlePluginTest`, `PruneLanguageServerTest`
  — one per adapter, plus the Maven invoker IT and the Gradle TestKit test that drive the real
  plugins against the samples

## Commits and pull requests

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/)
(`fix:`, `feat:`, `docs:`, `ci:`, `refactor:`, `perf:`, `test:`, `build:`) — the release
changelog is generated from them. Explain *why* in the body; the diff already says what.

Keep pull requests focused, make sure both builds pass, and update the README and AGENTS.md
when a documented contract changes (exit codes, output formats, plugin parameters, LSP
behaviour).

By taking part you agree to uphold the [Code of Conduct](CODE_OF_CONDUCT.md).

## Releasing

Releases are cut by pushing a `vX.Y.Z` tag; [release.yml](.github/workflows/release.yml) then
stages the Maven reactor and the Gradle plugin, attests their provenance, and lets JReleaser
sign them, publish them to Maven Central, and create the GitHub release from the commit log.
The version lives once, in the root `pom.xml`; the Gradle build reads it from there.

```bash
./mvnw versions:set -DnewVersion=1.0.0 -DgenerateBackupPoms=false
./mvnw versions:set-property -Dproperty=project.build.outputTimestamp \
    -DnewVersion="$(date -u +%Y-%m-%dT%H:%M:%SZ)" -DgenerateBackupPoms=false
git commit -am "Release 1.0.0" && git tag v1.0.0 && git push --tags
./mvnw versions:set -DnewVersion=1.1.0-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "Prepare 1.1.0-SNAPSHOT" && git push
```

The workflow refuses a tag whose version differs from the POM or whose
`project.build.outputTimestamp` was not bumped since the previous release.

## Reporting security issues

Please do not open a public issue. See [SECURITY.md](SECURITY.md).
