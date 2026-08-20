package com.hamstrack.report.csv;

/**
 * A rendered report export: the download filename and the file itself.
 *
 * <p>Two fields rather than a {@code ResponseEntity} so the rendering layer stays free of HTTP -
 * the controller owns the content type, the disposition and the cache headers, and this record is
 * what a test can assert on without a servlet.
 */
public record ReportCsvDocument(String filename, String body) {
}
