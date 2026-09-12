---
applyTo: "**/src/test/java/**/*.java"
description: "JUnit 5 test conventions for prune-java: no mocking library, hand-built test seams for CLI/Gradle/Maven entry points, exact-string assertions for renderers."
---

# Test conventions

JUnit 5 only. **There is no mocking library on the classpath** (no Mockito, no AssertJ). Do not add one — use the hand-built seams below instead.

- Plain `org.junit.jupiter.api.Assertions` with static imports (`assertEquals`, `assertTrue`, `assertNotNull`).
- `class FooTest` and test methods are package-private, no modifier.
- Method names are behavioral sentences: `checkCommandReturnsSuccessWithNoIssues`, `escapesJsonContentInJsonOutput`. No `@DisplayName`.
- `var` for locals throughout.
- Arrange / act / assert separated by blank lines, no comment headers.

## Seams per module

- **`prune-cli`** — `PruneCliApplication` has package-private constructors taking `UnusedCodeAnalyzer` and `ReportRenderer` (optionally plus a `Function<Path, AutofixEngine>` factory), and a package-private `run(String[], PrintStream, PrintStream)`. Drive it with `ByteArrayOutputStream` and assert on the exit code plus captured stdout/stderr. Never call `main`; it calls `System.exit`.
- **`prune-lsp`** — `PruneLanguageServer` has package-private constructors taking `UnusedCodeAnalyzer` (a lambda returning a synthetic `AnalysisReport` is enough) and optionally a `Function<Path, AutofixEngine>`. `connect` the `RecordingClient implements LanguageClient` from `PruneLanguageServerTest`, which lists every `publishDiagnostics` and `logMessage` call, then invoke `initialize` / `initialized` / `didSave` / `codeAction` directly. Never go through `PruneLspApplication.main` or `LSPLauncher`; `main` calls `System.exit`. lsp4j 1.0.0 wraps `Diagnostic.getMessage()` and `getCode()` in `Either`, so assert on `.getLeft()`.
- **`prune-gradle-plugin`** — `org.gradle.testfixtures.ProjectBuilder.builder().build()`, apply the plugin, assert tasks resolve by name. For end-to-end coverage use `GradleRunner` with `withPluginClasspath()`, which resolves the plugin under test **only through a `plugins {}` block** — a legacy `apply plugin:` line fails with "plugin not found".
- **`prune-maven-plugin`** — mojo `@Parameter` fields are private with no setter; inject via the `setPrivateField` reflection helper in `PruneMojoTest`, and capture output with the local `CapturingLog implements Log`.
- **Filesystem** — `@TempDir Path tempDir`, never real project paths. The one exception is the sample fixtures below: they are read-only inputs, and the Gradle functional test copies them into a `@TempDir` before running anything.

## Sample projects

`samples/maven-sample` and `samples/gradle-sample` are plain-Java projects, and `samples/spring-boot-sample`, `samples/quarkus-sample`, `samples/helidon-sample`, and `samples/micronaut-sample` are framework projects; all contain deliberate dead code and one deliberately unused dependency each, and none is ever built by any reactor. `SampleProjectFixtureTest` discovers every `samples/*/expected-findings.txt`, so a new sample needs a manifest and nothing else. The manifest has three verdicts:

- `unused TYPE symbol` — exactly what the analyzer **must report**. `SampleProjectFixtureTest` asserts set equality, so a missing or extra finding fails.
- `test-only TYPE symbol` — not reported by default; reported exactly so, with an `is only referenced from tests` message, when `includeTestReferences` is off.
- `kept ANY symbol` — what the analyzer **must never report** and never explains, because an identifier references it. `kept <guard-id> symbol` — never reported, and with `explain` on it is kept by exactly that guard. The explained set must equal the guard-naming rows, so every guard decision in a sample is written down and a guard change shows up as a manifest diff.

