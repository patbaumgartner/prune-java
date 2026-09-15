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
these two commands on JDK 21 and 25, the Maven half once more under Maven 4, rebuilds every
published jar twice to prove the build is reproducible, and then lets both plugins analyze this
repository (see [Eating our own dog food](#eating-our-own-dog-food)).

A single module builds on its own — `./mvnw -pl prune-core test -Dtest=ReportRendererTest` is
the fastest loop — and on newer JDKs Maven prints `sun.misc.Unsafe` warnings to stderr that
`./mvnw ... 2>&1 | grep -v '^WARNING'` filters out.

To try a change against the fixture projects, see
[samples/README.md](samples/README.md#running-the-tools-by-hand).

## Formatting

Java is laid out by [Spring Java Format](https://github.com/spring-io/spring-javaformat) and
every `pom.xml` by [sortpom](https://github.com/Ekryd/sortpom) (`custom_1` order, sorted
properties, and the layout itself: the check is strict, so a re-indented pom fails it). Both are
checked in the `validate` phase and by the Gradle `check` task, so an
unformatted file fails the build before anything compiles. To put a change into shape:

```bash
./mvnw spring-javaformat:apply sortpom:sort      # the Maven modules
cd prune-gradle-plugin && ./gradlew format       # the Gradle plugin
```

The formatter wraps `//` comments at 90 columns and never re-joins them, so write comments to
fit; [.editorconfig](.editorconfig) tells editors about the tabs. The samples are formatted the
same way but carry no gate, because they must stay free of any reference to this project.

## Static analysis

Both builds run the same analyzers from the same rule files under [config/](config/), and every
finding fails the build — there is no report-only mode, so a green build means a clean one:

| Tool | When | Rules |
| --- | --- | --- |
| [Checkstyle](https://checkstyle.org/) | Maven `validate`, Gradle `check` | [config/checkstyle/checkstyle.xml](config/checkstyle/checkstyle.xml): imports, naming, class design, `equals`/`hashCode` — what the formatter cannot see |
| [Error Prone](https://errorprone.info/) with [NullAway](https://github.com/uber/NullAway) | inside javac, on every compile | Error Prone's default checks as errors; NullAway treats every `com.patbaumgartner` package as null-safe, with `@Nullable` from [JSpecify](https://jspecify.dev/) for the exceptions |
| [PMD](https://pmd.github.io/) and CPD | Maven `verify`, Gradle `check` | [config/pmd/ruleset.xml](config/pmd/ruleset.xml); CPD fails on 60 duplicated tokens and runs in the Maven build only |
| [SpotBugs](https://spotbugs.github.io/) with [Find Security Bugs](https://find-sec-bugs.github.io/) | Maven `verify`, Gradle `check` | maximum effort, low threshold, main and test classes; the few exclusions live in [config/spotbugs/exclude.xml](config/spotbugs/exclude.xml) |
| [forbidden-apis](https://github.com/policeman-tools/forbidden-apis) | Maven `verify`, Gradle `check` | the bundled `jdk-unsafe`, `jdk-deprecated`, `jdk-non-portable`, and `jdk-reflection` sets plus [config/forbidden-apis/signatures.txt](config/forbidden-apis/signatures.txt) |
| Maven [enforcer](https://maven.apache.org/enforcer/) | Maven `validate` | JDK 21+, Maven 3.9+, every plugin pinned, converged dependencies, no JUnit 4 or legacy logging artifacts, no dependency compiled above Java 17 |

Fix the code, not the rule. When a rule really is wrong for this codebase, change the rule file
— both builds read it — in the same commit and say why in the body; an inline suppression needs
the same justification next to it. For a quick inner loop the Maven analyzers can be skipped
with `-Dcheckstyle.skip -Dpmd.skip -Dspotbugs.skip -Dforbiddenapis.skip -Denforcer.skip
-Dspring-javaformat.skip`; CI never skips any of them.

Two toolchain details are easy to trip over. Error Prone runs inside javac, so
[.mvn/jvm.config](.mvn/jvm.config) opens the `jdk.compiler` internals it needs, and the compiler
arguments carry `-XDaddTypeAnnotationsToSymbol=true`, which a JDK 21 javac needs to expose type
annotations to the checks (later JDKs accept it). In the Gradle build, spring-javaformat's
`checkFormat` task reads `Task.project` while running and is declared
`notCompatibleWithConfigurationCache`, so a machine that enables the configuration cache in
`~/.gradle/gradle.properties` still builds; the repository itself does not enable it.

## Eating our own dog food

prune-java is analyzed by prune-java. After `./mvnw install`, the reactor runs the Maven plugin
it just built over every module, and the Gradle plugin analyzes its own build through
[prune-java.init.gradle](prune-gradle-plugin/prune-java.init.gradle); CI does both on every push,
with `failOnIssues` and `ignoreFailures` at their defaults, so a private method that lost its
last caller fails the build before a reviewer sees it:

```bash
./mvnw -B -ntp com.patbaumgartner:prune-maven-plugin:check          # every reactor module, with the plugin just built
cd prune-gradle-plugin && ./gradlew publishToMavenLocal -x test && \
    ./gradlew -I prune-java.init.gradle pruneCheck                   # the Gradle plugin, with itself
```

The root `pom.xml` pins the plugin to `${project.version}` in `pluginManagement` and leaves it
unbound, because the plugin cannot analyze the build that is still producing it. Each module
logs its own `prune-java check: Analyzed N Java file(s) ...` header followed by the rendered
`Summary:` line. A finding here is either dead code to delete or a guard the analyzer is
missing — decide which before touching a manifest or a rule.

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

**5. Every version lives once, in the root `pom.xml`.** The project version, and every plugin and
library version as a `<properties>` entry (`checkstyle.version`, `maven-compiler-plugin.version`,
...). `prune-gradle-plugin/settings.gradle` reads the project version and every version the
Gradle build shares with the reactor from that file, so both builds run the same analyzer and
formatter releases and Dependabot bumps each once; the three Gradle-only plugins are pinned in
`prune-gradle-plugin/gradle.properties`. Do not write a version literal into either build.

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
git commit -am "Prepare 1.1.0-SNAPSHOT" && git push
```

The workflow refuses a tag whose version differs from the POM or whose
`project.build.outputTimestamp` was not bumped since the previous release.

## Reporting security issues

Please do not open a public issue. See [SECURITY.md](SECURITY.md).
