package com.patbaumgartner.prune.core.analyzer;

import java.util.Optional;

// Registered through META-INF/services on the test classpath; keeps exactly one member name.
public final class PluggedGuard implements Guard {

	@Override
	public String id() {
		return "plugged";
	}

	@Override
	public Optional<String> keep(Candidate candidate) {
		return candidate instanceof Candidate.MemberCandidate member && "keptByPlugin".equals(member.member().name())
				? Optional.of("the test plug-in keeps members named keptByPlugin") : Optional.empty();
	}

}
