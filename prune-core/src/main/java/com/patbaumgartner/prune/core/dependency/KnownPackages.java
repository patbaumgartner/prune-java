package com.patbaumgartner.prune.core.dependency;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

// Package roots of widely used libraries whose artifact names alone would be ambiguous
// evidence (every Apache Commons artifact shares "commons"). Only unambiguous, stable
// roots belong here.
final class KnownPackages {

	private static final Map<String, List<String>> ROOTS = Map.ofEntries(
			Map.entry("commons-io:commons-io", List.of("org.apache.commons.io")),
			Map.entry("commons-codec:commons-codec", List.of("org.apache.commons.codec")),
			Map.entry("commons-cli:commons-cli", List.of("org.apache.commons.cli")),
			Map.entry("commons-net:commons-net", List.of("org.apache.commons.net")),
			Map.entry("commons-lang:commons-lang", List.of("org.apache.commons.lang")),
			Map.entry("commons-collections:commons-collections", List.of("org.apache.commons.collections")),
			Map.entry("commons-beanutils:commons-beanutils", List.of("org.apache.commons.beanutils")),
			Map.entry("commons-fileupload:commons-fileupload", List.of("org.apache.commons.fileupload")),
			Map.entry("commons-validator:commons-validator", List.of("org.apache.commons.validator")),
			Map.entry("org.apache.commons:commons-lang3", List.of("org.apache.commons.lang3")),
			Map.entry("org.apache.commons:commons-collections4", List.of("org.apache.commons.collections4")),
			Map.entry("org.apache.commons:commons-text", List.of("org.apache.commons.text")),
			Map.entry("org.apache.commons:commons-csv", List.of("org.apache.commons.csv")),
			Map.entry("org.apache.commons:commons-compress", List.of("org.apache.commons.compress")),
			Map.entry("org.apache.commons:commons-math3", List.of("org.apache.commons.math3")),
			Map.entry("org.apache.commons:commons-pool2", List.of("org.apache.commons.pool2")),
			Map.entry("org.apache.commons:commons-dbcp2", List.of("org.apache.commons.dbcp2")),
			Map.entry("org.apache.commons:commons-exec", List.of("org.apache.commons.exec")),
			Map.entry("org.apache.commons:commons-configuration2", List.of("org.apache.commons.configuration2")),
			Map.entry("org.apache.httpcomponents:httpclient", List.of("org.apache.http")),
			Map.entry("org.apache.httpcomponents:httpcore", List.of("org.apache.http")),
			Map.entry("org.apache.httpcomponents.client5:httpclient5",
					List.of("org.apache.hc.client5", "org.apache.hc.core5")),
			Map.entry("org.apache.httpcomponents.core5:httpcore5", List.of("org.apache.hc.core5")),
			Map.entry("com.google.guava:guava", List.of("com.google.common", "com.google.thirdparty")),
			Map.entry("com.google.code.gson:gson", List.of("com.google.gson")),
			Map.entry("com.google.protobuf:protobuf-java", List.of("com.google.protobuf")),
			Map.entry("com.google.code.findbugs:jsr305", List.of("javax.annotation")),
			Map.entry("com.fasterxml.jackson.core:jackson-databind", List.of("com.fasterxml.jackson.databind")),
			Map.entry("com.fasterxml.jackson.core:jackson-core", List.of("com.fasterxml.jackson.core")),
			Map.entry("com.fasterxml.jackson.core:jackson-annotations", List.of("com.fasterxml.jackson.annotation")),
			Map.entry("org.slf4j:slf4j-api", List.of("org.slf4j")),
			Map.entry("org.apache.logging.log4j:log4j-api", List.of("org.apache.logging.log4j")),
			Map.entry("org.projectlombok:lombok", List.of("lombok")),
			Map.entry("com.squareup.okhttp3:okhttp", List.of("okhttp3")),
			Map.entry("com.squareup.retrofit2:retrofit", List.of("retrofit2")),
			Map.entry("com.squareup.moshi:moshi", List.of("com.squareup.moshi")),
			Map.entry("io.vavr:vavr", List.of("io.vavr")),
			Map.entry("org.jetbrains:annotations", List.of("org.jetbrains.annotations")),
			Map.entry("joda-time:joda-time", List.of("org.joda.time")),
			Map.entry("org.mapstruct:mapstruct", List.of("org.mapstruct")),
			Map.entry("org.jsoup:jsoup", List.of("org.jsoup")),
			Map.entry("com.zaxxer:HikariCP", List.of("com.zaxxer.hikari")),
			Map.entry("io.projectreactor:reactor-core", List.of("reactor")),
			Map.entry("org.reactivestreams:reactive-streams", List.of("org.reactivestreams")),
			Map.entry("io.reactivex.rxjava3:rxjava", List.of("io.reactivex.rxjava3")),
			Map.entry("com.github.ben-manes.caffeine:caffeine", List.of("com.github.benmanes.caffeine")),
			Map.entry("org.apache.poi:poi", List.of("org.apache.poi")),
			Map.entry("org.apache.poi:poi-ooxml", List.of("org.apache.poi")),
			Map.entry("org.mockito:mockito-core", List.of("org.mockito")),
			Map.entry("org.assertj:assertj-core", List.of("org.assertj")),
			Map.entry("org.hamcrest:hamcrest", List.of("org.hamcrest")),
			Map.entry("org.junit.jupiter:junit-jupiter", List.of("org.junit.jupiter")),
			Map.entry("org.junit.jupiter:junit-jupiter-api", List.of("org.junit.jupiter")),
			Map.entry("junit:junit", List.of("org.junit", "junit.framework")));

	private KnownPackages() {
	}

	static @Nullable List<String> rootsOf(DeclaredDependency dependency) {
		return ROOTS.get(dependency.coordinates());
	}

}
