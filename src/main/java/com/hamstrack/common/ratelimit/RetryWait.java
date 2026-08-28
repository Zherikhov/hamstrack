package com.hamstrack.common.ratelimit;

/**
 * How a wait is spelled in a 429 the user reads (HD-190 §8.1).
 *
 * <p>The {@code Retry-After} header is always seconds — that is the wire contract and it is
 * unchanged. This is the other half: the same number in the {@code detail}, in the unit a person
 * thinks in. A daily ceiling's honest wait can be hours, and "retry in 24 831s" reads as a fault
 * rather than as a wait.
 */
public final class RetryWait {

    private RetryWait() {
    }

    /**
     * A short human phrase for a wait, e.g. {@code "less than a minute"}, {@code "42 minutes"},
     * {@code "about 7 hours"}.
     *
     * <p>Deliberately coarse and deliberately never a clock time. A countdown would go stale in the
     * message the moment it was rendered, and a wall-clock time would need the reader's timezone —
     * which a problem+json detail does not have.
     */
    public static String describe(long seconds) {
        if (seconds < 60) {
            return "less than a minute";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        long hours = (minutes + 59) / 60;
        return "about " + hours + (hours == 1 ? " hour" : " hours");
    }
}
