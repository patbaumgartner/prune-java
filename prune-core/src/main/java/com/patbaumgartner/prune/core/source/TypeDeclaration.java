package com.patbaumgartner.prune.core.source;

import java.util.List;

public record TypeDeclaration(TypeKind kind, String name, List<String> modifiers, boolean annotated, boolean documented,
		List<String> superTypes, int start, int nameOffset, int publicModifierOffset, int end,
		List<MemberDeclaration> members, List<TypeDeclaration> nestedTypes) {
	public enum TypeKind {

		CLASS, INTERFACE, ENUM, RECORD, ANNOTATION

	}

	public TypeDeclaration {
		modifiers = List.copyOf(modifiers);
		superTypes = List.copyOf(superTypes);
		members = List.copyOf(members);
		nestedTypes = List.copyOf(nestedTypes);
	}

	public boolean isPublic() {
		return modifiers.contains("public");
	}

	public boolean isProtected() {
		return modifiers.contains("protected");
	}

	public boolean contains(int offset) {
		return offset >= start && offset < end;
	}

	// Since Java 25 a launcher also accepts non-static `void main()`; only a private main
	// is unreachable.
	public boolean hasMainMethod() {
		return members.stream()
			.anyMatch(member -> member.kind() == MemberDeclaration.MemberKind.METHOD && "main".equals(member.name())
					&& !member.isPrivate());
	}

	public List<MemberDeclaration> constructors() {
		return members.stream().filter(member -> member.kind() == MemberDeclaration.MemberKind.CONSTRUCTOR).toList();
	}
}
