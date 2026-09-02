package com.hamstrack.common.mail;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.core.ResolvableType;
import org.springframework.data.repository.CrudRepository;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>Rows never leave {@link MailSendEventRepository}</strong> (HD-190 sections 7.1 / 9.2,
 * {@code docs/adr/0015-recipient-keyed-mail-throttle-persisted.md}).
 *
 * <p>This is the invariant the ADR's accepted trade-off is <em>conditioned on</em>, and until now
 * it was prose in four javadocs. {@code mail_send_events} is the one table in the product that
 * holds recipient addresses and is <strong>not workspace-scoped</strong> — the exact shape of this
 * project's top bug class. What keeps it out of that class is not a membership check, because there
 * is nothing to check against: it is that no method here can hand a caller a row. Everything it
 * answers is of the form "how many, and when", already narrowed to one recipient key and one
 * {@code email_type} by the query itself, and returned as a {@link MailSendCounts} that carries no
 * address and no id.
 *
 * <p><strong>Why reflection and not review.</strong> The single line that would break it is a
 * {@code findByRecipientEmail} somebody adds while investigating an incident — plausible, useful,
 * and one commit away from a DTO. Spring Data would derive it silently and it would work. So the
 * question is asked of the interface itself, at the commit that adds the method.
 *
 * <p><strong>Since HD-158 it seals both directions, and the second one is not about a leak.</strong>
 * Four of the doors below are ways a row could <em>leave</em>; the fifth is a way a row could be
 * <em>removed</em>. A delete keyed on a recipient or a sender refunds a ceiling — it hands back a
 * send slot rather than an address — so it would pass all four of the others while defeating the
 * control this table exists to be.
 *
 * <p>Five separate doors, because each fails a different way: inheritance adds row-returning
 * methods nobody typed; a declared method can return the entity in half a dozen generic shapes; a
 * new projection record is a row read wearing a different type; a query filtered on the submitted
 * address is not a leak at all but the other defect this feature already shipped once — counting
 * spellings instead of inboxes; and a delete keyed on anything but age is a refund.
 */
class MailSendEventRepositorySealTest {

    /**
     * What an aggregate may be. Not an allow-list of convenience: every entry here is a value that
     * cannot carry a row out — two counts and the {@link MailSendCounts} projection, whose own
     * fields are already narrowed to one key.
     */
    private static final Set<Class<?>> AGGREGATE_RETURN_TYPES =
            Set.of(long.class, int.class, Long.class, Integer.class, void.class,
                   MailSendCounts.class);

    private static final String WHY = """

            mail_send_events is NOT workspace-scoped and it holds recipient addresses, so the only \
            thing keeping it out of this project's top bug class is that rows never leave this \
            interface. There is no membership to check here — the table deliberately has no \
            workspace scope and no foreign keys, because its whole purpose is to outlive the \
            invite, the workspace and the account it describes.

            If you need to look at rows, query the database directly: that is what workspace_id is \
            written for, and it is written and never queried on purpose. If you believe a \
            row-returning method is genuinely needed, the ADR's trade-off has to be re-argued \
            first (docs/adr/0015-recipient-keyed-mail-throttle-persisted.md) — it is granted ON \
            this invariant, not alongside it.
            """;

    /**
     * {@code extends JpaRepository} would be a five-character change that adds {@code findAll()},
     * {@code findById()} and {@code save()} — three row-returning methods nobody wrote and nobody
     * reviews. The {@code RoleRepository} precedent: a repository whose safety depends on what it
     * does <em>not</em> offer extends the bare {@code Repository} marker, so a stray
     * {@code findById} does not compile.
     */
    @Test
    void theRepositoryDoesNotInheritRowReturningMethods() {
        assertThat(JpaRepository.class.isAssignableFrom(MailSendEventRepository.class))
                .as("MailSendEventRepository must not extend JpaRepository — it would inherit "
                    + "findAll/findById/save, i.e. three ways to read a row, none of which anybody "
                    + "typed." + WHY)
                .isFalse();
        assertThat(CrudRepository.class.isAssignableFrom(MailSendEventRepository.class))
                .as("nor CrudRepository, for the same reason." + WHY)
                .isFalse();
    }

