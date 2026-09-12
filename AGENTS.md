# prune-java — agent instructions

Conservative Java dead-code analyzer (a `knip` for Java) shipped as a CLI, a language server, a Gradle plugin, and a Maven plugin on top of a shared core. See [README.md](README.md) for the philosophy, module descriptions, and roadmap.

**This repo is a scaffold.** The interfaces, data model, report rendering, and plugin entry points exist; unused-symbol detection does not. `ConservativeUnusedCodeAnalyzer` returns an empty report on purpose. Extend the scaffold — never fabricate detection results or strip the "scaffold active" messages to make a feature look finished.

## Build and test

There are **two builds**, both wrapper-pinned with a distribution checksum, both fine on JDK 21 through 26.

```bash
./mvnw install                                   # prune-core, prune-cli, prune-lsp, prune-maven-plugin
./mvnw -pl prune-core test                       # one module
./mvnw -pl prune-core test -Dtest=ReportRendererTest
./mvnw install -Dinvoker.skip=true               # skip the Maven integration tests
java -jar prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar check --format=json
java -jar prune-lsp/target/prune-lsp-0.1.0-SNAPSHOT.jar   # LSP over stdio; talks JSON-RPC, not a REPL

cd prune-gradle-plugin && ./gradlew build        # the Gradle plugin only
```

`prune-gradle-plugin` is a **separate Gradle build, not a Maven module** — a Gradle plugin needs the real Gradle API, which Maven cannot supply. It resolves `prune-core` from the local repository, so `./mvnw install` must run before it; `./mvnw verify` is not enough. After changing `prune-core`, re-run `./mvnw install` or the Gradle build compiles against a stale artifact.

`maven.compiler.release` in [pom.xml](pom.xml) compiles every module to **Java 17**. Do not use language features or APIs newer than 17.

`prune-gradle-plugin/gradle/wrapper/gradle-wrapper.jar` is re-included by a `!` rule in [.gitignore](.gitignore) that overrides the blanket `*.jar` exclusion. Keep that rule. Without it the wrapper jar silently stops being committed and the Gradle build breaks from a clean clone. The Maven wrapper is `only-script` and has no jar to protect.

## Module boundaries

`prune-core` depends on nothing. `prune-cli`, `prune-lsp`, `prune-gradle-plugin`, and `prune-maven-plugin` each depend only on `prune-core`, and never on each other. `prune-cli`, `prune-lsp`, and `prune-maven-plugin` consume it as a reactor module; `prune-gradle-plugin` consumes it as a published `com.patbaumgartner:prune-core` artifact.

Every integration module is a thin adapter: build an `AnalysisConfig`, call `UnusedCodeAnalyzer.analyze`, then render or log the `AnalysisReport`. Anything reusable belongs in `prune-core`. Never let `prune-core` learn about Gradle, Maven, a CLI framework, or lsp4j.

In the Gradle plugin, never touch `getProject()` inside a `@TaskAction`. It breaks every consumer build that enables the configuration cache. Capture what the action needs into a task property at configuration time, as `PruneCheckTask.getProjectRoot()` does. [PruneGradlePluginTest.java](prune-gradle-plugin/src/test/java/com/patbaumgartner/prune/gradle/PruneGradlePluginTest.java) pins this with a TestKit build run under `--configuration-cache`.

In the language server, **stdout is the wire**. `PruneLspApplication.main` redirects `System.out` to stderr before the launcher starts so a stray `println` cannot corrupt the JSON-RPC stream; keep that, and never hand the original stdout to anything but the launcher. `PruneLanguageServer` maps issues to diagnostics through `DiagnosticMapper` and clears diagnostics for URIs that dropped out of the latest report — publishing a new set without clearing the old leaves ghost squiggles in the editor. lsp4j is the one external dependency an integration module carries, because it *is* the protocol; do not pull it into `prune-core` and do not hand-roll JSON-RPC.

## Sample projects

