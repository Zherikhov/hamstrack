package com.hamstrack.issue.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.issue.dto.BacklogViewResponse;
import com.hamstrack.issue.dto.LabelMatch;
import com.hamstrack.issue.service.BacklogService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The planning view of one project (HD-22) — {@code GET
 * …/projects/{projectId}/backlog}. One request returns the project's OPEN sprint
 * sections (ACTIVE first, then FUTURE by sequence) above the rank-ordered backlog, so
 * the BacklogPage renders in a single round trip instead of one fetch per section.
 *
 * <p>Any project member may read it; a missing workspace, a missing project or a
 * non-member all yield <strong>404</strong>, never 403.
 *
 * <p>The filter parameters are exactly the ones {@code GET …/issues} already accepts
 * and are all server-side, so filtering searches the WHOLE project rather than the
 * capped page already on screen. Each section is independently capped at
 * {@code app.agile.section-max-issues} and reports {@code truncated} /
 * {@code totalAvailable} (the HD-79 pattern) — while its stats are computed over the
 * whole section, so a truncated section still shows honest totals.
 *
 * <p>The response also carries {@code bulkMoveCap}
 * ({@code app.agile.max-issues-per-bulk-move}) beside {@code sectionCap}: the two are
 * independent operator knobs with deliberately different defaults, so a client that
 * derived one from the other would 400 on every "move all to…" of a section larger than
 * the bulk cap. With both on the wire the SPA chunks at what the install actually allows.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/backlog")
@RequiredArgsConstructor
public class BacklogController {

    private final BacklogService backlogService;

    /**
     * @param includeDone default <strong>false</strong>: a done, unranked issue is
     *                    planning noise. It affects the BACKLOG section only — sprint
     *                    sections always include their DONE issues, because that is the
     *                    sprint's record of what it delivered.
     */
    @GetMapping
    public BacklogViewResponse view(@AuthenticationPrincipal User actor,
                                    @PathVariable UUID workspaceId,
                                    @PathVariable UUID projectId,
                                    @RequestParam(required = false) UUID statusId,
                                    @RequestParam(required = false) UUID assigneeId,
                                    @RequestParam(required = false) UUID priorityId,
                                    @RequestParam(required = false) UUID componentId,
                                    @RequestParam(required = false) List<UUID> labelId,
                                    @RequestParam(defaultValue = "any") String labelMatch,
                                    @RequestParam(required = false) UUID fixVersionId,
                                    @RequestParam(defaultValue = "false") boolean includeDone) {
        // Parsed here (not bound as an enum @RequestParam): Spring's String→enum
        // conversion is case-sensitive, and the wire contract is lowercase any/all.
        var match = LabelMatch.parse(labelMatch);
        return backlogService.view(actor, workspaceId, projectId, statusId, assigneeId,
                priorityId, componentId, labelId, match, fixVersionId, includeDone);
    }
}
