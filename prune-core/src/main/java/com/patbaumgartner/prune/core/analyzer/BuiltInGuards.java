package com.patbaumgartner.prune.core.analyzer;

import com.patbaumgartner.prune.core.report.IssueType;
import com.patbaumgartner.prune.core.source.MemberDeclaration;
import com.patbaumgartner.prune.core.source.TypeDeclaration;

import java.util.List;
import java.util.Optional;
import java.util.Set;

// The guards prune-java ships. Every one exists because of a concrete false-positive
// class; the order is the order in which explanations name them, most specific first.
final class BuiltInGuards {

	private static final Set<String> SERIALIZATION_METHODS = Set.of("writeObject", "readObject", "readObjectNoData",
			"writeReplace", "readResolve", "writeExternal", "readExternal");

	private static final Set<String> SERIALIZATION_FIELDS = Set.of("serialVersionUID", "serialPersistentFields");

	private BuiltInGuards() {
	}

	static List<Guard> all() {
		return List.of(new EntryPoint(), new Annotation(), new NestedType(), new Serialization(), new NativeMethod(),
				new AnnotatedOwner(), new ReflectiveSerialization(), new Javadoc(), new ModuleDescriptor(),
				new Literal());
	}

	static final class EntryPoint implements Guard {

		@Override
		public String id() {
			return "entry-point";
		}

		@Override
		public Optional<String> keep(Candidate candidate) {
			return candidate instanceof Candidate.TypeCandidate type && type.type().hasMainMethod()
					? Optional.of("it declares a main method a launcher can start") : Optional.empty();
		}

	}

	static final class Annotation implements Guard {

		@Override
		public String id() {
			return "annotation";
		}

		@Override
		public Optional<String> keep(Candidate candidate) {
			if (candidate instanceof Candidate.MemberCandidate member) {
				return member.member().annotated() ? Optional
					.of("it carries an annotation on itself or a parameter, which frameworks and reflection act on")
						: Optional.empty();
			}
			return candidate.type().annotated()
					? Optional.of("it carries an annotation, which frameworks and reflection act on")
					: Optional.empty();
		}

	}

	static final class NestedType implements Guard {

		@Override
		public String id() {
			return "nested-type";
		}

		@Override
		public Optional<String> keep(Candidate candidate) {
			if (!(candidate instanceof Candidate.TypeCandidate type) || type.outer().isEmpty()) {
				return Optional.empty();
			}
			TypeDeclaration outer = type.outer().get();
			if (outer.kind() == TypeDeclaration.TypeKind.INTERFACE
					|| outer.kind() == TypeDeclaration.TypeKind.ANNOTATION) {
				return Optional.of("it is nested in an interface, which makes it implicitly public");
			}
			if (outer.annotated()) {
				return Optional.of("it is nested in an annotated type, which a framework may pick up");
			}
			return Optional.empty();
		}

	}

	static final class Serialization implements Guard {

		@Override
		public String id() {
			return "serialization";
		}

		@Override
		public Optional<String> keep(Candidate candidate) {
			if (!(candidate instanceof Candidate.MemberCandidate member)) {
				return Optional.empty();
			}
			MemberDeclaration declaration = member.member();
			if (declaration.kind() == MemberDeclaration.MemberKind.METHOD
					&& SERIALIZATION_METHODS.contains(declaration.name())) {
				return Optional.of("it is a serialization hook the JVM calls by name");
			}
			if (declaration.kind() == MemberDeclaration.MemberKind.FIELD
					&& SERIALIZATION_FIELDS.contains(declaration.name())) {
				return Optional.of("it is a serialization control field the JVM reads by name");
			}
			if (declaration.kind() == MemberDeclaration.MemberKind.FIELD && isSerializable(member.type())) {
				return Optional.of("it is a field of a Serializable type, which serialization reads reflectively");
			}
			return Optional.empty();
		}

		private static boolean isSerializable(TypeDeclaration type) {
			return type.superTypes().contains("Serializable") || type.superTypes().contains("Externalizable");
		}

	}

	static final class NativeMethod implements Guard {

		@Override
		public String id() {
			return "native";
		}

		@Override
		public Optional<String> keep(Candidate candidate) {
			return candidate instanceof Candidate.MemberCandidate member && member.member().hasModifier("native")
					? Optional.of("it is a native method bound by the JVM") : Optional.empty();
		}

	}

	// Frameworks that annotate a type (JPA, Lombok, Jackson) read its fields
	// reflectively.
	static final class AnnotatedOwner implements Guard {

		@Override
		public String id() {
			return "annotated-owner";
		}

		@Override
		public Optional<String> keep(Candidate candidate) {
			return candidate instanceof Candidate.MemberCandidate member
					&& member.member().kind() == MemberDeclaration.MemberKind.FIELD && member.type().annotated()
							? Optional
								.of("it is a field of an annotated type, whose fields frameworks read reflectively")
							: Optional.empty();
		}

	}

	static final class ReflectiveSerialization implements Guard {

		@Override
		public String id() {
			return "reflective-serialization";
		}

		@Override
		public Optional<String> keep(Candidate candidate) {
			return candidate instanceof Candidate.MemberCandidate member && member.member()
				.kind() == MemberDeclaration.MemberKind.FIELD && !member.member().isStatic() && candidate.context()
					.reflectiveSerializationInUse() ? Optional
						.of("it is an instance field while a reflective serialization library (Gson, Jackson, JPA, ...) is in use")
							: Optional.empty();
		}

	}

	static final class Javadoc implements Guard {

		@Override
		public String id() {
			return "javadoc";
		}

		@Override
		public Optional<String> keep(Candidate candidate) {
			return candidate.issueType() == IssueType.UNUSED_VISIBILITY && candidate.type().documented()
					? Optional.of("it has Javadoc, the mark of documented API") : Optional.empty();
		}

	}

	static final class ModuleDescriptor implements Guard {

		@Override
		public String id() {
			return "module-info";
		}

		@Override
		public Optional<String> keep(Candidate candidate) {
			return candidate.issueType() == IssueType.UNUSED_VISIBILITY
					&& candidate.context().moduleDescriptorPresent(candidate.file())
							? Optional.of("its module has a module-info.java whose exports decide visibility")
							: Optional.empty();
		}

	}

	static final class Literal implements Guard {

		@Override
		public String id() {
			return "literal";
		}

		@Override
		public Optional<String> keep(Candidate candidate) {
			return candidate.context().mentionedInLiteralsOrResources(candidate.name()) ? Optional
				.of("it is named in a string literal, resource, build file, or configuration file, which reflection or a framework may resolve")
					: Optional.empty();
		}

	}

}
