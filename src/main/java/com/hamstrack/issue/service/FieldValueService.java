package com.hamstrack.issue.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.issue.entity.*;
import com.hamstrack.issue.repository.FieldSetItemRepository;
import com.hamstrack.issue.repository.FieldSetRepository;
import com.hamstrack.issue.repository.IssueFieldValueRepository;
import com.hamstrack.project.entity.Project;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Custom field values on issues: resolves the project's effective field set,
 * validates JSONB value shapes per {@link FieldType}, and upserts/clears the
 * rows. Every write path goes through {@link #applyValues}; nothing else may
 * touch {@code issue_field_values}.
 */
@Service
@RequiredArgsConstructor
public class FieldValueService {

    private final FieldSetRepository fieldSetRepository;
    private final FieldSetItemRepository fieldSetItemRepository;
    private final IssueFieldValueRepository valueRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public FieldSet effectiveFieldSet(Project project) {
        if (project.getFieldSet() != null) return project.getFieldSet();
        return fieldSetRepository.findBySystemDefaultTrue()
                .orElseThrow(() -> new IllegalStateException("System default field set is missing"));
    }

    /** The project's fields in display order. */
    @Transactional(readOnly = true)
    public List<FieldSetItem> fields(Project project) {
        return fieldSetItemRepository.findAllBySetOrderByPosition(effectiveFieldSet(project));
    }

    @Transactional(readOnly = true)
    public List<IssueFieldValue> values(Issue issue) {
        return valueRepository.findAllByIssue(issue);
    }

    @Transactional(readOnly = true)
    public Map<UUID, List<IssueFieldValue>> valuesByIssue(Collection<Issue> issues) {
        if (issues.isEmpty()) return Map.of();
        var byIssue = new HashMap<UUID, List<IssueFieldValue>>();
        for (var v : valueRepository.findAllByIssueIn(issues)) {
            byIssue.computeIfAbsent(v.getIssue().getId(), k -> new ArrayList<>()).add(v);
        }
        return byIssue;
    }

    /** Receives (fieldName, oldRendered, newRendered) for issue history. */
    @FunctionalInterface
    public interface FieldChangeListener {
        void changed(String fieldName, String oldValue, String newValue);
    }

    /**
     * Apply a partial map of field values (JSON null = clear). Unknown fields
     * (not in the project's set) and archived fields are rejected with 422;
     * required fields must be present on create (when shown on the create
     * form) and can never be cleared.
     */
    @Transactional
    public void applyValues(Issue issue, Map<UUID, JsonNode> requested, boolean isCreate,
                            FieldChangeListener onChange) {
        var items = fields(issue.getProject());
        var itemsByFieldId = new HashMap<UUID, FieldSetItem>();
        for (var item : items) itemsByFieldId.put(item.getField().getId(), item);

        if (requested != null) {
            for (var entry : requested.entrySet()) {
                var item = itemsByFieldId.get(entry.getKey());
                if (item == null) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Field is not part of this project's field set");
                }
                var field = item.getField();
                if (field.getArchivedAt() != null) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Field '" + field.getName() + "' is archived");
                }
                var value = entry.getValue();
                boolean clearing = value == null || value.isNull();
                if (clearing) {
                    if (item.isRequired()) {
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "Field '" + field.getName() + "' is required and cannot be cleared");
                    }
                    valueRepository.findByIssueAndField(issue, field).ifPresent(existing -> {
                        onChange.changed(field.getName(), render(field, existing.getValue()), null);
                        valueRepository.delete(existing);
                    });
                    continue;
                }
                validate(issue, field, value);
                var existing = valueRepository.findByIssueAndField(issue, field).orElse(null);
                if (existing == null) {
                    var row = new IssueFieldValue();
                    row.setIssue(issue);
                    row.setField(field);
                    row.setValue(value);
                    valueRepository.save(row);
                    onChange.changed(field.getName(), null, render(field, value));
                } else if (!value.equals(existing.getValue())) {
                    onChange.changed(field.getName(), render(field, existing.getValue()), render(field, value));
                    existing.setValue(value);
                    valueRepository.save(existing);
                }
            }
        }

        if (isCreate) {
            for (var item : items) {
                boolean provided = requested != null
                        && requested.get(item.getField().getId()) != null
                        && !requested.get(item.getField().getId()).isNull();
                if (item.isRequired() && item.isShowOnCreate() && !provided) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Field '" + item.getField().getName() + "' is required");
                }
            }
        }
    }

    // ---- type validation (value shapes documented on FieldType) ----

    private void validate(Issue issue, FieldDef field, JsonNode value) {
        switch (field.getType()) {
            case TEXT, TEXTAREA -> requireText(field, value, field.getType() == FieldType.TEXT ? 500 : 10_000);
            case URL -> {
                requireText(field, value, 2000);
                var s = value.asText();
                if (!s.startsWith("http://") && !s.startsWith("https://")) {
                    reject(field, "must be an http(s) URL");
                }
            }
            case NUMBER -> {
                if (!value.isNumber()) reject(field, "must be a number");
                var cfg = field.getConfig();
                if (cfg != null) {
                    double d = value.asDouble();
                    if (cfg.hasNonNull("min") && d < cfg.get("min").asDouble()) reject(field, "is below the minimum");
                    if (cfg.hasNonNull("max") && d > cfg.get("max").asDouble()) reject(field, "is above the maximum");
                }
            }
            case DATE -> {
                if (!value.isTextual()) reject(field, "must be a YYYY-MM-DD string");
                try {
                    LocalDate.parse(value.asText());
                } catch (DateTimeParseException e) {
                    reject(field, "must be a valid YYYY-MM-DD date");
                }
            }
            case CHECKBOX -> {
                if (!value.isBoolean()) reject(field, "must be true or false");
            }
            case SELECT -> {
                if (!value.isTextual() || !optionIds(field).contains(value.asText())) {
                    reject(field, "must be one of the configured options");
                }
            }
            case MULTI_SELECT -> {
                if (!value.isArray() || value.isEmpty()) reject(field, "must be a non-empty array of options");
                var ids = optionIds(field);
                for (var el : value) {
                    if (!el.isTextual() || !ids.contains(el.asText())) {
                        reject(field, "contains an unknown option");
                    }
                }
            }
            case USER -> {
                if (!value.isTextual()) reject(field, "must be a user id");
                UUID userId;
                try {
                    userId = UUID.fromString(value.asText());
                } catch (IllegalArgumentException e) {
                    reject(field, "must be a user id");
                    return;
                }
                // Same tenancy rule as assignee: only workspace members are referencable
                var ok = userRepository.findById(userId)
                        .filter(u -> workspaceMemberRepository.existsByWorkspaceAndUser(issue.getWorkspace(), u))
                        .isPresent();
                if (!ok) reject(field, "must be a member of the workspace");
            }
        }
    }

    private void requireText(FieldDef field, JsonNode value, int maxLen) {
        if (!value.isTextual() || value.asText().isBlank()) reject(field, "must be a non-empty string");
        if (value.asText().length() > maxLen) reject(field, "is too long");
    }

    private Set<String> optionIds(FieldDef field) {
        var ids = new HashSet<String>();
        var cfg = field.getConfig();
        if (cfg != null && cfg.has("options")) {
            for (var opt : cfg.get("options")) {
                if (opt.hasNonNull("id")) ids.add(opt.get("id").asText());
            }
        }
        return ids;
    }

    /** Human-readable value for issue history (option labels, not ids). */
    private String render(FieldDef field, JsonNode value) {
        return switch (field.getType()) {
            case SELECT -> optionLabel(field, value.asText());
            case MULTI_SELECT -> {
                var labels = new ArrayList<String>();
                value.forEach(el -> labels.add(optionLabel(field, el.asText())));
                yield String.join(", ", labels);
            }
            case USER -> userRepository.findById(UUID.fromString(value.asText()))
                    .map(u -> u.getDisplayName()).orElse(value.asText());
            case CHECKBOX -> value.asBoolean() ? "yes" : "no";
            default -> value.asText();
        };
    }

    private String optionLabel(FieldDef field, String id) {
        var cfg = field.getConfig();
        if (cfg != null && cfg.has("options")) {
            for (var opt : cfg.get("options")) {
                if (id.equals(opt.path("id").asText())) return opt.path("label").asText(id);
            }
        }
        return id;
    }

    private void reject(FieldDef field, String why) {
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Field '" + field.getName() + "' " + why);
    }
}
