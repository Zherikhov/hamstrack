package com.hamstrack.report.dto;

import com.hamstrack.issue.entity.StatusCategory;

import java.util.List;
import java.util.UUID;

/**
 * One column of the aging-WIP half (reports-proposal §2.2): a non-DONE status of the project's
 * <em>effective</em> workflow, holding its open issues oldest-first.
 *
 * <h2>Columns come from the workflow, items come from the issues — and they can disagree</h2>
 * The column list is the project's effective workflow, read through {@code ProjectConfigService}
 * (already cached), which is what makes an <strong>empty</strong> column render: a status nobody
 * is currently in is a fact about the board worth seeing, and a report that only draws columns
 * it found rows for silently redraws its own axis every day.
 *
 * <p>The reverse disagreement is the interesting one. An issue can sit in a status that is
 * <em>not</em> in the project's current workflow — the workflow was swapped, or the status was
 * removed from it — and the codebase deliberately permits that ({@code requireStatusInWorkflow}
 * gates <em>transitions</em>, not existing rows). Such issues are gathered into a single trailing
 * column with {@link #statusId} and {@link #category} {@code null} and the name
 * {@code "Not on this board"}. They are emphatically not dropped: an aging report exists to name
 * the item nobody is looking at, and an issue stranded outside the workflow is the single most
 * likely such item in any real project. A report that silently drops rows is worse than one with
 * an awkward column.
 *
 * @param statusId the status, or {@code null} for the trailing "Not on this board" column. The
 *                 null is the client's signal that this column is not a board column and has no
 *                 workflow position — it is the one column that cannot be dragged to.
 * @param name     the status name, or {@code "Not on this board"}. Deliberately one column
 *                 rather than one per off-workflow status: the reader's question is "what is
 *                 stranded", not "which retired status is it stranded in", and per-status
 *                 columns would let a workflow swap add a dozen columns to a chart.
 * @param category the status category, or {@code null} for the trailing column (whose members
 *                 may come from several statuses of different categories, so there is no single
 *                 honest answer). DONE is never a column here — this half is about unfinished
 *                 work.
 * @param items    the column's open issues, <strong>oldest first</strong>, so the item most in
 *                 need of attention is the first one a reader's eye lands on and the first one
 *                 to survive the row cap.
 */
public record AgingColumn(
        UUID statusId,
        String name,
        StatusCategory category,
        List<AgingItem> items
) {

    /** The trailing column's name — see the class javadoc for why there is exactly one. */
    public static final String OFF_WORKFLOW_NAME = "Not on this board";
}
