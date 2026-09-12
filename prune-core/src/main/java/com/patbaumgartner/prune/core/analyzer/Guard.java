package com.patbaumgartner.prune.core.analyzer;

import java.util.Optional;

// A reason a candidate must not be reported. Guards run only for candidates that no Java
// identifier references; the first guard that keeps a candidate names itself in the
// explanation. Additional guards are discovered through java.util.ServiceLoader.
public interface Guard {

	// Stable, lower-case identifier shown next to every explanation this guard produces.
	String id();

	Optional<String> keep(Candidate candidate);

}
