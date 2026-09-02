package com.hamstrack.common.mail;

import com.hamstrack.common.observability.ProductMetrics.EmailType;

/**
 * One email, as something the mail pool can hold <strong>and name</strong> (HD-207/HD-208).
 *
 * <h2>Why a task type exists at all, rather than {@code @Async}</h2>
 * Both losses this type was introduced for are discovered where only a {@code Runnable} is in
 * hand — a {@link java.util.concurrent.RejectedExecutionHandler} sees the submitted task, and
 * {@code ExecutorService.shutdownNow()} hands back the queue's contents — and the whole point of
 * discovering them is to write down <em>which</em> message was lost and to <em>whom</em>. A handler
 * that cannot answer that can only log an anonymous count.
 *
 * <p>{@code @Async} cannot supply the answer and this is not a matter of effort:
 * {@code AsyncExecutionAspectSupport.doSubmit} calls {@code executor.submit(task)}, so what sits in
 * the queue is a {@code FutureTask} wrapping an inaccessible lambda over the proxy's method
 * invocation. Nothing downstream can recover the recipient from it. That is why the mailers
 * dispatch through {@link MailDispatcher#dispatch} with one of these instead of carrying
 * {@code @Async}, and it is also why the pool is fed with {@code execute} rather than
 * {@code submit} — {@code ThreadPoolTaskExecutor.execute} passes the runnable through untouched
 * (no {@code TaskDecorator} is configured), so the queue really does hold these.
 *
 * <h2>Why it overrides {@code toString}</h2>
 * A rule about what a log line may <em>say</em> has to be checked against what it may
 * <em>carry</em>. Every {@code log.*} call on this path was written to pass the mail kind and
 * {@link MailAddresses#domainOf(String) the domain}, and the address still reached a shipped log —
 * through an <em>argument</em>. {@code ThreadPoolTaskExecutor.execute} wraps a rejection as
 * {@code new TaskRejectedException(executor, task, cause)}, whose message is
 * {@code "… did not accept task: " + task}; a record's generated {@code toString} renders every
 * component, so that message held the full address and the subject. {@link MailDispatcher} rethrows
 * that exception whenever {@link UndeliverableMail#record} writes nothing — on every best-effort
 * refusal and on every critical message over the hourly cap — and it is logged, message and stack
 * trace, by {@code AfterCommit.runQuietly}.
 *
 * <p>The redaction lives <strong>here</strong> rather than in what {@code MailDispatcher} throws,
 * because this method is reachable from anything that ever logs, formats or debug-prints a queued
 * task — including code that does not exist yet. The row remains the one place the address is kept.
 *
 * @param type      what kind of mail this is; {@link MailService#isCritical} decides whether losing
 *                  it earns a {@code failed_email} row
 * @param recipient the full address. It reaches the dead-letter <em>row</em> and never a log line —
 *                  see {@link UndeliverableMail} for where that line is drawn and why, and
 *                  {@link #toString()} for the argument that was carrying it past that line
 * @param subject   operator context on the dead-letter row, and nothing else
 * @param send      performs the send when a pool thread picks this up. It must not touch the
 *                  {@code EntityManager}: dispatch happens inside an {@code AfterCommit} effect and
 *                  the send itself happens on another thread entirely, so everything it needs is
 *                  already captured in locals by the mailer that built it.
 */
public record MailTask(EmailType type, String recipient, String subject, Runnable send)
        implements Runnable {

    @Override
    public void run() {
        send.run();
    }

    /**
     * The mail kind and the recipient's <strong>domain</strong> — never the local part, never the
     * subject.
     *
     * <p>The subject is dropped as well as the address. It is not personal data by itself, but it
     * is operator context that has a home ({@code failed_email.subject}) and no business in a log
     * line, and "Reset your Hamstrack password for &lt;x&gt;" said about a rejection is a fact about
     * a person either way. What is kept is what a reader can act on: which kind of mail was refused
     * and roughly where it was going.
     *
     * <p>Deliberately not the generated record form, and deliberately not {@code super.toString()}
     * either — see the class javadoc for the exception argument that made this the leak it was.
     */
    @Override
    public String toString() {
        return "MailTask[type=" + type + ", recipientDomain=" + MailAddresses.domainOf(recipient)
               + "]";
    }
}
