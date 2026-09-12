# Sample projects

Two small but realistic projects used as analyzer fixtures and integration-test inputs: `maven-sample` (Maven, Gson + Commons Lang) and `gradle-sample` (Gradle, SLF4J + Commons IO). Each contains deliberate dead code and exactly one deliberately unused dependency.

They exist because a dead-code analyzer cannot be trusted on the strength of unit tests alone. Unit tests prove the renderer escapes a string correctly; only a real project proves the Maven goal is invocable, the Gradle task runs under the configuration cache, and the analyzer does not flag live code.

**These projects are never built by the reactor.** Nothing compiles them, and the integration tests invoke prune goals and tasks *directly* rather than through a lifecycle phase, so the samples' own dependencies are never resolved. That keeps the whole suite offline-safe. The declared dependencies are there to be *read* as unused-dependency input, not to be downloaded.

They also contain no reference to prune-java. A sample that configured the plugin would no longer resemble the projects prune-java is meant to analyze, so all harness coupling lives outside: in `prune-maven-plugin/pom.xml` for Maven, and in the functional test that copies and patches a throwaway clone for Gradle.

## Ground truth

Each sample carries an `expected-findings.txt` manifest, one finding per line, in the form `<verdict> <type> <symbol>`:

| Verdict | Meaning |
| --- | --- |
| `unused` | The analyzer **must report exactly these** (type and symbol). Asserted as set equality by `SampleProjectFixtureTest`, so a missing or extra finding fails the build. |
| `kept` | The analyzer **must never report** this, under any type. The type column is always `ANY`. |

The split matters more than it looks. A missed finding is an inconvenience; a false positive deletes working code. So the `kept` rows are the ones that guard the philosophy, and they stay under test permanently.

Only unambiguously dead symbols are marked `unused`. Public API with no in-project caller is marked `kept`, because a conservative analyzer cannot prove that no external caller exists — that is a judgement call for the user, not a finding. `PriceFormatter#describe` and `StockLevel#label` are deliberately reachable only from tests, pinning the behaviour of `includeTestReferences`. `CurrencyCodes` and `WarehouseCodes` are public constant holders without Javadoc that only their own package reads, which is exactly the shape `UNUSED_VISIBILITY` targets. `PriceFormatter.LegacyRounding` is a private nested class nothing refers to, the nested form of `UNUSED_CLASS`.

## What is checked, and what is not

`SampleProjectFixtureTest` in `prune-core` validates the manifests themselves (known verdicts, real `IssueType` constants), requires the analyzer's report for each sample to equal the `unused` rows exactly, checks that every finding points at a line naming its symbol, and enforces the kept-symbol invariant. `keptSymbolGuardFailsWhenAReportNamesAKeptSymbol` feeds a synthetic report through the same helper to prove the guard fails when it should.

`SampleProjectFunctionalTest` runs the Gradle plugin against `gradle-sample` (`pruneCheck` fails the build with the manifest's findings, `ignoreFailures` plus `format = 'json'` yields a report equal to the manifest, and `pruneFix` rewrites a copy), and `maven-invoker-plugin` runs both Maven goals against `maven-sample` with `prune.failOnIssues=false`. Neither is decorative: the Maven one is what surfaced a `@Parameter` declared as `String` that Maven could not inject from `${project.basedir}`, a bug no unit test had caught.

## Running the tools by hand

The three `check` runs below are supposed to end in a failed build. The samples exist to be caught,
and both plugins fail the build on findings by default; the findings themselves are printed right
before the failure. Pass `-Dprune.failOnIssues=false` to the Maven goal to keep the build green.

```bash
./mvnw install                                              # CLI jar, Maven plugin
(cd prune-gradle-plugin && ./gradlew publishToMavenLocal)   # Gradle plugin

cd samples/maven-sample
mvn com.patbaumgartner:prune-maven-plugin:0.1.0-SNAPSHOT:check
# BUILD FAILURE: prune-java found 6 unused-code issue(s).

cd ../gradle-sample
../../prune-gradle-plugin/gradlew -I ../../prune-gradle-plugin/prune-java.init.gradle pruneCheck
# BUILD FAILED: prune-java found 4 unused-code issue(s).

java -jar ../../prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar check
# exit 1: Analyzed 4 Java file(s) under .: 4 issue(s) found (conservative mode).
```

The Gradle sample does not apply the plugin, for the reason given above, so
[prune-java.init.gradle](../prune-gradle-plugin/prune-java.init.gradle) applies it from the outside —
the Gradle counterpart of invoking a Maven plugin by its coordinates. The init script resolves the
plugin from the local repository, which is why `publishToMavenLocal` has to run first. The plugin's
wrapper is used only because the sample has none; any Gradle installation works in its place, and
`pruneFix` runs the same way.

The CLI analyzes its working directory unless `--root=<dir>` is given. Run `fix` and `pruneFix` only
on a copy: they rewrite the sample, and the manifests describe the samples as committed.
