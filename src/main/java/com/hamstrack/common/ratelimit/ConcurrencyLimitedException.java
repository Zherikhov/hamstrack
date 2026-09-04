package com.hamstrack.common.ratelimit;

/**
 * <strong>429 — too many requests of this kind are running AT ONCE</strong> (HD-182). Two
 * refusals, one status, told apart by {@code errorType}, because the remedies differ and are not
 * interchangeable.
 *
 * <p>There are now three ways to be refused on one path, and a caller must be able to tell them
 * apart:
 *
 * <table>
 *   <caption>the three refusals of the expensive-read surface</caption>
 *   <tr><th>refusal</th><th>meaning</th><th>what the caller can do</th><th>{@code Retry-After}</th></tr>
 *   <tr><td>{@code 429}, no {@code errorType}</td><td>you have spent your minute's budget</td>
 *       <td>stop asking for up to a minute</td><td>seconds until the window rolls (1–60)</td></tr>
 *   <tr><td>{@code 429 TOO_MANY_IN_FLIGHT}</td><td><em>your own</em> requests are already
 *       occupying your share</td><td>let one finish; a UI may retry once</td><td>1</td></tr>
 *   <tr><td>{@code 429 EXPENSIVE_SURFACE_BUSY}</td><td>the instance's expensive-read share is
 *       full; you may hold none of it</td><td>retry shortly — there is nothing else to do</td>
 *       <td>1</td></tr>
 * </table>
 *
 * <p><strong>429 for both, not 503.</strong> 5xx is the class intermediaries and SDKs auto-retry,
 * and auto-retrying a saturation refusal re-spends the resource that is already scarce — the
 * argument {@code GlobalExceptionHandler.handleStatementBudget} already records, applied
 * unchanged. 429 is also what this codebase already means by "you asked for more of this than you
 * may have".
 *
 * <p><strong>{@code Retry-After: 1}, and computing it the way the minute budget does would be
 * wrong by up to 60×.</strong> The obstacle here is another in-flight request that ends in at most
 * {@code statement_timeout} and typically in tens of milliseconds; the obstacle there is a clock.
 * {@code 1} is the value the lock-contention 409 already uses for exactly this reason — the rival
 * finishes and the retry succeeds — and it is the one honest hint. A minute-window
 * {@code Retry-After} on a queue that clears in 50 ms would make a correct client sit out a whole
 * minute.
 *
 * <p><strong>The two sentences prescribe different actions, and neither prescribes one its reader
 * cannot perform.</strong> {@link #tooManyInFlight} may name the caller's own concurrency, because
 * that is the caller's own conduct; it must not say "the server is busy", which would be false and
 * would send the reader to an operator. {@link #surfaceBusy} must <em>not</em> tell the reader to
 * reduce their own concurrency — they may hold exactly one permit, or none — and must not tell
 * them to contact an administrator, which on Cloud is a dead end. Its sentence states a condition
 * and offers a retry. This project has shipped a refusal prescribing an unreachable action three
 * times; getting the two the wrong way round is the fourth way to do it.
 *
 * <p><strong>{@code EXPENSIVE_SURFACE_BUSY} discloses that somebody else is busy, and that is an
 * accepted trade.</strong> It is a load signal weaker than the latency the same caller can already
 * measure, and it carries no count, no tenant, no principal and no number. The tenancy contract is
 * untouched for {@link PerPrincipalMinuteBudget}'s reason: the refusal is byte-for-byte identical
 * for a real workspace, a nonexistent one and somebody else's, so it cannot answer a question about
 * a resource the caller cannot see.
 *
 * <p>Extends {@link RateLimitedException} so the {@code Retry-After} envelope is not written twice;
 * {@code GlobalExceptionHandler} has a more specific handler that adds the {@code errorType}.
 */
public class ConcurrencyLimitedException extends RateLimitedException {

    /** @see #tooManyInFlight(String) */
    public static final String TOO_MANY_IN_FLIGHT = "TOO_MANY_IN_FLIGHT";

    /** @see #surfaceBusy(String) */
    public static final String EXPENSIVE_SURFACE_BUSY = "EXPENSIVE_SURFACE_BUSY";

    /**
     * One second, like the lock-contention 409 and unlike the minute budget: what the caller is
     * waiting for is a request that ends, not a window that rolls.
     */
    public static final int RETRY_AFTER_SECONDS = 1;

    private final String errorType;

    private ConcurrencyLimitedException(String message, String errorType) {
        super(message, RETRY_AFTER_SECONDS);
        this.errorType = errorType;
    }

    /**
     * The caller's own share is full. {@code noun} is what the caller's own in-flight work is
     * called — {@code "requests"} for the expensive-read surface.
     */
    public static ConcurrencyLimitedException tooManyInFlight(String noun) {
        return new ConcurrencyLimitedException(
                "Too many of your " + noun + " are running at once — wait for one to finish.",
                TOO_MANY_IN_FLIGHT);
    }

    /**
     * The whole surface is full and the caller may hold none of it. {@code noun} is what the
     * surface's work is called — {@code "expensive requests"} here.
     *
     * <p>No figure, deliberately: a count would vary with other tenants' behaviour, and the reader
     * could do nothing with it.
     */
    public static ConcurrencyLimitedException surfaceBusy(String noun) {
        return new ConcurrencyLimitedException(
                "This instance is running as many " + noun + " as it can at once. Try again in a "
                + "moment.", EXPENSIVE_SURFACE_BUSY);
    }

    public String getErrorType() {
        return errorType;
    }
}
