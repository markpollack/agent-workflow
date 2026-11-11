package io.github.markpollack.workflow.flows.compiler;

import java.time.Duration;
import java.util.Objects;

/**
 * Finite default/cap for timed definitions. {@link #UNTIMED} explicitly opts out
 * of time limits; the fluent API exposes that intent as {@code withoutDeadline()}.
 * Positive authored durations can tighten, but cannot extend, a finite maximum.
 * @param profile stable policy identity retained in deadline provenance
 * @param maximum positive finite default/cap, or zero for an explicit untimed policy
 */
public record DeadlinePolicy(String profile, Duration maximum) {
    public static final DeadlinePolicy DEFAULT = new DeadlinePolicy("local-v1", Duration.ofHours(1));

    /** Explicit no-deadline default. Zero maximum means absence, not immediate expiry. */
    public static final DeadlinePolicy UNTIMED = new DeadlinePolicy("local-v1", Duration.ZERO);

    public DeadlinePolicy {
        if (profile == null || profile.isBlank()) throw new IllegalArgumentException("deadline profile required");
        Objects.requireNonNull(maximum, "maximum required");
        if (!maximum.isZero()) requireDuration(maximum);
    }

    /** Resolve an optional authored bound, without assigning an admission timestamp. */
    public Duration resolve(Duration authored) {
        if (authored == null) return maximum;
        requireDuration(authored);
        return maximum.isZero() || authored.compareTo(maximum) < 0 ? authored : maximum;
    }

    /** Origin is retained even when an authored bound is looser than this policy. */
    public String origin(Duration authored) {
        if (maximum.isZero()) return (authored == null ? "UNTIMED:" : "AUTHORED:") + profile;
        return authored == null ? "DEFAULT:" + profile
                : authored.compareTo(maximum) < 0 ? "AUTHORED:" + profile : "POLICY_CAP:" + profile;
    }

    private static void requireDuration(Duration duration) {
        Objects.requireNonNull(duration, "finite maximum required");
        try {
            if (duration.isNegative() || duration.isZero() || duration.toMillis() < 1
                    || duration.toNanos() < 1) throw new IllegalArgumentException("positive finite duration required");
        } catch (ArithmeticException ex) { throw new IllegalArgumentException("duration overflow", ex); }
    }
}
