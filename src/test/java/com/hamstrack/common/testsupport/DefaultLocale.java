package com.hamstrack.common.testsupport;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs the annotated test class or method with {@link java.util.Locale#setDefault(java.util.Locale)}
 * set to {@code value}, restored afterwards by {@link DefaultLocaleExtension}.
 *
 * <p><strong>What this is for.</strong> A case fold, a number format or a date format that reads
 * {@code Locale.getDefault()} produces a different answer on a container configured with a
 * different locale — identical code, identical data, different result, no error anywhere. That is
 * a defect no assertion made on an {@code en} machine can see, so the only test that can catch it
 * is one that names the hostile locale itself. {@code tr-TR} is the usual choice because it folds
 * {@code 'I'} to a dotless {@code 'ı'} (Azeri and Lithuanian are the same family of surprise).
 *
 * <p><strong>The hazard this annotation exists to contain.</strong> The default locale is
 * JVM-global and Surefire runs the whole suite in one fork with cached Spring contexts, so a test
 * that changes it and does not put it back poisons every class scheduled after it in that JVM —
 * and the symptom then surfaces in classes that touch nothing related. Restoration therefore
 * belongs in a callback that runs even when the test body throws, never at the end of the body;
 * that is the whole reason this is an extension and not three lines of setup. The annotation also
 * carries {@link ResourceLock}{@code (}{@link Resources#LOCALE}{@code )}, so if this suite is ever
 * run with parallel execution enabled, no other test can observe the borrowed locale.
 *
 * <p><strong>What it does not do.</strong> A Spring context is built before the extension's
 * callback runs and is cached across classes, so anything that reads a locale once at bean
 * construction keeps whatever the JVM was started with. This annotation changes what a
 * <em>request</em> sees, not what a context was born with.
 *
 * @param value an IETF BCP 47 language tag, e.g. {@code "tr-TR"}
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ExtendWith(DefaultLocaleExtension.class)
@ResourceLock(Resources.LOCALE)
public @interface DefaultLocale {

    String value();
}
