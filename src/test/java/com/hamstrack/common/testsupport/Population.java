package com.hamstrack.common.testsupport;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * <strong>The one carrier of a population, its floor and its description</strong> (HD-296, D3).
 *
 * <p>Every method on {@link Doors} returns one of these, and every category test that consumes a
 * population writes {@code Doors.writeHandlers().floor(150)} and gets the same sentence the
 * harness's own test prints when the scan collapses. A floor is anti-vacuity, not change
 * detection: it catches a broken scan or a narrowed predicate, and a consumer that must notice a
 * single door leaving asserts the members instead. Raise a floor deliberately; never lower one to
 * make a run pass — the message below names the causes its own reader can act on: three of them for
 * a {@code Doors} population, and a caller whose floor is over a different subject passes its own
 * (HD-301).
 *
 * <p>Immutable. {@link #floor(int)} returns a copy that remembers the floor so that
 * {@link #describe()} can print it; {@link #filter(String, Predicate)} remembers the parent's size
 * so a narrowed population still says where it came from.
 *
 * @param <T> the member type; every {@code Doors} member type has a {@code describe()} of its own
 */
public final class Population<T> implements Iterable<T> {

    private final String name;
    private final List<T> members;
    private final Integer floor;
    private final Integer parentSize;
    private final String why;

    private Population(String name, List<T> members, Integer floor, Integer parentSize, String why) {
        this.name = Objects.requireNonNull(name, "name");
        this.members = List.copyOf(members);
        this.floor = floor;
        this.parentSize = parentSize;
        this.why = why;
    }

    /**
     * A population from a list the caller already holds — for probes, for the harness's own
     * positive control, and for a consumer that derives a second population (ids, names) from a
     * {@code Doors} one. Members keep the given order; {@code Doors} sorts before calling this.
     */
    public static <T> Population<T> of(String name, List<T> members) {
        return new Population<>(name, members, null, null, null);
    }

    public String name() {
        return name;
    }

    /** Immutable, in the order the population was built in (stable across JVMs for {@code Doors}). */
    public List<T> members() {
        return members;
    }

    public int size() {
        return members.size();
    }

    public Stream<T> stream() {
        return members.stream();
    }

    @Override
    public Iterator<T> iterator() {
        return members.iterator();
    }

    /**
     * Refuses a population smaller than {@code n} with the standard message, and otherwise returns
     * the population with the floor recorded for {@link #describe()}.
     *
     * @throws AssertionError when {@code size() < n} — thrown explicitly, never via a bare
     *         {@code assert} (HD-295)
     */
    public Population<T> floor(int n) {
        var floored = new Population<>(name, members, n, parentSize, why);
        if (members.size() < n) {
            throw new AssertionError(floorMessage(name, members.size(), n, floored.describe()));
        }
        return floored;
    }

    /**
     * A narrowed population that remembers where it came from: {@code describe()} prints
     * {@code "(filtered from <parent> — <why>)"} so a floor failure on the narrowed set still names
     * the size of the set it was cut from.
     */
    public Population<T> filter(String why, Predicate<? super T> keep) {
        Objects.requireNonNull(why, "why");
        var kept = members.stream().filter(keep).toList();
        return new Population<>(name, kept, null, members.size(), why);
    }

    /**
     * {@link #filter(String, Predicate)} by explicit members, refusing an exclusion that names a
     * member which is not live. An exclusion is a written decision about one member; when the
     * member is renamed or deleted the decision outlives its subject, and the next member that
     * happens to take the name inherits a reason nobody wrote about it.
     *
     * @throws AssertionError naming the first excluded member that is not in this population
     */
    public Population<T> excluding(String why, Set<T> excluded) {
        Objects.requireNonNull(why, "why");
        var stale = new ArrayList<String>();
        for (T member : excluded) {
            if (!members.contains(member)) {
                stale.add(String.valueOf(member));
            }
        }
        if (!stale.isEmpty()) {
            throw new AssertionError(("""
                    %s: `%s` excludes %d member(s) that are not live:
                      %s

                    An exclusion is a written decision that one member may be left out. When the \
                    member is renamed or deleted the decision outlives its subject, and the next \
                    member that happens to take that name inherits a reason nobody wrote about it. \
                    Delete the entry, or update it to the new name in the same commit that renamed \
                    the member. (%s)""").formatted(name, why, stale.size(),
                    String.join("\n  ", stale.stream().sorted().toList()), describe()));
        }
        return filter(why, m -> !excluded.contains(m));
    }

    /**
     * {@code "write handlers: 175 (floor 150)"}, or
     * {@code "write handlers: 61 (filtered from 175 — POST/PUT/PATCH only; floor 130)"}.
     */
    public String describe() {
        var notes = new ArrayList<String>();
        if (parentSize != null) {
            notes.add("filtered from " + parentSize + " — " + why);
        }
        if (floor != null) {
            notes.add("floor " + floor);
        }
        var suffix = notes.isEmpty() ? "" : " (" + String.join("; ", notes) + ")";
        return name + ": " + members.size() + suffix;
    }

    @Override
    public String toString() {
        return describe();
    }

    /**
     * Why a {@link Doors}-shaped population that shrank makes everything above it vacuous, and the
     * three causes of it. Passed to {@link #floorMessage(String, int, int, String, String, List)}
     * rather than baked into it, because a caller whose floor is over a <em>different</em> subject
     * would otherwise inherit a remedy it cannot perform (HD-301: the suite-coverage arms printed
     * "`mvnw clean compile`" and "fix Doors" at a reader whose real problem was a vitest
     * {@code include} line).
     */
    static final String POPULATION_WHY =
            "Every consumer of this population asserts \"nothing offends\", so a scan that stopped"
            + " seeing members reports clean for ever.";

    static final List<String> POPULATION_CAUSES = List.of(
            "the scan is broken — target/classes is stale or empty (`mvnw clean compile`), or"
            + " HamstrackApplication moved and the code-source root moved with it;",
            "a door really left — if that was deliberate, lower the floor in the SAME commit and"
            + " name the door in that commit's message;",
            "the predicate narrowed — a renamed annotation, a moved package, a stereotype Doors does"
            + " not recognise yet: fix Doors, never the floor.");

    /** The §4.5 floor message for a {@link Doors} population, over {@link #POPULATION_CAUSES}. */
    static String floorMessage(String name, int size, int floor, String description) {
        return floorMessage(name, size, floor, description, POPULATION_WHY, POPULATION_CAUSES);
    }

    /**
     * The §4.5 floor message over a caller's own causes: the frame is shared (so every floor in this
     * repository refuses in one voice) and the remedies are the caller's (so every one of them is an
     * action its reader can perform). Kept under 25 lines, which bounds {@code causes} at about ten.
     */
    static String floorMessage(String name, int size, int floor, String description, String why,
                               List<String> causes) {
        var message = new StringBuilder(name + ": the scan saw " + size + ", under the floor of "
                                        + floor + "." + System.lineSeparator()
                                        + System.lineSeparator() + why
                                        + " Exactly one of these is true:" + System.lineSeparator());
        var n = 1;
        for (var cause : causes) {
            message.append("  ").append(n++).append(". ").append(cause).append(System.lineSeparator());
        }
        return message.append("Do not lower a floor to make a run pass. (").append(description)
                .append(")").toString();
    }
}