`samples/maven-sample` and `samples/gradle-sample` are analyzer fixtures with deliberate dead code and one deliberately unused dependency each — see [samples/README.md](samples/README.md). **No reactor builds them**, and the integration tests invoke prune goals and tasks directly rather than through a lifecycle phase, so the samples' dependencies are never resolved and the suite stays offline-safe. Keep it that way, and keep the samples free of any reference to prune-java: harness coupling belongs in `prune-maven-plugin/pom.xml` and in the Gradle functional test, not in the fixtures.

Each sample's `expected-findings.txt` is the ground truth. `unused` rows are the acceptance criteria for detection that does not exist yet; `kept` rows are asserted today and must never be reported. When detection lands, turn `unused` rows into assertions — do not edit them to match what the analyzer happens to emit.

Unit tests cannot catch goal- and task-invocation failures, which is why both plugins are also driven end to end. The Maven side runs through `maven-invoker-plugin` during `install`; that is what surfaced a mojo `@Parameter` declared as `String` that Maven could not inject from `${project.basedir}`. Logs land in `prune-maven-plugin/target/it/maven-sample/build.log`.

## Conventions

- **`prune-core` has zero runtime dependencies.** The JSON writer in [ReportRenderer.java](prune-core/src/main/java/com/patbaumgartner/prune/core/report/ReportRenderer.java) is hand-rolled deliberately — do not add Jackson or Gson. Same for [PruneCliApplication.java](prune-cli/src/main/java/com/patbaumgartner/prune/cli/PruneCliApplication.java): argument parsing is hand-rolled, do not add picocli. `prune-lsp` depends on `org.eclipse.lsp4j` (pinned by `lsp4j.version` in the root pom) and nothing else beyond `prune-core`.
- Data is `record`s (`AnalysisConfig`, `AnalysisReport`, `AnalysisIssue`, `FixAction`, `FixPlan`), behavior is `final class`es, extension points are `interface`s (`UnusedCodeAnalyzer`, `AutofixEngine`).
- Switch over `IssueType` / `OutputFormat` / `Severity` with exhaustive `switch` expressions and **no `default` branch**, so adding a constant breaks compilation at every site that must handle it.
- Almost no comments and no Javadoc in existing code. Match that: comment only what the code cannot show.
- New analysis behavior must stay conservative — a false positive that deletes live code is worse than a missed finding.

## Contracts that tests lock in

Changing any of these is a breaking change; update the tests deliberately, not incidentally.

- **CLI exit codes**: `0` success, `1` `check` found issues, `2` usage error (unknown command, unknown option, unsupported `--format`).
- **Format defaults**: `--ci` implies `github` output, otherwise `terminal`. `fix` currently always exits `0`.
- **LSP diagnostics**: source `prune-java`, code = `IssueType.name()`, tag `Unnecessary`, severity mapped `INFO→Information`, `WARNING→Warning`, `ERROR→Error`. `AnalysisIssue.location` is parsed as `path[:line[:column]]` (1-based, converted to 0-based); a bare path anchors at `0:0`. The server advertises save-only sync (`openClose=false`, `change=None`, `save=true`) and re-analyzes on `didSave` and `didChangeWatchedFiles`. Process exit code is `0` after `shutdown` then `exit`, and `1` if `exit` arrives without a prior `shutdown`, as the LSP spec requires.
- **Output escaping**: issue `symbol`, `location`, and `message` originate from analyzed source, so every sink escapes for its own context. JSON goes through `escapeJson`; the GitHub `file=` property goes through `escapeGithubProperty` (which additionally escapes `:` and `,`, or a crafted path can spoof annotation properties); the GitHub message goes through `escapeGithubData`; terminal output goes through `escapeControlCharacters`, or ANSI sequences from analyzed source can rewrite the report. Reusing the wrong helper reintroduces an injection.

Test conventions live in [.github/instructions/tests.instructions.md](.github/instructions/tests.instructions.md).
