package com.patbaumgartner.prune.core.analyzer;

import com.patbaumgartner.prune.core.report.IssueType;
import com.patbaumgartner.prune.core.source.JavaSourceFile;
import com.patbaumgartner.prune.core.source.MemberDeclaration;
import com.patbaumgartner.prune.core.source.TypeDeclaration;

import java.util.Objects;
import java.util.Optional;

// A declaration the analyzer is about to report unless a guard keeps it.
public sealed interface Candidate permits Candidate.TypeCandidate, Candidate.MemberCandidate {

	IssueType issueType();

	JavaSourceFile file();

	// The type itself, or the type declaring the member.
	TypeDeclaration type();

	String symbol();

	AnalysisContext context();

	// The name the finding would carry: the simple type name or the member name.
	String name();

	record TypeCandidate(IssueType issueType, JavaSourceFile file, TypeDeclaration type,
			Optional<TypeDeclaration> outer, String symbol, AnalysisContext context) implements Candidate {
		public TypeCandidate {
			Objects.requireNonNull(issueType, "issueType");
			Objects.requireNonNull(file, "file");
			Objects.requireNonNull(type, "type");
			Objects.requireNonNull(outer, "outer");
			Objects.requireNonNull(symbol, "symbol");
			Objects.requireNonNull(context, "context");
		}

		@Override
		public String name() {
			return type.name();
		}
	}

	record MemberCandidate(IssueType issueType, JavaSourceFile file, TypeDeclaration type, MemberDeclaration member,
			String symbol, AnalysisContext context) implements Candidate {
		public MemberCandidate {
			Objects.requireNonNull(issueType, "issueType");
			Objects.requireNonNull(file, "file");
			Objects.requireNonNull(type, "type");
			Objects.requireNonNull(member, "member");
			Objects.requireNonNull(symbol, "symbol");
			Objects.requireNonNull(context, "context");
		}

		@Override
		public String name() {
			return member.name();
		}
	}

}
