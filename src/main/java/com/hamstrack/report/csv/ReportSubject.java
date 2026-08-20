package com.hamstrack.report.csv;

import java.util.UUID;

/**
 * The project a CSV export was computed for, as the comment header needs it (section 4.4:
 * "a comment header carrying project, window, measure, computedAt and basedOnIssues").
 *
 * <p>Carried separately from the report record because none of the six report responses names its
 * own project - the JSON caller already knows it from the URL, whereas a downloaded file has no
 * URL to be read alongside. A CSV that has been mailed on, renamed and opened three weeks later
 * has to say what it is about, or it is another screenshot.
 */
public record ReportSubject(UUID projectId, String key, String name) {
}
