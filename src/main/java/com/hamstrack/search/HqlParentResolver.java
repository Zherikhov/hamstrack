package com.hamstrack.search;

import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.search.parser.ast.Value;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves an ISSUE_REF ({@code parent}) operand — a quoted issue key like
 * {@code "DEMO-12"} — to its issue id, scoped to the actor's visible projects
 * (Advanced Search proposal §5, §6). Split out of {@link HqlValueResolver} because
 * it needs an issue lookup; the workspace + visible-project ids come from the
 * {@link ResolutionContext}, so a parent outside the tenant boundary is never
 * resolvable (a foreign/unknown key → 422, never an existence leak).
 */
@Component
@RequiredArgsConstructor
public class HqlParentResolver {

    private final IssueRepository issueRepository;

    public ResolvedValue.Ids resolve(FieldDescriptor field, Value value, ResolutionContext ctx) {
        if (!(value instanceof Value.StringLiteral s)) {
            throw new HqlSemanticException(
                    "Field 'parent' expects a quoted issue key like \"DEMO-12\"", field.name());
        }
        String key = s.value().trim();
        int dash = key.lastIndexOf('-');
        if (dash <= 0 || dash == key.length() - 1) {
            throw new HqlSemanticException(
                    "Invalid issue key '" + key + "'; expected PROJECT-NUMBER", field.name());
        }
        String projectKey = key.substring(0, dash);
        long number;
        try {
            number = Long.parseLong(key.substring(dash + 1));
        } catch (NumberFormatException e) {
            throw new HqlSemanticException(
                    "Invalid issue key '" + key + "'; expected PROJECT-NUMBER", field.name());
        }
        if (ctx.visibleProjectIds().isEmpty()) {
            throw new HqlSemanticException("No issue matching '" + key + "' in this workspace", field.name());
        }
        var id = issueRepository.findIdByWorkspaceAndKey(
                ctx.workspace().getId(), ctx.visibleProjectIds(), projectKey, number);
        return new ResolvedValue.Ids(List.of(id.orElseThrow(() -> new HqlSemanticException(
                "No issue matching '" + key + "' in this workspace", field.name()))));
    }
}