    /**
     * Asked of every public method the interface <em>offers</em> — declared and inherited alike, so
     * this holds whichever way a row-returning method arrives.
     *
     * <p><strong>Resolved against this interface rather than read raw, and the difference is the
     * whole test.</strong> {@code Method.getGenericReturnType()} on an inherited method hands back
     * the declaring interface's own type <em>variable</em> — {@code CrudRepository.findAll} is
     * {@code List<T>}, and {@code save} is {@code <S extends T> S} — so a plain
     * {@code == MailSendEvent.class} check sees {@code T} bounded by {@code Object} and waves
     * through the single cheapest way to lose this invariant, which is adding one word to the
     * {@code extends} clause. {@link ResolvableType#forMethodReturnType(Method, Class)} substitutes
     * {@code T} for the entity, which is what a caller actually gets. Verified by regression: the
     * raw-reflection version of this method stayed green while
     * {@code extends JpaRepository} was in the file.
     */
    @Test
    void noMethodCanReturnAMailSendEvent() {
        var leaking = new LinkedHashSet<String>();
        for (var method : offeredMethods()) {
            var returnType = ResolvableType.forMethodReturnType(method,
                    MailSendEventRepository.class);
            if (mentions(returnType, MailSendEvent.class)) {
                leaking.add(signature(method));
            }
        }

        assertThat(leaking)
                .as("these methods hand a MailSendEvent back to their caller, in some shape "
                    + "(bare, Optional, List, Page, Stream, or an <S extends MailSendEvent> type "
                    + "variable — all of them count)." + WHY)
                .isEmpty();
    }

    /**
     * Every method here answers only "how many, and when". Stated as an allow-list of return types
     * rather than as the absence of the entity, because those are different claims: a
     * {@code List<Object[]>} or a new projection record carrying {@code recipientEmail} would pass
     * the sibling test above and still be a row read wearing a different type.
     */
    @Test
    void everyMethodReturnsAnAggregate() {
        var offending = new LinkedHashSet<String>();
        for (var method : offeredMethods()) {
            if (!AGGREGATE_RETURN_TYPES.contains(method.getReturnType())) {
                offending.add(signature(method));
            }
        }

        assertThat(offending)
                .as("this repository answers only how many and when. A new return type is a new "
                    + "shape of read, and a projection record is not automatically safe just "
                    + "because it is not the entity — if it carries recipient_email it is a row "
                    + "read with better manners. Allowed today: %s." + WHY, AGGREGATE_RETURN_TYPES)
                .isEmpty();
    }

    /**
     * <strong>The predicate column is {@code recipientKey}, never {@code recipientEmail}.</strong>
     * Not a tenancy property — a security one of the other kind: {@code recipient_email} is one
     * <em>spelling</em>, and the ceilings exist to count one <em>inbox</em>. A count filtered on
     * the submitted address reads zero for {@code victim+1@} the moment the attacker types a
     * {@code +}, which is how the first cut of this feature came to be decorative. It is checked
     * here, on the queries, because that is where the choice is actually made.
     *
     * <p>Derived query names are covered by the same assertion: a {@code countByRecipientEmail...}
     * carries the column in its own name.
     */
    @Test
    void noQueryPredicatesOnTheSubmittedAddress() {
        var offending = new LinkedHashSet<String>();
        for (var method : offeredMethods()) {
            var query = method.getAnnotation(Query.class);
            var haystack = (method.getName() + " "
                            + (query == null ? "" : query.value())).toLowerCase(Locale.ROOT);
            if (haystack.contains("recipientemail") || haystack.contains("recipient_email")) {
                offending.add(signature(method));
            }
        }

        assertThat(offending)
                .as("a ceiling filtered on the SUBMITTED address counts spellings, not inboxes: "
                    + "victim+1@, victim+2@ and v.i.c.t.i.m@googlemail.com are distinct strings "
                    + "that reach one human, so the count reads zero and the ceiling is "
                    + "decorative. Filter on recipientKey (MailAddresses.throttleKey), which is "
                    + "what the index is on and what the entity comment calls the ONLY thing "
                    + "counted. recipient_email is stored to be echoed by a refusal and read by "
                    + "an operator — never to be a predicate.")
                .isEmpty();
    }

