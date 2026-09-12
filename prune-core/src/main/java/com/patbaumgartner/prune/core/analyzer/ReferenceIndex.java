package com.patbaumgartner.prune.core.analyzer;

import com.patbaumgartner.prune.core.project.ScannedProject;
import com.patbaumgartner.prune.core.source.JavaSourceFile;
import com.patbaumgartner.prune.core.source.MemberDeclaration;
import com.patbaumgartner.prune.core.source.TextSearch;
import com.patbaumgartner.prune.core.source.Token;
import com.patbaumgartner.prune.core.source.TypeDeclaration;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// Test sources are indexed separately so a caller can decide whether a test-only
// reference keeps a symbol alive; the reflective-library fact covers the whole build,
// the module-descriptor fact only the module that declares it.
final class ReferenceIndex implements AnalysisContext {

	enum Scope {

		NONE, SAME_PACKAGE_ONLY, BEYOND_PACKAGE

	}

	private static final List<String> REFLECTIVE_SERIALIZATION_PACKAGES = List.of("com.google.gson",
			"com.fasterxml.jackson", "org.codehaus.jackson", "org.yaml.snakeyaml", "jakarta.persistence",
			"javax.persistence", "org.hibernate", "jakarta.xml.bind", "javax.xml.bind", "jakarta.json.bind",
			"javax.json.bind", "com.squareup.moshi", "com.esotericsoftware.kryo", "org.springframework.data",
			"org.bson", "com.thoughtworks.xstream", "org.simpleframework.xml", "com.alibaba.fastjson",
			"org.apache.avro", "org.msgpack", "com.dslplatform.json", "io.protostuff", "org.nustaq.serialization",
			"de.undercouch.bson4jackson", "org.apache.johnzon");

	private final List<JavaSourceFile> mainFiles;

	private final List<JavaSourceFile> testFiles;

	private final Set<String> mainWords;

	private final Set<String> testWords;

	private final boolean includeTestReferences;

	private final Set<String> modulesWithDescriptor;

	private final boolean reflectiveSerializationInUse;

	ReferenceIndex(ScannedProject project, boolean includeTestReferences) {
		this.mainFiles = project.javaFiles()
			.stream()
			.filter(file -> !file.testSource())
			.map(ScannedProject.JavaFile::source)
			.toList();
		this.testFiles = project.javaFiles()
			.stream()
			.filter(ScannedProject.JavaFile::testSource)
			.map(ScannedProject.JavaFile::source)
			.toList();
		this.mainWords = indexWords(project, false);
		this.testWords = indexWords(project, true);
		this.includeTestReferences = includeTestReferences;
		this.modulesWithDescriptor = project.javaFiles()
			.stream()
			.map(ScannedProject.JavaFile::source)
			.filter(JavaSourceFile::moduleDescriptor)
			.map(file -> moduleOf(file.relativePath()))
			.collect(Collectors.toSet());
		this.reflectiveSerializationInUse = project.javaFiles()
			.stream()
			.flatMap(file -> file.source().imports().stream())
			.anyMatch(ReferenceIndex::isReflectiveSerializationImport);
	}

	// Every whole word inside a string literal, resource, build file, or configuration
	// file, split exactly where TextSearch.containsWord would see a word boundary, so
	// "com.example.Foo" and "Outer$Foo" both yield "Foo". Build files stay out of
	// DependencyUsage: a dependency's own declaration is not evidence that it is used.
	private static Set<String> indexWords(ScannedProject project, boolean testSources) {
		Set<String> words = new HashSet<>();
		for (ScannedProject.JavaFile file : project.javaFiles()) {
			if (file.testSource() == testSources) {
				for (Token string : file.source().strings()) {
					TextSearch.words(string.text(), words);
				}
			}
		}
		for (ScannedProject.ResourceFile resource : project.resources()) {
			if (resource.testSource() == testSources) {
				TextSearch.words(resource.content(), words);
			}
		}
		if (!testSources) {
			for (ScannedProject.ResourceFile configuration : project.configuration()) {
				TextSearch.words(configuration.content(), words);
			}
		}
		return words;
	}

