package com.hamstrack.issue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * <p><strong>{@code body} is bounded at 10 000</strong> (HD-171 §4.3) — the same number as
 * {@code FieldValueService.MAX_TEXTAREA_LENGTH}, on purpose: one bound for a block of prose
 * this product stores, not one per door. {@code issue_comments.body} is {@code TEXT} and
 * overflows nothing, so this is a payload guard rather than a column guard.
 *
 * <p>It is the one guard in that group whose cost is <strong>superlinear</strong> in the
 * input, which is why it is not merely tidiness: at <em>every</em> {@code @} in the body,
 * {@code CommentService.parseMentions} walks every workspace member comparing a prefix — inside
 * a transactional write, over an input the caller chooses.
 *
 * <p><strong>This bound does not make that scan cheap; it caps one of its two factors.</strong>
 * The cost is O(occurrences-of-{@code @} × members), and nothing in a request bounds the member
 * count. What made the product of the two <em>expensive per step</em> was a lowercasing inside
 * the inner loop, hoisted out in the same ticket — see {@code parseMentions}, which carries the
 * numbers. Read this bound as "the body can no longer be arbitrarily long", not as "the mention
 * scan has been dealt with".
 *
 * <p>The literal is written {@code 10000} deliberately, so that a <em>future</em> column-width
 * scanner can read it — no scanner reads this field today. The one that exists,
 * {@code EmailLengthBoundTest}, applies its {@code max\s*=\s*(\d+)} regex only to declarations it
 * reaches from an {@code @Email}, so it never looks here. Keeping the form it reads costs nothing
 * and is what makes the field scannable later; {@code 10_000} would read as 10 and a symbolic
 * constant as no bound at all. What is meant to police the <em>value</em> is a behavioural test
 * (HD-171 §5.3), not a source scan.
 */
public record CreateCommentRequest(@NotBlank @Size(max = 10000) String body) {}