    /**
     * <strong>The only thing a row here may be deleted for is being old</strong> (HD-158 §5.4).
     *
     * <p>The four assertions above are all about a row <em>leaving</em>, and none of them would
     * fail a refund. A {@code @Modifying int deleteByRecipientKeyAndSenderUserId(…)} returns an
     * aggregate, hands back no entity, inherits nothing, and filters on {@code recipientKey} rather
     * than on the submitted address — it passes every one of them while making the ceilings
     * resettable on demand. Asserted in that direction rather than assumed: exactly that method was
     * added to the interface, this test went red, and the other five stayed green.
     *
     * <p><strong>Why the property needs a test and not a sentence.</strong> {@code mail_send_events}
     * exists because the first cut of the invite cooldown derived its state from
     * {@code workspace_invites}, and three paths delete a row there — one of them pressed by the
     * victim, since {@code declineInvite} DELETEs. {@code V21}'s header named the future hazard
     * exactly: correctness that depends on the continued <em>absence</em> of a delete endpoint
     * breaks silently in a future ticket. HD-158 is that ticket — it ships
     * {@code DELETE /api/workspaces/{ws}/invites/{inviteId}} — and the answer it gives (a
     * withdrawal refunds nothing) is held on this end by nothing but the absence of a method. This
     * is where that absence becomes structural.
     *
     * <p>Phrased as a whitelist of predicate <em>shapes</em> rather than as a list of forbidden
     * columns, because a deny-list goes stale one column before the table does. The rule is that a
     * row is removed for its <strong>age</strong>, never for being <strong>about</strong>
     * somebody — so a delete that names no predicate at all fails too: it names no age, and it
     * takes every row.
     *
     * <p><strong>Two shapes are legal, not one, and the second was added under protest by this
     * test</strong> (HD-202 review). {@code deleteAnonymousCreatedBefore} sweeps rows nobody signed
     * for on a much shorter clock than the invite retention — they carry no
     * {@code sender_user_id} to answer <em>who</em> with, and they are the only rows an
     * unauthenticated stranger can force this instance to write. Its predicate therefore mentions
     * {@code senderUserId}, and the first version of that method failed here, correctly: the
     * assertion as written could not tell "delete the rows belonging to <em>this</em> sender" from
     * "delete the rows belonging to <em>no</em> sender".
     *
     * <p>What separates them, and what is now asserted instead of the cruder rule: <strong>a
     * property other than {@code createdAt} may appear only in a NULLITY test, and the method may
     * take no parameter but the cutoff.</strong> Both halves are load-bearing. A nullity test names
     * a <em>class</em> of row and cannot be aimed at a person — there is no value to supply — and
     * the parameter check is what stops the loophole of aiming it anyway through a second argument.
     * A refund requires naming somebody; neither half lets you.
     *
     * <p><strong>Be exact about what this rule is: it is literally WEAKER than the equality it
     * replaced</strong> — the old assertion admitted only predicates on {@code createdAt}, and this
     * one admits a strict superset of those. What is true, and what it was chosen for, is that it
     * is far stronger than the alternative it was weighed against: a whitelist naming
     * {@code deleteAnonymousCreatedBefore}. A name stops checking the moment it matches; the
     * predicate rule keeps checking every delete for ever, including the ones written after
     * everybody who remembers this paragraph has left. "Stronger" without a stated comparand is the
     * kind of sentence this project keeps having to correct.
     *
     * <p><strong>The residual, not reachable today.</strong> {@link #propertiesMentionedIn} reflects
     * over {@link MailSendEvent}'s FIELDS, so it can only recognise a column the entity maps. A
     * native delete naming an unmapped column — one added by a migration and never mapped, or a
     * database-side one — mentions nothing this method can see, so the nullity half of the rule
     * looks at an empty set and the predicate reads as harmless. Every column of
     * {@code mail_send_events} is mapped today and every delete here is JPQL, which is why this is
     * a note rather than a second assertion; a native delete arriving in this interface is the
     * moment to make it one.
     */
    @Test
    void noDeleteMayBeKeyedOnAnythingButAge() {
        var offending = new LinkedHashMap<String, Set<String>>();
        for (var method : offeredMethods()) {
            if (!isDelete(method)) {
                continue;
            }
            var predicate = predicateOf(method);
            var keyedOn = propertiesMentionedIn(predicate);
            if (!removesRowsOnlyForBeingOld(method, predicate, keyedOn)) {
                offending.put(signature(method), keyedOn);
            }
        }

        assertThat(offending)
                .as("a delete keyed on a recipient, a sender or a workspace is a refund; the "
                    + "ceilings then reset on demand and HD-190 is defeated by "
                    + "invite -> revoke -> invite — two legitimate calls, no exploit, one extra "
                    + "HTTP request per message. Only the retention sweep may remove a row here, "
                    + "and it removes rows for being OLD (createdAt), never for being ABOUT "
                    + "somebody: a revocation may free stock — outstanding rows, a uniqueness "
                    + "slot, a stock cap — and never flow — sends, cooldowns, daily ceilings. "
                    + "Deleting the record of an offer does not delete the record of a delivery. "
                    + "Each offender maps to the entity properties its predicate mentions; an "
                    + "empty set means it names no predicate at all, which takes every row. A "
                    + "property other than createdAt is allowed ONLY as a nullity test on a method "
                    + "whose sole parameter is the cutoff — that names a CLASS of row (rows nobody "
                    + "signed for) and cannot be aimed at a person, because there is no value to "
                    + "supply. If yours takes a second parameter, it can be aimed, and it is a "
                    + "refund."
                    + WHY)
                .isEmpty();
    }

