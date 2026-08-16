package com.hamstrack.issue.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Release a version (HD-32, §6.3) — both fields optional, so an empty body
 * {@code {}} is a valid "just release it".
 *
 * @param releaseDate               overrides the stored plan date. When neither is
 *                                  given the server stamps <strong>today</strong>
 *                                  (server UTC date, §6.4)
 * @param moveUnresolvedToVersionId optionally re-point the FIX links of every issue
 *                                  in a non-DONE status to another
 *                                  <strong>unreleased, non-archived</strong> version
 *                                  of the same project (422 otherwise). Omit it and
 *                                  unresolved issues simply keep the released
 *                                  version — releasing with open work is allowed on
 *                                  purpose (§6.1: blocking it pushes teams to
 *                                  falsify the tracker)
 */
public record ReleaseVersionRequest(
        LocalDate releaseDate,
        UUID moveUnresolvedToVersionId
) {}
