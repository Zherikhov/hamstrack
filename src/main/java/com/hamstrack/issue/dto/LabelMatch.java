package com.hamstrack.issue.dto;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

/**
 * How the board/backlog {@code ?labelId=} filter combines the selected labels
 * (HD-30 §3.6): {@link #ANY} = OR (default, matches tag-filter intuition),
 * {@link #ALL} = AND.
 *
 * <p>A closed set, not a free string. The parameter used to be compared with
 * {@code "all".equalsIgnoreCase(raw)}, which <strong>failed open</strong>: any typo or
 * near-synonym ({@code "ALL "}, {@code "and"}, {@code "every"}) silently fell through
 * to the broader OR result set, so a caller asking to narrow got a wider list. Parsing
 * here turns an unknown value into a 400 instead.
 *
 * <p>Parsed rather than bound directly as a {@code @RequestParam} enum because
 * Spring's default String→enum conversion is case-<em>sensitive</em>
 * ({@code Enum.valueOf}) and the API contract (and the SPA) uses lowercase
 * {@code any}/{@code all}.
 */
public enum LabelMatch {
    ANY,
    ALL;

    /** Parse the wire value; {@code null}/blank → {@link #ANY}, unknown → 400. */
    public static LabelMatch parse(String raw) {
        if (raw == null || raw.isBlank()) return ANY;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "any" -> ANY;
            case "all" -> ALL;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "labelMatch must be 'any' or 'all'");
        };
    }
}
