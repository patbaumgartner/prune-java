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

final class ReferenceIndex {

    enum Scope {
        NONE,
        SAME_PACKAGE_ONLY,
        BEYOND_PACKAGE
    }

    private static final List<String> REFLECTIVE_SERIALIZATION_PACKAGES = List.of(
            "com.google.gson", "com.fasterxml.jackson", "org.codehaus.jackson", "org.yaml.snakeyaml",
            "jakarta.persistence", "javax.persistence", "org.hibernate", "jakarta.xml.bind", "javax.xml.bind",
            "jakarta.json.bind", "javax.json.bind", "com.squareup.moshi", "com.esotericsoftware.kryo",
            "org.springframework.data", "org.bson", "com.thoughtworks.xstream", "org.simpleframework.xml",
            "com.alibaba.fastjson", "org.apache.avro", "org.msgpack", "com.dslplatform.json", "io.protostuff",
            "org.nustaq.serialization", "de.undercouch.bson4jackson", "org.apache.johnzon");

    private final List<JavaSourceFile> javaFiles;
    private final Set<String> literalWords;
    private final boolean moduleDescriptorPresent;
    private final boolean reflectiveSerializationInUse;

    ReferenceIndex(ScannedProject project) {
        this.javaFiles = project.javaFiles().stream().map(ScannedProject.JavaFile::source).toList();
        this.literalWords = indexWords(project);
        this.moduleDescriptorPresent = javaFiles.stream().anyMatch(JavaSourceFile::moduleDescriptor);
        this.reflectiveSerializationInUse = javaFiles.stream()
                .flatMap(file -> file.imports().stream())
                .anyMatch(ReferenceIndex::isReflectiveSerializationImport);
    }

    // Every whole word inside a string literal, resource, build file, or configuration file, split
    // exactly where TextSearch.containsWord would see a word boundary, so "com.example.Foo" and
    // "Outer$Foo" both yield "Foo". Build files stay out of DependencyUsage: a dependency's own
    // declaration is not evidence that it is used.
    private static Set<String> indexWords(ScannedProject project) {
        Set<String> words = new HashSet<>();
        for (ScannedProject.JavaFile file : project.javaFiles()) {
            for (Token string : file.source().strings()) {
                TextSearch.words(string.text(), words);
            }
        }
        for (ScannedProject.ResourceFile resource : project.resources()) {
            TextSearch.words(resource.content(), words);
        }
        for (ScannedProject.ResourceFile configuration : project.configuration()) {
            TextSearch.words(configuration.content(), words);
        }
        return words;
    }

    boolean moduleDescriptorPresent() {
        return moduleDescriptorPresent;
    }

    boolean reflectiveSerializationInUse() {
        return reflectiveSerializationInUse;
    }

    boolean typeReferenced(JavaSourceFile owner, TypeDeclaration type) {
        return typeReferenceScope(owner, type) != Scope.NONE;
    }

    Scope typeReferenceScope(JavaSourceFile owner, TypeDeclaration type) {
        String name = type.name();
        boolean samePackage = false;
        for (JavaSourceFile file : javaFiles) {
            boolean mentioned = file == owner
                    ? file.mentionsOutside(name, type.start(), type.end())
                    : file.mentions(name);
            if (!mentioned) {
                continue;
            }
            if (!file.packageName().equals(owner.packageName())) {
                return Scope.BEYOND_PACKAGE;
            }
            samePackage = true;
        }
        if (mentionedInStringsOrResources(name)) {
            return Scope.BEYOND_PACKAGE;
        }
        return samePackage ? Scope.SAME_PACKAGE_ONLY : Scope.NONE;
    }

    boolean memberReferenced(JavaSourceFile owner, MemberDeclaration member) {
        String name = member.name();
        // A method's own body cannot keep it alive: a recursive call only runs once something else
        // has called the method. A field's initializer stays in, since another declarator may read it.
        boolean method = member.kind() == MemberDeclaration.MemberKind.METHOD;
        int excludeStart = method ? member.start() : member.nameOffset();
        int excludeEnd = method ? member.end() : member.nameOffset() + name.length();
        return owner.mentionsOutside(name, excludeStart, excludeEnd) || mentionedInStringsOrResources(name);
    }

    private boolean mentionedInStringsOrResources(String word) {
        return literalWords.contains(word);
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
