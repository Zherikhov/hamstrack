package com.hamstrack.common.testsupport;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Applies {@link DefaultLocale} and — the part that matters — always puts the JVM back.
 *
 * <p>The saved value is captured <em>before</em> the switch and restored from an
 * {@link ExtensionContext.Store}, so the restore is correct even when the test body throws, when an
 * assertion fails, or when a nested extension aborts: Jupiter invokes every {@code AfterEachCallback}
 * whose {@code BeforeEachCallback} ran. Nothing here may move into a test body — a locale left
 * behind is not a failure in the class that leaked it, it is a failure in some unrelated class
 * scheduled later in the same fork, which is the most expensive shape of test bug this repo has.
 *
 * <p><strong>A default locale is three fields, not one.</strong> {@code Locale} keeps a global
 * default plus a {@link Locale.Category#DISPLAY} and a {@link Locale.Category#FORMAT} default; the
 * one-argument setter writes all three, the two-argument setter writes exactly one, and plain
 * {@code getDefault()} reads the global — so a save/restore that names only the categories puts
 * back two of the three and leaks the borrowed locale through the one everything actually reads.
 * All three are captured here, restored global-first (which re-flattens the categories) and then
 * per category (which re-splits them, for a fork started with {@code -Duser.language} and
 * {@code -Duser.language.format} disagreeing).
 */
public final class DefaultLocaleExtension implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(DefaultLocaleExtension.class);
    private static final String GLOBAL = "saved.global";
    private static final String DISPLAY = "saved.display";
    private static final String FORMAT = "saved.format";

    /** What {@code Locale.forLanguageTag} will happily accept but nothing on the machine means. */
    private static final Set<String> ISO_LANGUAGES = Set.of(Locale.getISOLanguages());

    @Override
    public void beforeEach(ExtensionContext context) {
        var tag = resolveTag(context);
        var target = requireRealLocale(tag);
        var store = context.getStore(NAMESPACE);
        // Three fields, not two: setDefault(Category, …) writes ONLY that category, while plain
        // getDefault() reads a third, separate field. Saving the categories alone and restoring
        // them one by one leaves the global default holding the borrowed locale — which is a leak
        // that looks like a fix, and is how this extension behaved until its own test caught it.
        store.put(GLOBAL, Locale.getDefault());
        store.put(DISPLAY, Locale.getDefault(Locale.Category.DISPLAY));
        store.put(FORMAT, Locale.getDefault(Locale.Category.FORMAT));
        Locale.setDefault(target);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        var store = context.getStore(NAMESPACE);
        var global = store.remove(GLOBAL, Locale.class);
        var display = store.remove(DISPLAY, Locale.class);
        var format = store.remove(FORMAT, Locale.class);
        if (global == null) {
            return;   // beforeEach never got as far as saving; there is nothing to undo
        }
        // Global first: it resets both categories to itself, so the two category restores after it
        // are what re-splits them if the fork was started with them split.
        Locale.setDefault(global);
        Locale.setDefault(Locale.Category.DISPLAY, display);
        Locale.setDefault(Locale.Category.FORMAT, format);
    }

    /**
     * {@code forLanguageTag} never refuses: an ill-formed tag ({@code "tr_TR"}) resolves to ROOT,
     * and a well-formed but imaginary one ({@code "turkish"} — 7 letters is a legal language
     * subtag) resolves to a locale no data exists for. Both would run the test under a perfectly
     * dotted-i default and pass while proving nothing, so both are refused up front.
     */
    private Locale requireRealLocale(String tag) {
        var target = Locale.forLanguageTag(tag);
        var language = target.getLanguage();
        boolean real = !language.isEmpty()
                && (ISO_LANGUAGES.contains(language) || List.of(Locale.getAvailableLocales()).contains(target));
        if (!real) {
            throw new IllegalArgumentException(
                    "@DefaultLocale(\"" + tag + "\") does not name a locale this JVM knows — "
                            + "Locale.forLanguageTag() resolved it to '" + target
                            + "', so the test would run under a locale nobody chose. "
                            + "Use a BCP 47 tag with an ISO language code, e.g. \"tr-TR\".");
        }
        return target;
    }

    private String resolveTag(ExtensionContext context) {
        return AnnotationSupport.findAnnotation(context.getElement(), DefaultLocale.class)
                .or(() -> AnnotationSupport.findAnnotation(context.getTestClass(), DefaultLocale.class))
                .map(DefaultLocale::value)
                .orElseThrow(() -> new IllegalStateException(
                        "DefaultLocaleExtension is registered without @DefaultLocale on "
                                + context.getDisplayName()));
    }
}
