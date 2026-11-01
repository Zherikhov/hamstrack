package com.hamstrack.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <strong>This member cannot protect itself, so its containment is the set of classes that reach
 * it — and that set is declared here rather than described in prose</strong> (HD-340).
 *
 * <p>Some members are safe only because of <em>where they are called from</em>. A finder with no
 * tenancy predicate answers across every tenant; an append that stamps nothing answers for whoever
 * builds the row; a send is outside a cross-tenant lock only while its caller is the one that holds
 * the lock. None of those properties is visible at the member — the callee cannot see the request,
 * the tenant or the lock — so the thing that keeps the member safe is a fact about the call graph,
 * and a fact about the call graph written in a javadoc is held by whoever last read it.
 *
 * <p>This annotation makes that fact machine-readable. {@code CalledOnlyFromSealTest} enumerates
 * every marked member from production bytecode, derives the real call sites from the same bytecode,
 * and refuses a caller that is not declared here — so a new caller is a deliberate edit of this
 * declaration rather than an omission nobody notices.
 *
 * <p>Two seals held a claim of this shape before it existed and keep holding it, each for its own
 * reason, and a claim they hold names them instead of carrying this marker.
 * {@code MailAddressesThrottleKeyTest} asks a question this annotation <em>cannot</em> — its
 * subject is one overload of a name (see below). {@code UserEmailFinderSealTest}'s question is
 * expressible here as {@code callers = {}}, and it is left alone anyway: it reads source where this
 * reads bytecode, so it is a second witness over the same property rather than a thing to replace,
 * and nothing is gained by moving a working guard.
 *
 * <h2>Why a marker and not a prose sweep</h2>
 * The obvious population rule is "a javadoc that says who may call", and prose moves: a reworded
 * sentence leaves the population and takes its member's guard with it, reporting the same green.
 * So the marker is the population — it travels with the member through any rename, move or
 * rewording — and the prose shape is used only in the direction where being wrong is harmless:
 * every caller-set claim in a production comment must name a live test, which catches the claim
 * that arrives with no seal at all. That arm can lose a member to a rewording too; what it loses
 * there is an accusation, never a check.
 *
 * <h2>{@code callers} names classes, not call sites</h2>
 * Deliberately coarser than the sites the seal prints, for two reasons that hold independently.
 * The property that makes a caller admissible is a property of the <em>class</em> — this one is a
 * process and not a request, that one is the writer that stamps the tenant. And a site-level
 * allow-list would go stale on every rename of a method that merely happens to contain the call,
 * which is a diff nobody would think to bring here. A nested class of a declared caller counts as
 * that caller.
 *
 * <p>(An earlier version of this paragraph argued the same conclusion from a lambda compiling to a
 * synthetic {@code lambda$…} method. That premise is wrong for the walk the seal actually uses —
 * ArchUnit attributes an access inside a lambda to the <em>enclosing</em> code unit, measured
 * 2026-10-24 — and it is corrected rather than quietly dropped, because a reason that sounds
 * mechanical is exactly the kind a later reader reuses somewhere it is load-bearing.)
 *
 * <h2>An empty {@code callers} is a claim, not a default</h2>
 * {@code callers = {}} says no production class may reach this member at all — the shape of a
 * test-only overload or a finder kept for the suite. It is checked in exactly the same way, so it
 * cannot be the accidental value: there is no default, and omitting the attribute does not compile.
 *
 * <h2>The limit, stated: the walk is keyed on the method NAME</h2>
 * {@code ProductionBytecode.callSitesOfMethods} matches a call by its target owner and name, never
 * by its signature, so this annotation cannot separate two overloads — mark one and the seal
 * answers about both. The seal refuses an overloaded name outright rather than quietly answering
 * the wrong question. A member whose name is overloaded needs a seal that matches on the parameter
 * list ({@code MailAddressesThrottleKeyTest} is the worked example), and its javadoc names that
 * seal.
 *
 * @see com.hamstrack.common.config.Credential the same marker-over-name-shape argument, for a
 *      configuration component rather than a call site
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface CalledOnlyFrom {

    /**
     * Fully-qualified names of the production classes that may reach this member. Names rather than
     * {@code Class} literals so that a repository does not have to import the service that calls it
     * — and a name that stops being a caller is refused by the seal, so it cannot go stale
     * unnoticed in either direction.
     */
    String[] callers();

    /**
     * One sentence on what goes wrong when somebody else calls it. Printed by the refusal, because
     * the reader who trips this seal is in the calling file and cannot see this one.
     */
    String because();

    /**
     * The in-bounds path, named so that the refusal prescribes an action its reader can perform:
     * the scoped finder, the service that stamps the row, the ordering to keep.
     */
    String instead();

    /**
     * <strong>The subset of {@link #callers} that a web bean dispatches to, admitted by name.</strong>
     * Empty by default, and that default is the point: the commonest way to lose a containment like
     * this is not a new call site — the seal catches those — it is somebody <em>widening
     * {@code callers}</em> by one line to make their own call compile, in a diff where the query is
     * untouched and everything reads as documentation.
     *
     * <p>So admitting a request-dispatched caller takes a second line, here, where a reviewer
     * cannot miss it, and {@link #because} has to say why the member is safe to reach that way. For
     * a member whose whole claim is "nothing request-scoped may call it", writing a name here is
     * not a configuration choice: it contradicts the claim, and the claim is what has to change
     * first.
     *
     * <p><strong>Names, not a boolean</strong>, so that the exemption keeps checking after it is
     * granted. A flag switches the arm off for the member for ever, and the next
     * request-dispatched caller added to {@code callers} inherits a decision nobody made about it;
     * a name exempts one class and leaves every other one refused. The seal also refuses a name
     * here that is not in {@code callers}, and one that is no longer request-dispatched — an
     * exemption whose subject has moved on is a reason attached to whoever takes the name next.
     *
     * <p><strong>Reach, stated exactly:</strong> "dispatched to by a web bean" means
     * {@code Doors.webBeans()} and the classes they call directly — not "reachable from a request".
     * Transitive reachability covers most of this tree and discriminates nothing:
     * {@code SprintScopeLedger}, {@code DataSeeder}, {@code RolePermissionCache} and
     * {@code ProductMetrics} are all legitimately inside it. A caller two hops from a controller is
     * therefore NOT refused, and that residual is named in {@code CalledOnlyFromSealTest} rather
     * than papered over — together with the live figures, which that test measures on every run
     * instead of quoting.
     */
    String[] requestScopedCallers() default {};
}
