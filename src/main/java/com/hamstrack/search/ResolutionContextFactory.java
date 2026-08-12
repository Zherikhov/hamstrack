package com.hamstrack.search;

import com.hamstrack.auth.entity.User;
import com.hamstrack.issue.entity.Priority;
import com.hamstrack.issue.entity.PrioritySetItem;
import com.hamstrack.issue.entity.Status;
import com.hamstrack.issue.entity.IssueType;
import com.hamstrack.issue.service.ProjectConfigService;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Builds a {@link ResolutionContext} for one search request: the actor's visible
 * projects, the name→id catalog maps reachable across those projects (via
 * {@link ProjectConfigService} — the single arbiter of effective config), and the
 * workspace member roster. Assembled inside the request transaction, scoped to the
 * workspace (Advanced Search proposal §6.1). Archived catalog rows are excluded
 * from name resolution (§6.1); issues already carrying them still match by id.
 */
@Component
@RequiredArgsConstructor
public class ResolutionContextFactory {

    private final ProjectRepository projectRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ProjectConfigService projectConfigService;
    private final SearchScope searchScope;

    @Transactional(readOnly = true)
    public ResolutionContext build(User actor, Workspace ws) {
        // NOTE (§3.1.2): derive the project set from SearchScope.visibleProjectIds — the
        // single source of truth for what the actor can see. Fetch the entities by that
        // exact id set (never a second "all non-archived" query) so name/member resolution
        // can never resolve through a project the scope would hide once public/private
        // projects land.
        var visibleIds = searchScope.visibleProjectIds(actor, ws);
        var visibleProjects = visibleIds.isEmpty() ? List.<Project>of()
                : projectRepository.findAllById(visibleIds);

        Map<String, List<UUID>> statusIds = new LinkedHashMap<>();
        Map<String, List<UUID>> typeIds = new LinkedHashMap<>();
        Map<String, List<UUID>> priorityIds = new LinkedHashMap<>();
        Map<String, List<Priority>> prioritiesByName = new LinkedHashMap<>();
        // Original-cased display names, deduped case-insensitively (for /schema).
        Map<String, String> statusNames = new LinkedHashMap<>();
        Map<String, String> typeNames = new LinkedHashMap<>();
        Map<String, String> priorityNames = new LinkedHashMap<>();

        for (var project : visibleProjects) {
            for (Status s : projectConfigService.statuses(project)) {
                if (s.getArchivedAt() != null) continue;
                addId(statusIds, s.getName(), s.getId());
                statusNames.putIfAbsent(s.getName().toLowerCase(Locale.ROOT), s.getName());
            }
            for (IssueType t : projectConfigService.types(project)) {
                if (t.getArchivedAt() != null) continue;
                addId(typeIds, t.getName(), t.getId());
                typeNames.putIfAbsent(t.getName().toLowerCase(Locale.ROOT), t.getName());
            }
            for (PrioritySetItem item : projectConfigService.priorityItems(project)) {
                Priority p = item.getPriority();
                if (p.getArchivedAt() != null) continue;
                addId(priorityIds, p.getName(), p.getId());
                addPriority(prioritiesByName, p);
                priorityNames.putIfAbsent(p.getName().toLowerCase(Locale.ROOT), p.getName());
            }
        }

        var members = new ArrayList<ResolutionContext.Member>();
        for (var m : workspaceMemberRepository.findAllByWorkspaceWithUser(ws)) {
            var u = m.getUser();
            members.add(new ResolutionContext.Member(u.getId(), u.getEmail(), u.getDisplayName()));
        }

        return new ResolutionContext(actor, ws, visibleIds,
                statusIds, typeIds, priorityIds, prioritiesByName, members,
                List.copyOf(statusNames.values()),
                List.copyOf(typeNames.values()),
                List.copyOf(priorityNames.values()));
    }

    private void addId(Map<String, List<UUID>> map, String name, UUID id) {
        var list = map.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>());
        if (!list.contains(id)) list.add(id);
    }

    private void addPriority(Map<String, List<Priority>> map, Priority p) {
        var list = map.computeIfAbsent(p.getName().toLowerCase(Locale.ROOT), k -> new ArrayList<>());
        if (list.stream().noneMatch(e -> e.getId().equals(p.getId()))) list.add(p);
    }
}