When you change a detection rule or a guard, update the manifests deliberately in the same change and explain why in the commit — never edit them to match whatever the analyzer happens to emit. Adding a symbol to a sample means adding its row to the manifest; `SampleProjectFixtureTest` fails on an unknown verdict, issue type, guard id, or unexpected finding, so typos surface immediately. Samples must not mention `com.patbaumgartner` or commit a `prune-baseline.txt`; the fixture test checks both. The Maven samples share `samples/pom.xml` as their reactor, so the analyzer reads all of `samples/` for any of them: a fixture's dead symbol must stay dead with its neighbours in scope (no sibling may import its unused dependency's package or name its dead types), and `everySampleReportsTheSameFindingsWhenAnalyzedOutsideTheAggregator` copies each sample out on its own to prove it. A new Maven sample goes into the aggregator's `<modules>`; `theAggregatorListsExactlyTheMavenSamplesAndIsNotTheirParent` fails until it does.

`keptSymbolGuardFailsWhenAReportNamesAKeptSymbol` feeds a synthetic report through the same helper as the live guard to prove the guard fails when it should. Keep it.

Analyzer rules are unit-tested in `ConservativeUnusedCodeAnalyzerTest` against small projects written into a `@TempDir` (`write("src/main/java/com/example/X.java", ...)`). Every guard against a false positive — annotations on members and on parameters, `main`, string literals, resources, serialization, reflective libraries, Javadoc, `module-info.java`, sibling modules, runtime-only dependencies — has a test that would fail if the guard were removed, and `explainNamesTheGuardThatKeptEveryCandidate...` pins the guard id and message of each. Add one for every new guard. `GuardPipelineTest` pins the built-in order, the `ServiceLoader` discovery (through `src/test/resources/META-INF/services/...Guard` and `PluggedGuard`), duplicate-id rejection, and that referenced code never reaches a guard. Baseline parsing and rendering are exact-string tested in `BaselineTest`. Rewrites are tested in `SourceAutofixEngineTest` with exact full-file `assertEquals` on the result.

## Fuzzing

`ParserFuzzer` (test sources, `prune-core`) is a plain Jazzer target: `fuzzerTestOneInput(FuzzedDataProvider)` feeds one string to `JavaSourceParser`, `MavenPomParser`, `GradleBuildParser`, and `Baseline` and throws on any broken invariant. `ParserFuzzerTest` replays every file in `src/test/resources/.../ParserFuzzerInputs/` through it on each build and proves the checker itself fails on an inconsistent declaration. A parser fix that came from fuzzing keeps its crashing input in that directory as the regression test; a new parser gets its invariants added to `ParserFuzzer` and a seed to the corpus. Fuzzing runs only through `./mvnw -pl prune-core -Pfuzz test-compile exec:exec`, never in CI.

## Integration tests

Unit tests cannot catch goal- and task-invocation failures, so both plugins are also driven end to end:

- **Maven** — `maven-invoker-plugin` runs the packaged plugin's `check`, `fix`, `baseline`, and `check` goals in that order against `samples/maven-sample` in a real Maven process during `install`, with `prune.failOnIssues=false` because the sample is meant to have findings; the final `check` shows the remaining findings suppressed by the baseline the third goal wrote. This is what catches a `@Parameter` whose declared type Maven cannot inject. Skip with `-Dinvoker.skip=true`. Logs land in `prune-maven-plugin/target/it/maven-sample/build.log`; `fix` and `baseline` write into the clone under `target/it`, never the sample.
- **Gradle** — `SampleProjectFunctionalTest` copies `samples/gradle-sample` into a `@TempDir`, adds the plugin id to its `plugins {}` block, and runs `pruneCheck` (expecting `buildAndFail()` with the manifest's findings, the `test-only` rows on top with `testReferences = false`, and a JSON report equal to the manifest with `ignoreFailures`) and `pruneFix` (asserting the rewritten copy) through TestKit. `PruneGradlePluginTest` drives `pruneBaseline` and the `baseline`/`testReferences`/`explain` properties against scratch builds, including one under `--configuration-cache`.

Both only ever invoke prune goals and tasks directly, never a lifecycle phase, so the samples' own dependencies are never resolved and the suite stays offline-safe.

## Renderer tests

`ReportRenderer` output is a contract. Assert the **exact full string** with `assertEquals`, not `contains`, so escaping regressions in JSON and GitHub workflow commands fail loudly. Every new `OutputFormat` needs a test covering an empty report and a report with escape-sensitive characters (`"`, `\`, `%`, `\r`, `\n`).