	@Override
	public boolean moduleDescriptorPresent(JavaSourceFile file) {
		return modulesWithDescriptor.contains(moduleOf(file.relativePath()));
	}

	// The path above the first src segment: "" for the analyzed root itself, "core" or
	// "../sibling" for another module of the same build.
	static String moduleOf(String relativePath) {
		int src = relativePath.startsWith("src/") ? 0 : relativePath.indexOf("/src/");
		return src < 0 ? relativePath : relativePath.substring(0, src);
	}

	@Override
	public boolean reflectiveSerializationInUse() {
		return reflectiveSerializationInUse;
	}

	@Override
	public boolean mentionedInLiteralsOrResources(String word) {
		return mainWords.contains(word) || (includeTestReferences && testWords.contains(word));
	}

	// Where Java identifiers name the type, from the sources the configuration counts as
	// callers.
	Scope typeScope(JavaSourceFile owner, TypeDeclaration type) {
		return scope(owner, type, includeTestReferences);
	}

	// Test sources always count here: a test in another package needs the type to stay
	// public.
	Scope typeScopeIncludingTests(JavaSourceFile owner, TypeDeclaration type) {
		return scope(owner, type, true);
	}

	boolean namedInTests(JavaSourceFile owner, TypeDeclaration type) {
		return testFiles.stream().anyMatch(file -> mentions(file, owner, type)) || testWords.contains(type.name());
	}

	boolean namedInTests(String word) {
		return testWords.contains(word);
	}

	// A method's own body cannot keep it alive: a recursive call only runs once something
	// else has called the method. A field's initializer stays in, since another
	// declarator may read it.
	boolean memberReferenced(JavaSourceFile owner, MemberDeclaration member) {
		String name = member.name();
		boolean method = member.kind() == MemberDeclaration.MemberKind.METHOD;
		int excludeStart = method ? member.start() : member.nameOffset();
		int excludeEnd = method ? member.end() : member.nameOffset() + name.length();
		return owner.mentionsOutside(name, excludeStart, excludeEnd);
	}

	private Scope scope(JavaSourceFile owner, TypeDeclaration type, boolean includeTests) {
		boolean samePackage = false;
		for (JavaSourceFile file : mainFiles) {
			Scope found = scopeIn(file, owner, type);
			if (found == Scope.BEYOND_PACKAGE) {
				return found;
			}
			samePackage |= found == Scope.SAME_PACKAGE_ONLY;
		}
		if (includeTests) {
			for (JavaSourceFile file : testFiles) {
				Scope found = scopeIn(file, owner, type);
				if (found == Scope.BEYOND_PACKAGE) {
					return found;
				}
				samePackage |= found == Scope.SAME_PACKAGE_ONLY;
			}
		}
		return samePackage ? Scope.SAME_PACKAGE_ONLY : Scope.NONE;
	}

	private static Scope scopeIn(JavaSourceFile file, JavaSourceFile owner, TypeDeclaration type) {
		if (!mentions(file, owner, type)) {
			return Scope.NONE;
		}
		return file.packageName().equals(owner.packageName()) ? Scope.SAME_PACKAGE_ONLY : Scope.BEYOND_PACKAGE;
	}

	private static boolean mentions(JavaSourceFile file, JavaSourceFile owner, TypeDeclaration type) {
		return file.relativePath().equals(owner.relativePath())
				? file.mentionsOutside(type.name(), type.start(), type.end()) : file.mentions(type.name());
	}

	private static boolean isReflectiveSerializationImport(String importName) {
		for (String prefix : REFLECTIVE_SERIALIZATION_PACKAGES) {
			if (importName.equals(prefix) || importName.startsWith(prefix + ".")) {
				return true;
			}
		}
		return false;
	}

}
