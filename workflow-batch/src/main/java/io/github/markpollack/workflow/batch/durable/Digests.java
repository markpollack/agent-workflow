package io.github.markpollack.workflow.batch.durable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

final class Digests {
    private Digests() {}
    static String of(byte[] bytes) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    static String fields(String... fields) {
        StringBuilder text = new StringBuilder();
        for (String field : fields) text.append(field.length()).append(':').append(field);
        return of(text.toString().getBytes(StandardCharsets.UTF_8));
    }
}
