package com.hamstrack.common.event;

/**
 * One field-level change carried on {@link IssueUpdated#changeSet()} — the same
 * (field, old, new) triples {@code IssueService.update} already builds for issue
 * history. Populated for downstream consumers (Phase-5 triggers, webhooks); the
 * SSE listener ignores it and emits the unchanged {@code {projectId, issueNumber}}
 * payload, so the wire contract is untouched.
 */
public record FieldChange(String field, String oldValue, String newValue) {
}
