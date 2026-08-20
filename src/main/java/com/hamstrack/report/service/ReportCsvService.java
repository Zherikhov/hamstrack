package com.hamstrack.report.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.project.exception.ProjectNotFoundException;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.report.csv.ReportCsv;
import com.hamstrack.report.csv.ReportCsvDocument;
import com.hamstrack.report.csv.ReportSubject;
import com.hamstrack.report.dto.CycleTimeQuery;
import com.hamstrack.report.dto.FlowQuery;
import com.hamstrack.report.dto.ReportMeasure;
import com.hamstrack.report.dto.ReportMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * Renders the six reports as CSV downloads (HD-141 R7, section 4.4).
 *
 * <p><strong>It computes nothing.</strong> Every method delegates to the service that owns the
 * report and then formats what came back, so an export can never drift from the endpoint it
 * exports: the window validation, the row cap, the percentile suppression, the sprint resolution
 * and — most importantly — the <strong>tenancy check</strong> are the report service's, unchanged
 * and unbypassed. An export that recomputed its own numbers would be a second implementation of
 * every rule in this package, and the first one to fall behind.
 *
 * <p><strong>Tenancy comes from the delegate, and the project header is scoped anyway.</strong>
 * The delegate calls {@code WorkspaceAccessService.resolveProject} first, so a missing workspace,
 * a missing project and a non-member are all 404 before anything is rendered. Only after that does
 * this class ask for the project's key and name — through
 * {@code findHeaderByIdAndWorkspaceId}, which is scoped by workspace rather than trusting the
 * ordering. One extra statement per download, on a throttled endpoint a person clicks.
 *
 * <p><strong>No endpoint here aggregates above the project</strong>, and the reason is stronger
 * than symmetry: section 1.4 refuses cross-team velocity on evidence, and the only thing enforcing
 * that refusal is the absence of an aggregate above the project. A workspace-level
 * {@code velocity.csv} unioning two projects would be exactly the artefact the refusal names, and
 * it would be invisible to the return-type half of {@code VelocityRefusalTest} because a CSV
 * handler returns a {@code String}. See {@code ReportCsvController}.
 */
@Service
@RequiredArgsConstructor
public class ReportCsvService {

    /** {@code DEMO-velocity-2026-08-20.csv} — see {@link #document}. */
    private static final DateTimeFormatter FILENAME_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    /**
     * Everything a filename may contain. Project keys are already {@code [A-Z0-9]+}, so this
     * strips nothing today; it is here because a filename crosses into a {@code Content-Disposition}
     * header, where a quote or a CR is a header-injection primitive rather than a cosmetic problem,
     * and because the key's own validation is one migration away from being looser than this.
     */
    private static final String FILENAME_UNSAFE = "[^A-Za-z0-9._-]";

    private final FlowReportService flowReportService;
    private final CycleTimeReportService cycleTimeReportService;
    private final AgingReportService agingReportService;
    private final SprintBurnupService sprintBurnupService;
    private final SprintReviewService sprintReviewService;
    private final VelocityService velocityService;
    private final ProjectRepository projectRepository;

    @Transactional(readOnly = true)
    public ReportCsvDocument flow(User actor, UUID workspaceId, UUID projectId, FlowQuery query) {
        var report = flowReportService.flow(actor, workspaceId, projectId, query);
        var subject = subject(workspaceId, projectId);
        return document(subject, "flow", report.meta(), ReportCsv.flow(subject, report));
    }

    @Transactional(readOnly = true)
    public ReportCsvDocument cycleTime(User actor, UUID workspaceId, UUID projectId,
                                       CycleTimeQuery query) {
        var report = cycleTimeReportService.cycleTime(actor, workspaceId, projectId, query);
        var subject = subject(workspaceId, projectId);
        return document(subject, "cycle-time", report.meta(), ReportCsv.cycleTime(subject, report));
    }

    @Transactional(readOnly = true)
    public ReportCsvDocument aging(User actor, UUID workspaceId, UUID projectId) {
        var report = agingReportService.aging(actor, workspaceId, projectId);
        var subject = subject(workspaceId, projectId);
        return document(subject, "aging", report.meta(), ReportCsv.aging(subject, report));
    }

    @Transactional(readOnly = true)
    public ReportCsvDocument sprintBurnup(User actor, UUID workspaceId, UUID projectId,
                                          UUID sprintId, ReportMeasure measure) {
        var report = sprintBurnupService.burnup(actor, workspaceId, projectId, sprintId, measure);
        var subject = subject(workspaceId, projectId);
        return document(subject, "sprint-burnup", report.meta(),
                ReportCsv.sprintBurnup(subject, report));
    }

    @Transactional(readOnly = true)
    public ReportCsvDocument sprintReview(User actor, UUID workspaceId, UUID projectId,
                                          UUID sprintId) {
        var report = sprintReviewService.review(actor, workspaceId, projectId, sprintId);
        var subject = subject(workspaceId, projectId);
        return document(subject, "sprint-review", report.meta(),
                ReportCsv.sprintReview(subject, report));
    }

    @Transactional(readOnly = true)
    public ReportCsvDocument velocity(User actor, UUID workspaceId, UUID projectId,
                                      Integer sprints, ReportMeasure measure) {
        var report = velocityService.velocity(actor, workspaceId, projectId, sprints, measure);
        var subject = subject(workspaceId, projectId);
        return document(subject, "velocity", report.meta(), ReportCsv.velocity(subject, report));
    }

    /**
     * The project's identity for the header and the filename.
     *
     * <p>Called <strong>after</strong> the delegate, never before: until the report service has
     * run, this caller has not been shown to be a member, and a lookup before that would be a
     * read of a resource whose visibility nobody has checked. The scoping on the query makes that
     * ordering belt-and-braces rather than the whole defence.
     */
    private ReportSubject subject(UUID workspaceId, UUID projectId) {
        return projectRepository.findHeaderByIdAndWorkspaceId(projectId, workspaceId)
                .map(header -> new ReportSubject(projectId, header.getKey(), header.getName()))
                .orElseThrow(ProjectNotFoundException::new);
    }

    /**
     * {@code DEMO-velocity-2026-08-20.csv}.
     *
     * <p>Dated from {@code meta.computedAt} rather than from a second {@code now()}, so the name
     * of the file and the {@code Computed at} line inside it can never disagree — a downloads
     * folder holding two exports of the same report is the normal case, and the date is how they
     * are told apart.
     */
    private static ReportCsvDocument document(ReportSubject subject, String report, ReportMeta meta,
                                              String body) {
        var name = subject.key().replaceAll(FILENAME_UNSAFE, "")
                   + "-" + report
                   + "-" + FILENAME_DATE.format(meta.computedAt().withOffsetSameInstant(ZoneOffset.UTC))
                   + ".csv";
        return new ReportCsvDocument(name, body);
    }
}
