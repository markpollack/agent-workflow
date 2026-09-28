package io.github.markpollack.workflow.flows.compiler;

import java.lang.reflect.Type;
import java.util.Objects;

/**
 * Immutable compiler-selected deployment metadata and concrete type declarations. The
 * runtime must check these against its fixed application registration before execution.
 * Metadata equality does not attest to executable bytes or dependency availability.
 *
 * @param entryPoint stable application registration ID, not necessarily a class name or a
 * workflow occurrence ID
 * @param deploymentManifestDigest fingerprint of declared immutable application/build
 * identity
 * @param configurationDigest digest of the configuration actually supplied to operations
 * @param input concrete input declaration
 * @param output concrete output declaration
 */
public record ExecutableIdentity(String entryPoint, String deploymentManifestDigest, String configurationDigest,
		Type input, Type output) {
	public ExecutableIdentity {
		if (entryPoint == null || entryPoint.isBlank())
			throw new IllegalArgumentException("executable entry point required");
		requireDigest(deploymentManifestDigest);
		requireDigest(configurationDigest);
		input = DefinitionOwnership.ownType(input);
		output = DefinitionOwnership.ownType(output);
		TypeContracts contracts = new TypeContracts();
		contracts.applicationType(input);
		contracts.applicationType(output);
	}

	private static void requireDigest(String digest) {
		if (digest == null || !digest.matches("sha256:[0-9a-f]{64}"))
			throw new IllegalArgumentException("versioned SHA-256 executable identity required");
	}
}
