package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * <strong>409 {@code STORAGE_QUOTA_EXCEEDED}</strong> — this upload would take the workspace past
 * its attachment storage ceiling (HD-191 §5.5).
 *
 * <h2>Why 409, having considered the four alternatives</h2>
 * <ul>
 *   <li><strong>413 {@code CONTENT_TOO_LARGE}</strong> is about <em>this representation</em> being
 *       too large, and the file is very often well inside {@code app.attachments.max-file-size}.
 *       It is also already taken twice on this endpoint — {@code validateUpload} and
 *       {@code handleMaxUploadSize} both answer 413 "File is too large". Two refusals with
 *       opposite remedies must not share a status <em>and</em> a shape.</li>
 *   <li><strong>429</strong> implies waiting helps. Waiting never frees a byte, and a
 *       {@code Retry-After} here would be an instruction that cannot work — the argument
 *       {@code handleQueryTimeout} already makes for its own 422.</li>
 *   <li><strong>422 {@code UNPROCESSABLE_CONTENT}</strong> is this codebase's "well-formed,
 *       understood, and we will not process it", and it is close. It says nothing about
 *       <em>state</em>, and this refusal is entirely about the state of a resource other than the
 *       one in the request — which is what 409 is defined as.</li>
 *   <li><strong>507 Insufficient Storage</strong> (RFC 4918) is semantically closest of all and is
 *       a <strong>5xx</strong>: retried automatically by intermediaries and SDKs, and rendered by
 *       the SPA as a crash rather than as a sentence. It says "we failed" where we decided.</li>
 * </ul>
 *
 * <p>So: 409, carrying no retry semantics, matching what this codebase already answers for
 * state-blocked writes (archived project, in-use-on-delete, invitation already accepted), and
 * reusing the {@code errorType} convention so a client branches on a stable code rather than on a
 * sentence.
 *
 * <h2>What the message may say</h2>
 * <strong>It describes the situation and dispatches nobody</strong>, because this refusal has no
 * reader who is guaranteed to be able to act: a contributor cannot delete other people's
 * attachments ({@code attachment.delete} is own-only for most roles) and the space is very often
 * in a project they cannot see; "ask your administrator" is a dead end in both deployment models,
 * not one — on Cloud a workspace owner cannot raise an instance property, and on DC the reader may
 * not know who owns the {@code .env}. The project's rule is that <em>a refusal may only prescribe
 * an action its reader can perform</em>, and the honest consequence here is a sentence that
 * prescribes nothing. Deliberately not profile-branched: one sentence, true in both models.
 *
 * <h2>Why the numbers are in the body</h2>
 * A disclosure decision, not a UX one. The reader is a workspace member holding
 * {@code attachment.create} in a project of this workspace; the figures are a single tenant-wide
 * aggregate with <strong>no per-project resolution</strong>, so the most they say across a project
 * boundary is that somebody, somewhere in a workspace the reader already belongs to, has uploaded
 * a lot. A member who cannot be told how full the workspace is cannot tell "I am blocked" from
 * "the server is broken", which is the failure mode a quota nobody can see produces. The
 * <em>breakdown</em> — project names and volumes, which is real disclosure — is a different
 * endpoint behind {@code workspace.edit}. Precedent for publishing a workspace-scoped aggregate in
 * a refusal body: {@code RoleInUseException}.
 */
public class StorageQuotaExceededException extends AppException {

    /** @see #getErrorType() */
    public static final String STORAGE_QUOTA_EXCEEDED = "STORAGE_QUOTA_EXCEEDED";

    private final long quotaBytes;
    private final long usedBytes;
    private final long availableBytes;
    private final long fileBytes;

    public StorageQuotaExceededException(long quotaBytes, long usedBytes, long fileBytes) {
        super(message(quotaBytes, usedBytes, fileBytes), HttpStatus.CONFLICT);
        this.quotaBytes = quotaBytes;
        this.usedBytes = usedBytes;
        // Clamped at zero because usage can legitimately exceed the quota (an operator lowered it,
        // §6.9) and "-2.1 GB available" is a number no reader can act on either.
        this.availableBytes = Math.max(0, quotaBytes - usedBytes);
        this.fileBytes = fileBytes;
    }

    private static String message(long quotaBytes, long usedBytes, long fileBytes) {
        return "This workspace has used all of its attachment storage ("
               + human(usedBytes) + " of " + human(quotaBytes) + "). This file needs "
               + human(fileBytes) + ". Storage is freed by deleting attachments that are no "
               + "longer needed.";
    }

    /**
     * Up to one decimal place, binary units — the same reading the SPA gives the same numbers, so
     * a user comparing the sentence with the storage page does not see two different figures for
     * one fact. The raw integers ride along as extensions for anything that needs to compute.
     *
     * <p><strong>A trailing {@code .0} is trimmed, and that is the whole reason this is not a
     * bare {@code %.1f}.</strong> The quota is configured as a round number, so the figure this
     * renders most often is the quota itself: {@code 10.0 GB} here against {@code 10 GB} on the
     * adjacent line of the same panel is one fact printed two ways, and a reader who notices the
     * difference has to decide which of the two is the real ceiling. Fractional values keep their
     * decimal ({@code 3.4 GB}); only an exact one loses a digit that carries nothing.
     */
    private static String human(long bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        var text = unit == 0
                ? String.valueOf(bytes)
                : String.format(java.util.Locale.ROOT, "%.1f", value);
        if (text.endsWith(".0")) {
            text = text.substring(0, text.length() - 2);
        }
        return text + " " + units[unit];
    }

    /**
     * The {@code errorType} extension, following the convention {@code StrandedProjectsException},
     * {@code RoleInUseException} and {@code HqlParseException} already use: a client branches on a
     * stable code, never on a sentence. It matters more than usual here, because this endpoint now
     * answers <strong>two different 409s</strong> — archived project and quota — with opposite
     * remedies, and only one of them carries a code.
     */
    public String getErrorType() {
        return STORAGE_QUOTA_EXCEEDED;
    }

    public long getQuotaBytes() {
        return quotaBytes;
    }

    public long getUsedBytes() {
        return usedBytes;
    }

    public long getAvailableBytes() {
        return availableBytes;
    }

    public long getFileBytes() {
        return fileBytes;
    }
}
