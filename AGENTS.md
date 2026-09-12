# prune-java — agent instructions

Conservative Java dead-code analyzer (a `knip` for Java) shipped as a CLI, a language server, a Gradle plugin, and a Maven plugin on top of a shared core. See [README.md](README.md) for the philosophy, the detection rules, and each adapter's options.

**Detection is real and asserted.** `ConservativeUnusedCodeAnalyzer` reports the five `IssueType`s against the rules in the README, `SourceAutofixEngine` rewrites sources for the autofixable ones, and `SampleProjectFixtureTest` requires the report for each sample to equal its `expected-findings.txt` exactly. When you change a rule, the manifests and the README table change in the same commit — never edit a manifest to match whatever the analyzer happens to emit, and never loosen a guard to make a sample finding appear.

## Build and test

There are **two builds**, both wrapper-pinned with a distribution checksum, both fine on JDK 21 through 26.

```bash
./mvnw install                                   # prune-core, prune-cli, prune-lsp, prune-maven-plugin
./mvnw -pl prune-core test                       # one module
./mvnw -pl prune-core test -Dtest=ReportRendererTest
./mvnw install -Dinvoker.skip=true               # skip the Maven integration tests
java -jar prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar check --format=json --root=samples/maven-sample
java -jar prune-lsp/target/prune-lsp-0.1.0-SNAPSHOT.jar   # LSP over stdio; talks JSON-RPC, not a REPL

cd prune-gradle-plugin && ./gradlew build        # the Gradle plugin only
cd prune-gradle-plugin && ./gradlew publishToMavenLocal   # plugin + marker into ~/.m2, for builds outside TestKit
```

On newer JDKs Maven prints `sun.misc.Unsafe` lines to stderr that start with `WARNING`, and `-q` also hides the javac `[WARNING]` output you want. Filter instead: `./mvnw -B -ntp install 2>&1 | grep -v '^WARNING'`. Per-class test results are in `*/target/surefire-reports/*.txt`.

To smoke-test the language server, pipe framed JSON-RPC messages into the jar and check the exit code; there is no interactive mode:

```bash
msg() { printf 'Content-Length: %d\r\n\r\n%s' "${#1}" "$1"; }
{ msg '{"jsonrpc":"2.0","id":1,"method":"shutdown"}'; msg '{"jsonrpc":"2.0","method":"exit"}'; } \
  | java -jar prune-lsp/target/prune-lsp-0.1.0-SNAPSHOT.jar; echo "exit=$?"   # expect 0; drop the shutdown message to expect 1
```

`prune-gradle-plugin` is a **separate Gradle build, not a Maven module** — a Gradle plugin needs the real Gradle API, which Maven cannot supply. It resolves `prune-core` from the local repository, so `./mvnw install` must run before it; `./mvnw verify` is not enough. After changing `prune-core`, re-run `./mvnw install` or the Gradle build compiles against a stale artifact.

`maven.compiler.release` in [pom.xml](pom.xml) compiles every module to **Java 17**. Do not use language features or APIs newer than 17.

`prune-gradle-plugin/gradle/wrapper/gradle-wrapper.jar` is re-included by a `!` rule in [.gitignore](.gitignore) that overrides the blanket `*.jar` exclusion. Keep that rule. Without it the wrapper jar silently stops being committed and the Gradle build breaks from a clean clone. The Maven wrapper is `only-script` and has no jar to protect.

## Module boundaries

`prune-core` depends on nothing. `prune-cli`, `prune-lsp`, `prune-gradle-plugin`, and `prune-maven-plugin` each depend only on `prune-core`, and never on each other. `prune-cli`, `prune-lsp`, and `prune-maven-plugin` consume it as a reactor module; `prune-gradle-plugin` consumes it as a published `com.patbaumgartner:prune-core` artifact.

Every integration module is a thin adapter: build an `AnalysisConfig`, call `UnusedCodeAnalyzer.analyze`, for `fix` hand the report to `new SourceAutofixEngine(root).fix(report)`, then render or log the `AnalysisReport`. Anything reusable belongs in `prune-core`. Never let `prune-core` learn about Gradle, Maven, a CLI framework, or lsp4j.

