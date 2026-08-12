package com.hamstrack.search.filter.dto;

import java.util.List;
import java.util.UUID;

/**
 * The delete-with-usage warning contract (Advanced Search proposal §10.4, HD-26
 * acceptance). A saved filter is designed to become a reusable board/report data
 * source; before deleting one that other things depend on, the client shows a
 * "Used by N places — delete anyway?" confirmation and re-issues the delete.
 *
 * <p><strong>MVP hook — always empty.</strong> No board/report consumes a filter yet,
 * so {@link #usages()} is always {@code []} and {@link #inUse()} is {@code false};
 * the endpoint and the client confirm-dialog are built now so the future board/report
 * wiring (a follow-up epic) is drop-in — it will populate {@code usages} from the new
 * consumer tables with no contract change.
 *
 * @param usages the places referencing this filter (empty in MVP).
 */
public record SavedFilterUsageResponse(List<Usage> usages) {

    public static SavedFilterUsageResponse empty() {
        return new SavedFilterUsageResponse(List.of());
    }

    public boolean inUse() {
        return usages != null && !usages.isEmpty();
    }

    /**
     * A single consumer of a saved filter (a board or report, when those land).
     *
     * @param type "BOARD" | "REPORT" (open string — new consumer kinds need no
     *             contract change)
     * @param id   the consumer's id
     * @param name the consumer's display name for the confirm dialog
     */
    public record Usage(String type, UUID id, String name) {}
}
