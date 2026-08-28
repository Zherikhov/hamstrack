package com.hamstrack.common.ratelimit;

import com.hamstrack.common.config.InviteProperties;
import com.hamstrack.common.observability.ProductMetrics.EmailType;
import com.hamstrack.common.observability.ProductMetrics.RateLimitKind;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * The invitation mailer's entry in the recipient-throttle policy map (HD-190 §6.6).
 *
 * <p><strong>This class is the shape HD-202 copies.</strong> Adding the per-address throttle to
 * forgot-password and resend-verification is two more {@link MailThrottlePolicy} beans (one per
 * {@link EmailType}), two {@code RecipientMailThrottle.requireAndRecord} calls in
 * {@code AuthService}, two properties — and deleting those two entries from
 * {@code MailThrottleCoverageTest.EXEMPT}, which is what makes the merge <em>verifiable</em> rather
 * than intended. No migration: {@code mail_send_events} carries {@code email_type} from day one for
 * exactly this reason.
 *
 * <p>Counts are per {@code EmailType}, so a reset flood cannot consume an invitation allowance or
 * the other way round. One table, one query shape, N configured ceilings.
 */
@Configuration
public class InviteMailThrottleConfig {

    @Bean
    public MailThrottlePolicy inviteMailThrottlePolicy(InviteProperties properties) {
        return new MailThrottlePolicy(
                EmailType.INVITE,
                Duration.ofMinutes(properties.recipientCooldownMinutes()),
                properties.maxPerRecipientPerDay(),
                RateLimitKind.INVITE_RECIPIENT_COOLDOWN,
                RateLimitKind.INVITE_RECIPIENT_DAILY,
                new InviteThrottleWording());
    }

    /**
     * §8.1 rows B and C, verbatim in intent.
     *
     * <p>Row B names the address — the caller's own past action, so it discloses nothing — and
     * deliberately <strong>never names a workspace</strong>: the earlier invitation may have come
     * from one the caller can no longer see. Row C is terse because every richer remedy would turn
     * the one bit of cross-tenant disclosure that ceiling accepts into a description of another
     * tenant's activity.
     */
    static final class InviteThrottleWording implements MailThrottleWording {

        @Override
        public String cooldown(String recipient, String wait, String addendum) {
            var message = new StringBuilder("You already invited ").append(recipient)
                    .append(" recently.");
            if (addendum != null && !addendum.isBlank()) {
                message.append(' ').append(addendum);
            }
            return message.append(" You can send another in ").append(wait).append(".").toString();
        }

        @Override
        public String recipientDaily(String wait) {
            // Not "…because other workspaces invited them", not "…they already have an invitation",
            // and not "ask them to accept one they already have". Each of those turns one bit into
            // two. Waiting IS the remedy here, so the terse form loses nothing.
            return "Invitations to this address are paused. Try again in " + wait + ".";
        }
    }
}
