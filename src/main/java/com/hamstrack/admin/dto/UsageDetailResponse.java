package com.hamstrack.admin.dto;

import com.hamstrack.project.entity.Project;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * "Where exactly is this used?" — the expansion behind a usage chip. Lists
 * names instead of counts: containing workflows/sets and the projects that
 * effectively use the entry through them (deduplicated).
 */
public record UsageDetailResponse(
        List<String> workflows,
        List<String> sets,
        List<ProjectRef> projects,
        long issues
) {
    public record ProjectRef(UUID id, String key, String name) {
        public static ProjectRef of(Project p) {
            return new ProjectRef(p.getId(), p.getKey(), p.getName());
        }
    }

    public static List<ProjectRef> dedupe(Collection<Project> projects) {
        var byId = new LinkedHashMap<UUID, ProjectRef>();
        for (var p : projects) byId.putIfAbsent(p.getId(), ProjectRef.of(p));
        return List.copyOf(byId.values());
    }
}
