package io.github.markpollack.workflow.flows.compiler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Length-framed internal identity encoding; not an executable serialization format. */
final class IdentityEncoding {
    private IdentityEncoding() {}
    static void field(StringBuilder target,String value) { target.append(value.length()).append(':').append(value); }
    static String digest(String value) {
        try { return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
