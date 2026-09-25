package io.github.markpollack.workflow.flows.compiler;

import java.lang.reflect.Type;
import java.util.Objects;

/**
 * Immutable deployment-supplied selection of an executable closure and configuration.
 * Digests attest to selected bytes; they do not prove availability or retention. Before run
 * admission, dispatch or resume, the runtime must resolve and verify those bytes against
 * these identities. Display names and an arbitrary handler with matching Java types are insufficient.
 *
 * @param entryPoint implementation entry point within the selected closure
 * @param artifactClosureDigest SHA-256 of the deployment's immutable artifact/dependency closure
 * @param configurationDigest SHA-256 of the selected immutable behavior configuration
 * @param input concrete input declaration
 * @param output concrete output declaration
 */
public record ExecutableIdentity(String entryPoint,String artifactClosureDigest,String configurationDigest,
                                 Type input,Type output) {
    public ExecutableIdentity {
        if(entryPoint==null||entryPoint.isBlank()) throw new IllegalArgumentException("executable entry point required");
        requireDigest(artifactClosureDigest); requireDigest(configurationDigest);
        input=DefinitionOwnership.ownType(input); output=DefinitionOwnership.ownType(output);
        TypeContracts contracts=new TypeContracts(); contracts.applicationType(input); contracts.applicationType(output);
    }
    private static void requireDigest(String digest) {
        if(digest==null||!digest.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("versioned SHA-256 executable identity required");
    }
}
