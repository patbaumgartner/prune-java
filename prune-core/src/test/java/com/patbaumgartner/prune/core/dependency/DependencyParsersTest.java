package com.patbaumgartner.prune.core.dependency;

import com.patbaumgartner.prune.core.project.ScannedProject;
import com.patbaumgartner.prune.core.source.JavaSourceParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyParsersTest {

    @Test
    void mavenParserReadsCoordinatesScopeTypeClassifierAndLines() {
        var dependencies = MavenPomParser.parse("pom.xml", """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <properties>
                        <gson.version>2.11.0</gson.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.code.gson</groupId>
                            <artifactId>gson</artifactId>
                            <version>${gson.version}</version>
                        </dependency>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>native-lib</artifactId>
                            <type>so</type>
                            <classifier>linux</classifier>
                            <scope>provided</scope>
                        </dependency>
                        <dependency>
                            <groupId>junit</groupId>
                            <artifactId>junit</artifactId>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>managed</groupId>
                                <artifactId>only</artifactId>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);

        assertEquals(List.of("com.google.code.gson:gson", "org.example:native-lib", "junit:junit"),
                dependencies.stream().map(DeclaredDependency::coordinates).toList());
        var gson = dependencies.get(0);
        assertEquals(7, gson.startLine());
        assertEquals(9, gson.artifactLine());
        assertEquals(11, gson.endLine());
        assertTrue(gson.isAnalyzableScope());
        assertTrue(gson.isPlainJar());
        var nativeLib = dependencies.get(1);
        assertEquals("provided", nativeLib.scope());
        assertTrue(nativeLib.isAnalyzableScope());
        assertFalse(nativeLib.isPlainJar());
        assertFalse(dependencies.get(2).isAnalyzableScope());
    }

    @Test
    void mavenParserResolvesPropertiesInCoordinatesAndSkipsUnresolvableOnes() {
        var dependencies = MavenPomParser.parse("pom.xml", """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <properties><lib.group>org.lib</lib.group></properties>
                    <dependencies>
                        <dependency><groupId>${project.groupId}</groupId><artifactId>sibling</artifactId></dependency>
                        <dependency><groupId>${project.groupId}</groupId><artifactId>${project.artifactId}-core</artifactId></dependency>
                        <dependency><groupId>${lib.group}</groupId><artifactId>lib</artifactId></dependency>
                        <dependency><groupId>${unknown}</groupId><artifactId>x</artifactId></dependency>
                    </dependencies>
                </project>
                """);

        assertEquals(List.of("com.example:sibling", "com.example:demo-core", "org.lib:lib"),
                dependencies.stream().map(DeclaredDependency::coordinates).toList());
    }

    @Test
    void mavenParserAcceptsAByteOrderMarkBeforeTheProlog() {
        var dependencies = MavenPomParser.parse("pom.xml", "\uFEFF<?xml version=\"1.0\"?><project><dependencies>"
                + "<dependency><groupId>g</groupId><artifactId>a</artifactId></dependency></dependencies></project>");

        assertEquals(List.of("g:a"), dependencies.stream().map(DeclaredDependency::coordinates).toList());
    }

    @Test
    void mavenParserReturnsNothingForMalformedXmlOrDoctypes() {
        assertEquals(List.of(), MavenPomParser.parse("pom.xml", "<project><dependencies><dependency>"));
        assertEquals(List.of(), MavenPomParser.parse("pom.xml",
                "<!DOCTYPE project [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><project><dependencies>"
                        + "<dependency><groupId>&x;</groupId><artifactId>a</artifactId></dependency></dependencies></project>"));
    }

    @Test
    void gradleParserReadsStringAndMapNotationsForCompileConfigurationsOnly() {
        var dependencies = GradleBuildParser.parse("build.gradle", """
                dependencies {
                    implementation 'org.slf4j:slf4j-api:2.0.16'
                    api("com.google.guava:guava:33.0.0-jre")
                    compileOnly group: 'org.projectlombok', name: 'lombok', version: '1.18.34'
                    implementation("org.example:lib:1.0:sources")
                    implementation 'org.example:variable:${libVersion}'
                    implementation "org.example:interpolated:$version"
                    implementation 'org.example:artifact-only:1.0@aar'
                    // implementation 'org.example:commented:1.0'
                    /* implementation 'org.example:blocked:1.0' */
                    runtimeOnly 'com.h2database:h2:2.3.232'
                    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.0'
                    implementation project(':core')
                    implementation platform('org.springframework.boot:spring-boot-dependencies:3.3.0')
                    implementation 'org.example:with-block:1.0' { exclude group: 'x' }
                }
                """);

        assertEquals(List.of("org.slf4j:slf4j-api", "com.google.guava:guava", "org.projectlombok:lombok",
                        "org.example:lib", "org.example:with-block"),
                dependencies.stream().map(DeclaredDependency::coordinates).toList());
        assertEquals(2, dependencies.get(0).startLine());
        assertEquals("provided", dependencies.get(2).scope());
        assertEquals("sources", dependencies.get(3).classifier());
        assertFalse(dependencies.get(3).isPlainJar());
    }

    @Test
    void gradleCommentStrippingPreservesLineNumbersAndStringContents() {
        var stripped = GradleBuildParser.stripComments("a // x\n/* multi\nline */ b 'http://url' \"c // not\"");

        assertEquals("a     \n        \n        b 'http://url' \"c // not\"", stripped);
    }

    @Test
    void gradleDependencyBlocksSpanUntilTheirClosingBrace() {
        var dependencies = GradleBuildParser.parse("build.gradle", """
                dependencies {
                    implementation('org.example:blocky:1.0') {
                        exclude group: 'x'
                    }
                    implementation 'org.example:inline:1.0' { exclude group: 'y' }
                    implementation group: 'org.example', name: 'mapped', version: '1' {
                        transitive = false
                    }
                    implementation 'org.example:plain:1.0'
                }
                """);

        assertEquals(List.of(2, 5, 6, 9), dependencies.stream().map(DeclaredDependency::startLine).toList());
        assertEquals(List.of(4, 5, 8, 9), dependencies.stream().map(DeclaredDependency::endLine).toList());
    }

    @Test
    void usageTokensDropGenericSegmentsAndRuntimeOnlyArtifactsAreNotAnalyzable() {
        assertEquals(Set.of("gson"), DependencyUsage.coordinateTokens(dependency("com.google.code.gson", "gson")));
        assertEquals(Set.of("commons", "lang3"), DependencyUsage.coordinateTokens(dependency("org.apache.commons", "commons-lang3")));
        assertTrue(DependencyUsage.isAnalyzable(dependency("org.apache.commons", "commons-lang3")));
        assertTrue(DependencyUsage.isAnalyzable(dependency("net.example", "widget-toolkit")));
        assertFalse(DependencyUsage.isAnalyzable(dependency("ch.qos.logback", "logback-classic")));
        assertFalse(DependencyUsage.isAnalyzable(dependency("org.springframework.boot", "spring-boot-starter-web")));
        assertFalse(DependencyUsage.isAnalyzable(dependency("org.flywaydb", "flyway-core")));
        assertFalse(DependencyUsage.isAnalyzable(dependency("org.webjars", "bootstrap")));
        assertFalse(DependencyUsage.isAnalyzable(dependency("org.postgresql", "postgresql")));
        assertFalse(DependencyUsage.isAnalyzable(dependency("com.h2database", "h2")));
        assertFalse(DependencyUsage.isAnalyzable(dependency("org.hibernate.orm", "hibernate-core")));
        assertFalse(DependencyUsage.isAnalyzable(dependency("org.mapstruct", "mapstruct-processor")));
        assertFalse(DependencyUsage.isAnalyzable(dependency("org.apache.tomcat.embed", "tomcat-embed-jasper")));
        assertFalse(DependencyUsage.isAnalyzable(dependency("com.google.guava", "failureaccess")));
        assertFalse(DependencyUsage.isAnalyzable(dependency("org", "api")));
        assertFalse(DependencyUsage.isAnalyzable(new DeclaredDependency("g", "a", "runtime", "", "", "pom.xml", 1, 1, 1)));
        assertFalse(DependencyUsage.isAnalyzable(new DeclaredDependency("g", "a", "", "", "linux", "pom.xml", 1, 1, 1)));
    }

    @Test
    void usageIsDecidedByImportsQualifiedNamesStringsAndResources() {
        var imported = source("A.java", "package p;\nimport org.slf4j.Logger;\nimport net.example.widget.Frame;\nclass A { }");
        var qualified = source("B.java", "package p;\nclass B { Object o = com.google.gson.Gson.class; }");
        var inString = source("C.java", "package p;\nclass C { String s = \"org.apache.commons.lang3.StringUtils\"; }");
        var resource = new ScannedProject.ResourceFile("src/main/resources/log4j2.xml",
                "<Configuration packages=\"org.apache.logging.log4j.core\"/>", false);
        var project = new ScannedProject(Path.of("."), List.of(imported, qualified, inString), List.of(resource), List.of());

        var usage = new DependencyUsage(project);

        assertTrue(usage.isUsed(dependency("org.slf4j", "slf4j-api")));
        assertTrue(usage.isUsed(dependency("com.google.code.gson", "gson")));
        assertTrue(usage.isUsed(dependency("org.apache.commons", "commons-lang3")));
        assertTrue(usage.isUsed(dependency("org.apache.logging.log4j", "log4j-api")));
        assertTrue(usage.isUsed(dependency("net.example", "widget-toolkit")));
        assertTrue(usage.isUsed(dependency("net.example", "gizmo")), "a sibling artifact of an imported group stays");
        assertFalse(usage.isUsed(dependency("commons-io", "commons-io")));
        assertFalse(usage.isUsed(dependency("com.google.guava", "guava")));
        assertFalse(usage.isUsed(dependency("com.fasterxml.jackson.core", "jackson-databind")));
        assertFalse(usage.isUsed(dependency("net.other", "gizmo")));
    }

    @Test
    void knownPackageRootsMatchWildcardImportsAndExactRoots() {
        var wildcard = source("A.java", "package p;\nimport org.apache.commons.io.*;\nimport lombok.Data;\nclass A { }");
        var project = new ScannedProject(Path.of("."), List.of(wildcard), List.of(), List.of());

        var usage = new DependencyUsage(project);

        assertTrue(usage.isUsed(dependency("commons-io", "commons-io")));
        assertTrue(usage.isUsed(dependency("org.projectlombok", "lombok")));
        assertFalse(usage.isUsed(dependency("org.apache.commons", "commons-lang3")));
    }

    @Test
    void lombokLoggingAnnotationsCountAsUsageOfTheLoggingApiTheyGenerateAgainst() {
        var slf4j = source("A.java", "package p;\nimport lombok.extern.slf4j.Slf4j;\n@Slf4j class A { }");
        var log4j = source("B.java", "package p;\nimport lombok.extern.log4j.*;\n@Log4j2 class B { }");
        var project = new ScannedProject(Path.of("."), List.of(slf4j, log4j), List.of(), List.of());

        var usage = new DependencyUsage(project);

        assertTrue(usage.isUsed(dependency("org.slf4j", "slf4j-api")));
        assertTrue(usage.isUsed(dependency("org.apache.logging.log4j", "log4j-api")));
        assertFalse(usage.isUsed(dependency("org.apache.commons", "commons-lang3")));
        assertFalse(new DependencyUsage(new ScannedProject(Path.of("."), List.of(), List.of(), List.of()))
                .isUsed(dependency("org.slf4j", "slf4j-api")));
    }

    private static ScannedProject.JavaFile source(String name, String content) {
        return new ScannedProject.JavaFile(JavaSourceParser.parse("src/main/java/p/" + name, content), true, false);
    }

    private static DeclaredDependency dependency(String group, String artifact) {
        return new DeclaredDependency(group, artifact, "", "", "", "pom.xml", 1, 1, 1);
    }
}