    /**
     * The whitelist itself. A delete qualifies when it is bounded by {@code createdAt}, every other
     * property it mentions appears only as {@code IS [NOT] NULL}, and the method accepts nothing
     * but the cutoff.
     *
     * <p>The parameter count is not belt-and-braces: a nullity test alone would still permit
     * {@code delete … where senderUserId is null and recipientKey = :key}, which mentions
     * {@code recipientKey} — caught by the nullity rule — but also permits subtler shapes as the
     * predicate grammar grows. One parameter, and it is an {@link Instant}, is the property that
     * makes "cannot be aimed at anybody" true by construction rather than by inspection.
     */
    private static boolean removesRowsOnlyForBeingOld(Method method, String predicate,
                                                      Set<String> keyedOn) {
        if (!keyedOn.contains("createdAt")) {
            return false;
        }
        if (keyedOn.size() == 1) {
            return true;
        }
        if (method.getParameterCount() != 1
            || !Instant.class.isAssignableFrom(method.getParameterTypes()[0])) {
            return false;
        }
        return keyedOn.stream()
                .filter(property -> !property.equals("createdAt"))
                .allMatch(property -> onlyTestedForNullity(predicate, property));
    }

    /**
     * Whether every mention of {@code property} in {@code predicate} is a nullity test. Both sides
     * are already normalised to words by {@link #words(String)}, so a JPQL
     * {@code e.senderUserId IS NULL} and a native {@code sender_user_id IS NULL} read alike.
     */
    private static boolean onlyTestedForNullity(String predicate, String property) {
        var phrase = words(property);
        var haystack = " " + predicate + " ";
        int mentions = 0;
        int nullityTests = 0;
        for (int at = haystack.indexOf(" " + phrase + " "); at >= 0;
             at = haystack.indexOf(" " + phrase + " ", at + 1)) {
            mentions++;
            var rest = haystack.substring(at + phrase.length() + 2);
            if (rest.startsWith("is null") || rest.startsWith("is not null")) {
                nullityTests++;
            }
        }
        return mentions > 0 && mentions == nullityTests;
    }

    /**
     * Tripwire. Every assertion above is of the form "nothing in this set offends", so an empty set
     * passes every one of them while guarding nothing — and an interface that has been emptied,
     * moved or renamed produces exactly that. The number is not pinned (adding an aggregate is
     * legitimate and this file should not have an opinion about it); the emptiness is.
     */
    @Test
    void theSealIsNotGuardingAnEmptyInterface() {
        assertThat(offeredMethods())
                .as("MailSendEventRepository offers no methods at all, so every assertion in this "
                    + "file is vacuously true — the interface moved or was emptied")
                .isNotEmpty();
    }

    // ------------------------------------------------------------------ reflection helpers

