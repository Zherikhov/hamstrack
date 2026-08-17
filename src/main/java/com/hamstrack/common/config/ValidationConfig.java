package com.hamstrack.common.config;

import jakarta.validation.MessageInterpolator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.validation.MessageInterpolatorFactory;
import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Locale;

/**
 * Pins bean-validation message interpolation to {@link Locale#ENGLISH}.
 *
 * <p><strong>Why.</strong> Constraint messages are part of the API contract — since the
 * {@code @Order} fix in {@code GlobalExceptionHandler} they are rendered into the
 * {@code detail} of every 400. Out of the box that text is <em>client-controlled</em>:
 * {@code LocalValidatorFactoryBean} always wraps the interpolator in
 * {@code LocaleContextMessageInterpolator}, which resolves the locale per call from
 * {@code LocaleContextHolder}; {@code DispatcherServlet} fills that from Boot's default
 * {@code AcceptHeaderLocaleResolver}, i.e. the caller's {@code Accept-Language} header,
 * falling back to the JVM/OS locale. Hibernate Validator ships bundles for 28 locales, so
 * the same request could answer in English, Russian or Japanese at the caller's choosing,
 * and a header-less {@code curl} got whatever locale the host happened to run in — which
 * differs between a dev box, CI and the DC/Cloud containers. That breaks the contract for
 * integrators and is a latent flaky-test generator.
 *
 * <p><strong>How.</strong> This replaces Boot's auto-configured {@code defaultValidator}
 * (same bean name and {@code ROLE_INFRASTRUCTURE} role, so
 * {@code PrimaryDefaultValidatorPostProcessor} still flags it primary) with an identical
 * one whose interpolator ignores the requested locale. Boot's own
 * {@link MessageInterpolatorFactory} interpolator is kept as the delegate, so
 * {@code MessageSource}-backed message resolution is unchanged; only the locale is fixed.
 *
 * <p>Deliberately <em>not</em> done two other ways: {@code spring.web.locale-resolver=fixed}
 * would change locale resolution application-wide for a validation-only problem, and a
 * {@code ValidationMessages.properties} override would only pin the constraint keys someone
 * remembered to list — any constraint added later (or contributed by a library) would
 * silently fall back to a localized bundle again.
 */
@Configuration
public class ValidationConfig {

    /**
     * Mirrors {@code ValidationAutoConfiguration.defaultValidator} (which backs off via
     * {@code @ConditionalOnMissingBean(Validator.class)}), plus the fixed locale. Static so
     * that this infrastructure bean never forces early instantiation of the configuration
     * class.
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static LocalValidatorFactoryBean defaultValidator(ApplicationContext applicationContext,
                                                      ObjectProvider<ValidationConfigurationCustomizer> customizers) {
        var factoryBean = new LocalValidatorFactoryBean();
        factoryBean.setConfigurationInitializer(configuration ->
                customizers.orderedStream().forEach(customizer -> customizer.customize(configuration)));
        var bootInterpolator = new MessageInterpolatorFactory(applicationContext).getObject();
        factoryBean.setMessageInterpolator(new FixedLocaleMessageInterpolator(bootInterpolator, Locale.ENGLISH));
        return factoryBean;
    }

    /**
     * Interpolates with a fixed locale, whatever locale it is handed. The three-arg
     * override is the one that matters: {@code LocaleContextMessageInterpolator} always
     * calls it with the request's locale, so a delegate configured with a default locale
     * would still be overridden — the argument has to be dropped here.
     */
    private record FixedLocaleMessageInterpolator(MessageInterpolator delegate, Locale locale)
            implements MessageInterpolator {

        @Override
        public String interpolate(String messageTemplate, Context context) {
            return this.delegate.interpolate(messageTemplate, context, this.locale);
        }

        @Override
        public String interpolate(String messageTemplate, Context context, Locale requestedLocale) {
            return this.delegate.interpolate(messageTemplate, context, this.locale);
        }
    }
}
