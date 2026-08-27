package com.hamstrack.common.mail;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.core.ResolvableType;
import org.springframework.data.repository.CrudRepository;

import java.lang.reflect.Method;
import java.util.Arrays;
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
 * <p>Four separate doors, because each fails a different way: inheritance adds row-returning
 * methods nobody typed; a declared method can return the entity in half a dozen generic shapes; a
 * new projection record is a row read wearing a different type; and a query filtered on the
 * submitted address is not a leak at all but the other defect this feature already shipped once —
 * counting spellings instead of inboxes.
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
     * Tripwire. Every assertion above is of the form "nothing in this set offends", so an empty set
     * passes all four while guarding nothing — and an interface that has been emptied, moved or
     * renamed produces exactly that. The number is not pinned (adding an aggregate is legitimate and
     * this file should not have an opinion about it); the emptiness is.
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

    private static String signature(Method method) {
        return method.getGenericReturnType().getTypeName() + " " + method.getName() + "(...)";
    }
}
