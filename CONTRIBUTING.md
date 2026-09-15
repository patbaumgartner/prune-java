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

## Formatting

Java is laid out by [Spring Java Format](https://github.com/spring-io/spring-javaformat) and
every `pom.xml` by [sortpom](https://github.com/Ekryd/sortpom) (`custom_1` order, sorted
properties). Both are checked in the `validate` phase and by the Gradle `check` task, so an
unformatted file fails the build before anything compiles. To put a change into shape:

```bash
./mvnw spring-javaformat:apply sortpom:sort      # the Maven modules
cd prune-gradle-plugin && ./gradlew format       # the Gradle plugin
```

The formatter wraps `//` comments at 90 columns and never re-joins them, so write comments to
fit; [.editorconfig](.editorconfig) tells editors about the tabs. The samples are formatted the
same way but carry no gate, because they must stay free of any reference to this project.

## Design constraints

A few rules shape most of the code. Please keep them; [AGENTS.md](AGENTS.md) is written for
coding agents but is the most complete statement of them.

**1. Conservative, always.** A finding that names live code is a defect, because `fix` deletes
what `check` reports. Every exception in the analyzer is a `Guard` with a stable id that exists
for a concrete false positive (`entry-point`, `annotation`, `nested-type`, `serialization`,
`native`, `annotated-owner`, `reflective-serialization`, `javadoc`, `module-info`, `literal`,
plus the runtime-only dependency lists). Removing one needs a test proving the false positive is
gone, not just a passing sample; a missed finding is always the acceptable failure. A guard can
only keep a candidate, never add a finding, and that holds for guards plugged in through
`ServiceLoader` too: extend the analyzer by adding a guard, not by weakening one.

**2. `prune-core` has zero runtime dependencies.** The lexer, the structural parser, the pom and
Gradle readers, and the JSON writer are hand-rolled on purpose. Do not add Jackson, Gson, or a
parser library; do not add picocli to the CLI. The language server depends on lsp4j because it
*is* the protocol, and on nothing else.

**3. Adapters stay thin.** The CLI, the language server, and the two build plugins each build an
`AnalysisConfig`, call the analyzer, optionally hand the report to `SourceAutofixEngine` or the
issues to `Baseline.write`, and render. Anything reusable belongs in `prune-core`, which must
never learn about Maven, Gradle, a CLI framework, or lsp4j.

**4. The samples are ground truth.** `samples/*/expected-findings.txt` lists exactly what the
analyzer must report, what it must report only when test references are excluded, what it must
never report, and which guard keeps each framework-owned symbol; the tests assert set equality
on all of it. When a rule or a guard changes, the manifests and the README table change in the
same commit. Never edit a manifest to match whatever the analyzer happens to emit, and never make
a sample reference prune-java.

## Tests

Every behavioural change needs a test that fails without it. Conventions are in
[.github/instructions/tests.instructions.md](.github/instructions/tests.instructions.md): JUnit 5,
no mocking library, hand-built seams for the entry points, exact-string assertions for the
renderers. The existing suites show the expected shape:

- `ConservativeUnusedCodeAnalyzerTest` — one test per rule and per guard, including the guard's
  explanation
- `GuardPipelineTest` — the guard order, `ServiceLoader` discovery, and duplicate-id rejection
- `BaselineTest` — the baseline file grammar, exact-string
- `SourceAutofixEngineTest` — every rewrite, including what it refuses to touch
- `SampleProjectFixtureTest` — every sample against its manifest
- `PruneCliApplicationTest`, `PruneMojoTest`, `PruneGradlePluginTest`, `PruneLanguageServerTest`
  — one per adapter, plus the Maven invoker IT and the Gradle TestKit test that drive the real
  plugins against the samples
- `ParserFuzzerTest` — replays the fuzzing seed corpus and every crash Jazzer ever found
  through `ParserFuzzer`, the invariant checker for all four hand-rolled parsers

The parsers read files this project did not write, so they are fuzzed as well:

```bash
./mvnw -pl prune-core -Pfuzz test-compile exec:exec      # 120 s of Jazzer; -Dfuzz.seconds=600 for longer
```

A crashing input is written next to the seeds in
`prune-core/src/test/resources/.../ParserFuzzerInputs/`, where `ParserFuzzerTest` picks it up on
the next build. Fix the parser, keep the input, and the bug stays fixed.

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
The version lives once, in the root `pom.xml`; the Gradle build reads it from there through
`settings.gradle`.

```bash
./mvnw versions:set -DnewVersion=1.0.0 -DgenerateBackupPoms=false
./mvnw versions:set-property -Dproperty=project.build.outputTimestamp \
    -DnewVersion="$(date -u +%Y-%m-%dT%H:%M:%SZ)" -DgenerateBackupPoms=false
git commit -am "Release 1.0.0" && git tag v1.0.0 && git push --tags
./mvnw versions:set -DnewVersion=1.1.0-SNAPSHOT -DgenerateBackupPoms=false
**5. Every version lives once, in the root `pom.xml`.** The project version, and every plugin and
library version as a `<properties>` entry (`checkstyle.version`, `maven-compiler-plugin.version`,
...). `prune-gradle-plugin/settings.gradle` reads the project version and every version the
Gradle build shares with the reactor from that file, so both builds run the same analyzer and
formatter releases and Dependabot bumps each once; the three Gradle-only plugins are pinned in
`prune-gradle-plugin/gradle.properties`. Do not write a version literal into either build.

git commit -am "Prepare 1.1.0-SNAPSHOT" && git push
```

The workflow refuses a tag whose version differs from the POM or whose
`project.build.outputTimestamp` was not bumped since the previous release.

## Reporting security issues

Please do not open a public issue. See [SECURITY.md](SECURITY.md).