Inside `prune-core` the pipeline is `project.ProjectScanner` (walks the enclosing build, parses every `.java` file with `source.JavaSourceParser`, reads text resources under `src`, build files, and machine-read configuration outside `src` — `*.xml`, `*.properties`, `*.yml`, `*.yaml`, `*.MF` — but never prose formats, or a README naming a class would hide its finding) → `analyzer.ReferenceIndex` (identifier, string-literal, and resource word indexes; build and configuration files feed the class-name index but not `DependencyUsage`, because a dependency's own declaration is not evidence it is used) → `ConservativeUnusedCodeAnalyzer` (the rules) → `fix.SourceAutofixEngine` (re-locates each finding in the current text and plans whole-line deletions; `fix.ImportCleanup` drops imports a removal leaves unused). `dependency.MavenPomParser` and `dependency.GradleBuildParser` extract declarations; `dependency.DependencyUsage` decides usage with `dependency.KnownPackages` for exact package roots and coordinate tokens otherwise. `JavaSourceParser` is structural, not a full Java grammar: it finds top-level and nested types, members at depth one, spans, and modifiers, and marks anything it cannot follow as `opaque` so the analyzer skips it as a candidate while still counting its identifiers as references. Extend it rather than adding a parser dependency.

Every guard in the analyzer exists because of a concrete false-positive class: annotations (frameworks and reflection), `main` (entry points, including the instance `main` a launcher accepts since Java 25), string literals and resources (`Class.forName`, service loaders, Spring XML), serialization hooks and `Serializable` fields, reflective serialization imports (Gson, Jackson, JPA read private fields), Javadoc on public classes (documented API of a library), `module-info.java` (exports pin visibility), interface-nested and annotated-outer nested types (implicit visibility, framework pickup), Lombok logging annotations (generated code against an API the source never names), and the runtime-only dependency lists (drivers, logging backends, starters, processors). Removing a guard needs a test in `ConservativeUnusedCodeAnalyzerTest` proving the false positive it prevented no longer occurs, not just a passing sample.

In the Gradle plugin, never touch `getProject()` inside a `@TaskAction`. It breaks every consumer build that enables the configuration cache. Capture what the action needs into a task property at configuration time, as `PruneCheckTask.getProjectRoot()` does. [PruneGradlePluginTest.java](prune-gradle-plugin/src/test/java/com/patbaumgartner/prune/gradle/PruneGradlePluginTest.java) pins this with a TestKit build run under `--configuration-cache`. Every task type carries `@DisableCachingByDefault(because = ...)`: applying `maven-publish` switches `validatePlugins` to strict mode, which fails the build on a task type that does not say whether it is cacheable.

In the language server, **stdout is the wire**. `PruneLspApplication.main` redirects `System.out` to stderr before the launcher starts so a stray `println` cannot corrupt the JSON-RPC stream; keep that, and never hand the original stdout to anything but the launcher. `PruneLanguageServer` maps issues to diagnostics through `DiagnosticMapper`, clears diagnostics for URIs that dropped out of the latest report — publishing a new set without clearing the old leaves ghost squiggles in the editor — and keeps the last report so `QuickFixMapper` can turn a `codeAction` request into `SourceAutofixEngine` edits for the matching issue. lsp4j is the one external dependency an integration module carries, because it *is* the protocol; do not pull it into `prune-core` and do not hand-roll JSON-RPC. Two lsp4j 1.0.0 API traps: `Launcher.startListening()` returns a plain `Future`, not a `CompletableFuture`, so it cannot be combined with `CompletableFuture.anyOf`; and `Diagnostic.getMessage()` / `getCode()` return `Either`, so tests read them through `.getLeft()`.

## Sample projects

`samples/maven-sample` and `samples/gradle-sample` are analyzer fixtures with deliberate dead code and one deliberately unused dependency each — see [samples/README.md](samples/README.md). **No reactor builds them**, and the integration tests invoke prune goals and tasks directly rather than through a lifecycle phase, so the samples' dependencies are never resolved and the suite stays offline-safe. Keep it that way, and keep the samples free of any reference to prune-java: harness coupling belongs in `prune-maven-plugin/pom.xml` and in the Gradle functional test, not in the fixtures. Runs by hand against `gradle-sample` go through [prune-java.init.gradle](prune-gradle-plugin/prune-java.init.gradle), which applies the plugin to the root project from outside the build — by class, because plugin-id lookup cannot see the init-script classpath — and resolves it from `~/.m2`, so `./gradlew publishToMavenLocal` must have run first.

Each sample's `expected-findings.txt` is the ground truth. `SampleProjectFixtureTest` asserts that the analyzer reports exactly the `unused` rows (type and symbol) and never a `kept` symbol; the Gradle functional test and the Maven invoker IT drive the same samples through the real plugins. The Maven IT runs with `prune.failOnIssues=false` because the sample is meant to have findings, and `fix` in that IT rewrites the clone under `target/it`, never the sample itself.

Unit tests cannot catch goal- and task-invocation failures, which is why both plugins are also driven end to end. The Maven side runs through `maven-invoker-plugin` during `install`; that is what surfaced a mojo `@Parameter` declared as `String` that Maven could not inject from `${project.basedir}`. Logs land in `prune-maven-plugin/target/it/maven-sample/build.log`. The IT forks whichever Maven runs the build, so the wrapper legs in CI exercise the plugin under Maven 3.9 and the `maven4` job, which builds with a checksum-pinned Maven 4 download, exercises it under Maven 4. `maven.version` in the root pom is the plugin's floor (3.9.0) as well as the `maven-plugin-api` it compiles against, and Dependabot is told to leave it alone: bumping it to the newest patch locks every older Maven out of the plugin.

## Conventions

- **`prune-core` has zero runtime dependencies.** The JSON writer in [ReportRenderer.java](prune-core/src/main/java/com/patbaumgartner/prune/core/report/ReportRenderer.java) is hand-rolled deliberately — do not add Jackson or Gson. The Java lexer/parser and the pom/Gradle readers are hand-rolled for the same reason (the pom reader uses the JDK's SAX parser with external entities disabled). Same for [PruneCliApplication.java](prune-cli/src/main/java/com/patbaumgartner/prune/cli/PruneCliApplication.java): argument parsing is hand-rolled, do not add picocli. `prune-lsp` depends on `org.eclipse.lsp4j` (pinned by `lsp4j.version` in the root pom) and nothing else beyond `prune-core`.
- Data is `record`s (`AnalysisConfig`, `AnalysisReport`, `AnalysisIssue`, `SourceLocation`, `FixAction`, `FixPlan`, `FixResult`, `TypeDeclaration`, `MemberDeclaration`, `DeclaredDependency`), behavior is `final class`es, extension points are `interface`s (`UnusedCodeAnalyzer`, `AutofixEngine`).
- Switch over `IssueType` / `OutputFormat` / `Severity` with exhaustive `switch` expressions and **no `default` branch**, so adding a constant breaks compilation at every site that must handle it.
- Almost no comments and no Javadoc in existing code. Match that: comment only what the code cannot show.
- New analysis behavior must stay conservative — a false positive that deletes live code is worse than a missed finding. Every rewrite goes through `FixAction` with the exact `original` text, `SourceAutofixEngine.apply` refuses to touch a file whose text no longer matches the plan, writes go through a sibling temp file and an atomic move, and a target whose real path leaves the root is never planned.
- The version lives once, in the root [pom.xml](pom.xml); `prune-gradle-plugin/build.gradle` reads it from there. Do not hard-code it in the Gradle build again.
- Releases are tag-driven: pushing `vX.Y.Z` runs [release.yml](.github/workflows/release.yml), which stages the reactor (`mvn deploy` to a local directory) and the Gradle plugin (`publishAllPublicationsToStagingRepository`), attests provenance, and hands both directories to JReleaser ([jreleaser.yml](jreleaser.yml)) for signing, Maven Central, and the GitHub release. `project.build.outputTimestamp` must be bumped with the version or the workflow refuses the tag; the `Reproducible build` CI job is what makes that timestamp mean something. Commit messages follow Conventional Commits because the changelog is generated from them. The procedure is in [CONTRIBUTING.md](CONTRIBUTING.md#releasing).
- Module POMs are flattened (`flatten-maven-plugin`, `oss` mode) on install and deploy, so the published `prune-core` POM has no parent and no `${...}` left in it; check `target/.flattened-pom.xml` when a change touches inherited metadata.
- [.github/dependabot.yml](.github/dependabot.yml) owns version updates for Maven, Gradle, and the SHA-pinned actions; [SECURITY.md](SECURITY.md) states the trust model users rely on. Keep both true when behavior changes.

## Contracts that tests lock in

Changing any of these is a breaking change; update the tests deliberately, not incidentally.

- **CLI exit codes**: `0` success, `1` `check` found issues or `fix` left issues that need a human, `2` usage error (unknown command, unknown option, unsupported `--format`, `--root` not a directory, empty or invalid `--exclude` glob), `3` analysis or rewrite failure.
- **Format defaults**: `--ci` implies `github` output, otherwise `terminal`; an explicit `--format` wins. `--root=<dir>` selects the project; the working directory is the default. `--exclude=<glob>` is repeatable and removes candidates without removing references.
- **Symbols and locations**: `AnalysisIssue.symbol` is `fully.qualified.Type`, `fully.qualified.Type.Nested`, `fully.qualified.Type#member`, or `groupId:artifactId`; `location` is `path[:line[:column]]` relative to the analyzed root with one-based positions, and issues are sorted by path, line, column, type, symbol. Class findings anchor at the type name, member findings at the member name, dependency findings at the `artifactId` line.
- **Autofixable**: `UNUSED_METHOD`, single-declarator `UNUSED_FIELD`, `UNUSED_VISIBILITY`, and `UNUSED_DEPENDENCY` are autofixable; `UNUSED_CLASS` never is. `fix` reports `Applied N autofix(es) in M file(s); K issue(s) remain (conservative mode).` and lists only the remaining issues.
- **Plugin parameters**: Maven `prune.failOnIssues` (default `true`), `prune.format` (`terminal`), `prune.excludes` (comma-separated globs), `prune.skip`, and both goals skip `pom` packaging; Gradle `pruneCheck.ignoreFailures` (default `false`) plus `format` and `excludes` on both tasks. Both fail with `prune-java found N unused-code issue(s).`
- **LSP diagnostics**: source `prune-java`, code = `IssueType.name()`, tag `Unnecessary`, severity mapped `INFO→Information`, `WARNING→Warning`, `ERROR→Error`. `AnalysisIssue.location` is parsed by `SourceLocation` as `path[:line[:column]]` (1-based, converted to 0-based); a bare path anchors at `0:0`. The server advertises save-only sync (`openClose=false`, `change=None`, `save=true`) plus a `quickfix` code-action provider, and re-analyzes on `didSave` and `didChangeWatchedFiles`. Process exit code is `0` after `shutdown` then `exit`, and `1` if `exit` arrives without a prior `shutdown`, as the LSP spec requires.
- **Output escaping**: issue `symbol`, `location`, and `message` originate from analyzed source, so every sink escapes for its own context. JSON goes through `escapeJson`; the GitHub `file=` property goes through `escapeGithubProperty` (which additionally escapes `:` and `,`, or a crafted path can spoof annotation properties) while `line=`/`col=` come from the parsed `SourceLocation` integers; the GitHub message goes through `escapeGithubData`; terminal output goes through `escapeControlCharacters`, or ANSI sequences from analyzed source can rewrite the report. Reusing the wrong helper reintroduces an injection.

Test conventions live in [.github/instructions/tests.instructions.md](.github/instructions/tests.instructions.md).
