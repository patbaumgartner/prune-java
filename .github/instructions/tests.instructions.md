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

`samples/maven-sample` and `samples/gradle-sample` are realistic projects containing deliberate dead code and one deliberately unused dependency each. They are never built by any reactor. Each carries an `expected-findings.txt` ground-truth manifest with two verdicts:

- `unused` — exactly what the analyzer **must report**, as `(type, symbol)` pairs. `SampleProjectFixtureTest` asserts set equality, so a missing or extra finding fails.
- `kept` — what the analyzer **must never report**, because a false positive that deletes live code is the failure mode this project exists to avoid.

When you change a detection rule, update the manifests deliberately in the same change and explain why in the commit — never edit them to match whatever the analyzer happens to emit. Adding a symbol to a sample means adding its row to the manifest; `SampleProjectFixtureTest` fails on an unknown verdict, issue type, or unexpected finding, so typos surface immediately.

`keptSymbolGuardFailsWhenAReportNamesAKeptSymbol` feeds a synthetic report through the same helper as the live guard to prove the guard fails when it should. Keep it.

Analyzer rules are unit-tested in `ConservativeUnusedCodeAnalyzerTest` against small projects written into a `@TempDir` (`write("src/main/java/com/example/X.java", ...)`). Every guard against a false positive — annotations, `main`, string literals, resources, serialization, reflective libraries, Javadoc, `module-info.java`, sibling modules, runtime-only dependencies — has a test that would fail if the guard were removed. Add one for every new guard. Rewrites are tested in `SourceAutofixEngineTest` with exact full-file `assertEquals` on the result.

## Integration tests

Unit tests cannot catch goal- and task-invocation failures, so both plugins are also driven end to end:

- **Maven** — `maven-invoker-plugin` runs the packaged plugin's `check` and `fix` goals against `samples/maven-sample` in a real Maven process during `install`, with `prune.failOnIssues=false` because the sample is meant to have findings. This is what catches a `@Parameter` whose declared type Maven cannot inject. Skip with `-Dinvoker.skip=true`. Logs land in `prune-maven-plugin/target/it/maven-sample/build.log`; `fix` rewrites the clone under `target/it`, never the sample.
- **Gradle** — `SampleProjectFunctionalTest` copies `samples/gradle-sample` into a `@TempDir`, adds the plugin id to its `plugins {}` block, and runs `pruneCheck` (expecting `buildAndFail()` with the manifest's findings, and a JSON report equal to the manifest with `ignoreFailures`) and `pruneFix` (asserting the rewritten copy) through TestKit.

Both only ever invoke prune goals and tasks directly, never a lifecycle phase, so the samples' own dependencies are never resolved and the suite stays offline-safe.

## Renderer tests

`ReportRenderer` output is a contract. Assert the **exact full string** with `assertEquals`, not `contains`, so escaping regressions in JSON and GitHub workflow commands fail loudly. Every new `OutputFormat` needs a test covering an empty report and a report with escape-sensitive characters (`"`, `\`, `%`, `\r`, `\n`).
