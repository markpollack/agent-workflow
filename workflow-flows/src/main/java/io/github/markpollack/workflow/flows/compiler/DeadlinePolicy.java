package io.github.markpollack.workflow.flows.compiler;

import java.time.Duration;
import java.util.Objects;

/** Finite admission policy. Authored durations can tighten, but cannot extend, its maximum. */
public record DeadlinePolicy(String profile, Duration maximum) {
    public static final DeadlinePolicy DEFAULT = new DeadlinePolicy("local-v1", Duration.ofHours(1));

    public DeadlinePolicy {
        if (profile == null || profile.isBlank()) throw new IllegalArgumentException("deadline profile required");
        requireDuration(maximum);
    }

    /** Resolve an optional authored bound, without assigning an admission timestamp. */
    public Duration resolve(Duration authored) {
        if (authored == null) return maximum;
        requireDuration(authored);
        return authored.compareTo(maximum) < 0 ? authored : maximum;
    }

    /** Origin is retained even when an authored bound is looser than this policy. */
    public String origin(Duration authored) {
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
