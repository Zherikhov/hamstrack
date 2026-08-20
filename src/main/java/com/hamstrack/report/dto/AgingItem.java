package com.hamstrack.report.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One open issue in an aging-WIP column (reports-proposal §2.2) — <strong>the named rotting
 * item</strong>, which is the entire reason this epic refuses the cumulative flow diagram.
 * A CFD shades an area and asks the reader to infer a queue; this names DEMO-31, says it has
 * been open 19.4 days, and lets the p85 line drawn across the column say that is longer than
 * 85% of everything the team has ever finished.
 *
 * @param issueId   the issue, so the dot is clickable through to the issue detail.
 * @param key       the project-scoped key ("DEMO-31"), built from the resolved project's key.
 * @param title     the issue title.
 * @param ageDays   how long this issue has been in flight, in days to two decimals, measured
 *                  from {@link #startedAt} when it exists and from {@code created_at} when it
 *                  does not. Never null.
 *                  <p><strong>This report may fall back and the cycle-time one may not — the
 *                  asymmetry is deliberate.</strong> Cycle time is a defined measurement of
 *                  finished work: substituting filing time for start time there produces a
 *                  number that is simply wrong and says nothing about it. Age is a question
 *                  about a thing that has not happened yet, and "this was filed 40 days ago and
 *                  nobody has picked it up" is a true, useful and materially different fact
 *                  from "this has been in progress for 40 days". Both belong on the board; the
 *                  reader must be able to tell them apart, which is what {@link #startedAt} is
 *                  for.
 * @param assigneeId who holds it, or {@code null}. An id only — this is not a per-person
 *                  breakdown and must not become one (§4.2 records the trigger for revisiting:
 *                  the day a per-assignee metric is proposed, a read permission lands with it).
 *                  Aging WIP labels each item with its assignee so somebody can be asked about
 *                  it; it never aggregates by assignee, and no percentile here is per-person.
 * @param startedAt when work began, or {@code null} for an item never started — the provenance
 *                  of {@link #ageDays}. Returned precisely so the client can distinguish
 *                  "19 days in progress" from "19 days sitting in To Do" rather than being
 *                  handed a number with no account of where it came from.
 */
public record AgingItem(
        UUID issueId,
        String key,
        String title,
        double ageDays,
        UUID assigneeId,
        OffsetDateTime startedAt
) {}