    /** Every public method the interface offers a caller: declared plus inherited, minus Object's. */
    private static List<Method> offeredMethods() {
        return Arrays.stream(MailSendEventRepository.class.getMethods())
                .filter(m -> m.getDeclaringClass() != Object.class)
                .toList();
    }

    /**
     * Whether {@code target} appears anywhere in {@code type} — as the type itself, as an array
     * component, or inside any generic argument at any depth. So {@code MailSendEvent},
     * {@code Optional<MailSendEvent>}, {@code List<MailSendEvent>}, {@code Page<MailSendEvent>} and
     * {@code Stream<? extends MailSendEvent>} all count, which is the point: the shape a row leaves
     * in is not the property, leaving is.
     */
    private static boolean mentions(ResolvableType type, Class<?> target) {
        var resolved = type.resolve();
        if (resolved != null && target.isAssignableFrom(resolved)) {
            return true;
        }
        if (type.isArray() && mentions(type.getComponentType(), target)) {
            return true;
        }
        return Arrays.stream(type.getGenerics()).anyMatch(generic -> mentions(generic, target));
    }

    /**
     * Whether this method removes rows — in either spelling. An explicit {@code @Query} answers for
     * itself (JPQL or native, both open with the verb); otherwise Spring Data derives one from the
     * name, and {@code delete…}/{@code remove…} are the two prefixes it derives a delete from.
     */
    private static boolean isDelete(Method method) {
        var query = method.getAnnotation(Query.class);
        if (query != null) {
            return words(query.value()).startsWith("delete ");
        }
        return method.getName().startsWith("delete") || method.getName().startsWith("remove");
    }

    /**
     * The part that decides <em>which</em> rows go, normalised to words: everything after
     * {@code WHERE} for a declared query, everything after {@code By} for a derived one. Empty for
     * a delete that narrows nothing — the {@code deleteAll} shape, treated as an offence rather
     * than as an absence, because a delete with no predicate names no age and takes every row.
     */
    private static String predicateOf(Method method) {
        var query = method.getAnnotation(Query.class);
        var statement = words(query == null ? method.getName() : query.value());
        var opener = query == null ? " by " : " where ";
        var at = statement.indexOf(opener);
        return at < 0 ? "" : statement.substring(at + opener.length());
    }

    /**
     * Which entity properties a predicate mentions, asked of {@link MailSendEvent}'s own fields —
     * inherited ones included, {@code createdAt} being one — rather than of a hand-kept list of
     * column names, so a property added to the entity is covered on the day it is added.
     *
     * <p>Every spelling of every field is covered because both sides are normalised identically: a
     * JPQL {@code e.createdAt}, a native {@code created_at} and a derived
     * {@code …ByCreatedAtBefore} all reduce to the words {@code created at}.
     */
    private static Set<String> propertiesMentionedIn(String predicate) {
        var haystack = " " + predicate + " ";
        var mentioned = new LinkedHashSet<String>();
        // Longest phrase first, and each match is consumed: senderUserId has to claim
        // "sender user id" before the bare id field can match its tail, or every sender-keyed
        // delete would also report a predicate on a primary key it never mentions.
        var byLengthDescending = entityFields().stream()
                .sorted(Comparator.comparingInt((String field) -> words(field).length()).reversed())
                .toList();
        for (var field : byLengthDescending) {
            var phrase = " " + words(field) + " ";
            if (haystack.contains(phrase)) {
                mentioned.add(field);
                haystack = haystack.replace(phrase, " ");
            }
        }
        return mentioned;
    }

    /**
     * Lower-cased words, split at every non-alphanumeric run and at every camelCase hump, so the
     * two sides of a comparison can be written in whichever spelling their own layer uses.
     */
    private static String words(String text) {
        return text.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9]+", " ")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    /** Every instance field of the entity, up the hierarchy through {@code CreatedOnlyEntity}. */
    private static List<String> entityFields() {
        var names = new ArrayList<String>();
        for (Class<?> type = MailSendEvent.class; type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (var field : type.getDeclaredFields()) {
                if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                    names.add(field.getName());
                }
            }
        }
        return names;
    }

    private static String signature(Method method) {
        return method.getGenericReturnType().getTypeName() + " " + method.getName() + "(...)";
    }
}
