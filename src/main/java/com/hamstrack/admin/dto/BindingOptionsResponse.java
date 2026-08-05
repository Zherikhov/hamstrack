package com.hamstrack.admin.dto;

import com.hamstrack.issue.entity.Scoped;

import java.util.List;
import java.util.UUID;

/**
 * The sets a delegated admin may bind to a project, per dimension. Each option
 * carries its {@code scope} ("GLOBAL" | "WORKSPACE" | "PROJECT") so the UI can
 * mark inherited (read-only) sets distinctly from ones the admin owns.
 */
public record BindingOptionsResponse(
        List<SetOption> workflows,
        List<SetOption> prioritySets,
        List<SetOption> fieldSets,
        List<SetOption> issueTypeSets
) {
    public record SetOption(UUID id, String name, String scope) {
        public static SetOption of(UUID id, String name, Scoped s) {
            String scope = s.getScopeProjectId() != null ? "PROJECT"
                    : s.getScopeWorkspaceId() != null ? "WORKSPACE"
                    : "GLOBAL";
            return new SetOption(id, name, scope);
        }
    }
}
