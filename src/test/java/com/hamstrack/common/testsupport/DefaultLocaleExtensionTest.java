package com.hamstrack.common.testsupport;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The harness testing itself, because a locale harness that borrows and forgets to give back is
 * worse than no harness at all: the damage lands in whatever class Surefire schedules next in the
 * same fork, which will have nothing to do with locales and will look like a bug in itself.
 *
 * <p>Method order is pinned rather than left to the default, because "the default was restored
 * between methods" is a statement about a sequence and cannot be asserted without one.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DefaultLocaleExtensionTest {

    /** Captured before any test runs, so it is the value the fork started with. */
    private static final Locale AT_CLASS_LOAD = Locale.getDefault();

    @Test
    @Order(1)
    @DefaultLocale("tr-TR")
    void borrowsTheRequestedLocaleForTheAnnotatedMethod() {
        assertThat(Locale.getDefault().getLanguage()).isEqualTo("tr");
        assertThat(Locale.getDefault(Locale.Category.FORMAT).getLanguage()).isEqualTo("tr");
        assertThat(Locale.getDefault(Locale.Category.DISPLAY).getLanguage()).isEqualTo("tr");
    }

    @Test
    @Order(2)
    void givesItBackBeforeTheNextMethodInTheSameFork() {
        assertThat(Locale.getDefault())
                .as("the previous method borrowed tr-TR; a leak here would surface in an unrelated class")
                .isEqualTo(AT_CLASS_LOAD);
    }

    /**
     * The premise behind the extension's up-front guard. {@code forLanguageTag} never refuses, and
     * it fails in two different ways, which is why one check is not enough: an ill-formed tag
     * collapses to ROOT, while a well-formed imaginary one is accepted verbatim because any 2–8
     * letter run is a legal language subtag. Either would run a locale test under a perfectly
     * dotted-i default and pass while proving nothing.
     */
    @Test
    @Order(3)
    void neitherWayOfMisspellingATagIsRefusedByTheJdkItself() {
        assertThat(Locale.forLanguageTag("tr_TR").getLanguage())
                .as("ill-formed: an underscore is not BCP 47, so this silently becomes ROOT")
                .isEmpty();
        assertThat(Locale.forLanguageTag("turkish").getLanguage())
                .as("well-formed but imaginary: parsed happily, and no locale data exists for it")
                .isEqualTo("turkish");
        assertThat(Set.of(Locale.getISOLanguages()))
                .as("which is what the extension's second check consults")
                .doesNotContain("turkish")
                .contains("tr");
        assertThat(Locale.forLanguageTag("tr-TR").getLanguage()).isEqualTo("tr");
    }
}
