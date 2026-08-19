package com.hamstrack.common.config;

import com.hamstrack.workspace.entity.ProjectAccessMode;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Workspace-creation defaults (HD-130, S7 §10).
 *
 * <p><strong>Not profile-gated, and it must never become so.</strong> Access modes are a
 * product feature, not a plan feature (epic §13): the default below is identical in
 * {@code dc} and {@code cloud}. If a deployment ever wants new workspaces to start
 * restricted, it changes this value — never a second code path.
 *
 * <p><strong>The default lives in the placeholder, not here</strong> (security review,
 * round 2). {@code application.properties} wires
 * {@code app.workspace.default-project-access-mode=${DEFAULT_PROJECT_ACCESS_MODE:OPEN}} and
 * that is the <em>sole</em> source of the fallback, which is what makes the three documents
 * promising fail-fast true:
 * <ul>
 *   <li><strong>unset</strong> env var → the placeholder supplies {@code OPEN};</li>
 *   <li><strong>blank</strong> env var ({@code DEFAULT_PROJECT_ACCESS_MODE=}) → the
 *       placeholder resolves to {@code ""}, the enum converter yields {@code null}, and
 *       {@link NotNull} <strong>aborts startup</strong>;</li>
 *   <li><strong>unrecognised</strong> value → the converter fails and startup aborts.</li>
 * </ul>
 * It used to carry {@code @DefaultValue("OPEN")}, and a blank variable therefore bound
 * {@code null}, which the binder reads as <em>unbound</em> — so the annotation quietly
 * supplied {@code OPEN} for an operator who had said something and been misheard, silently
 * and in the permissive direction. The primitive-{@code int} safety net that caught this
 * class of typo twice on the numeric caps cannot apply to an enum, so the constraint has to
 * be written down. {@code @NotNull} <em>with</em> {@code @DefaultValue} would be dead code —
 * the default is applied before validation runs — so exactly one of the two may exist, and
 * this is the one that fails fast.
 *
 * @param defaultProjectAccessMode the {@link ProjectAccessMode} a <strong>newly created</strong>
 *        workspace starts in. {@code OPEN} through the placeholder, which is byte-identically
 *        the behaviour every workspace has today.
 *        <p><strong>It never changes an existing workspace.</strong> V14 set every workspace
 *        {@code OPEN} and no property may retroactively move one; the only way is
 *        {@code PATCH /api/workspaces/{ws}}. That is already written into V14's comment and
 *        must stay true.
 *        <p>Read by {@code WorkspaceService.create}, the one place a {@code Workspace} is
 *        constructed. <strong>Demo seeding goes through the same method</strong>, so a DC
 *        operator who sets {@code STRICT} gets a strict demo workspace — which is correct and
 *        must not be special-cased (epic §11.5: no permission bypass may be added for seeding).
 *        <p><strong>The constraint carries a message naming the environment variable</strong>
 *        (review round 3). Bean-validation's default renders as {@code rejected value [null]} on
 *        {@code defaultProjectAccessMode} — a <em>property</em> the operator never wrote, with
 *        no mention of {@code DEFAULT_PROJECT_ACCESS_MODE} and no hint that an empty line is the
 *        cause. The unrecognised-value path echoes what was typed; this one had nothing to echo,
 *        so the abort has to say what to do instead. An operator who uncomments the template
 *        line and clears it "to get the default back" is the exact reader.
 */
@Validated
@ConfigurationProperties(prefix = "app.workspace")
public record WorkspaceProperties(
        @NotNull(message = "must be OPEN or STRICT. If DEFAULT_PROJECT_ACCESS_MODE is set to an "
                           + "empty value, delete the line rather than blanking it: an unset "
                           + "variable falls back to OPEN, a blank one is refused")
        ProjectAccessMode defaultProjectAccessMode
) {
}
