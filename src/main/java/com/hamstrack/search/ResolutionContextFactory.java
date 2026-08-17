package com.hamstrack.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.auth.entity.User;
import com.hamstrack.issue.entity.FieldDef;
import com.hamstrack.issue.entity.Priority;
import com.hamstrack.issue.entity.PrioritySetItem;
import com.hamstrack.issue.entity.Status;
import com.hamstrack.issue.entity.IssueType;
import com.hamstrack.issue.repository.ComponentRepository;
import com.hamstrack.issue.repository.LabelRepository;
import com.hamstrack.issue.repository.SprintRepository;
import com.hamstrack.issue.repository.VersionRepository;
import com.hamstrack.issue.service.FieldValueService;
import com.hamstrack.issue.service.ProjectConfigService;
import com.hamstrack.project.entity.BoardMode;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 *
 * <p>It also records which delivery capabilities the visible projects declare
 * (HD-107 §9.1). That is <strong>suggestion-only</strong> state: it narrows the
 * {@code /schema} field list and the SPRINT/VERSION picklists, and it never touches a
 * name→id resolution map. Search stays capability-blind where it matters — a saved
 * filter must keep running after somebody turns a project's sprints off.
 */
@Component
@RequiredArgsConstructor
public class ResolutionContextFactory {

    private final ProjectRepository projectRepository;
    private final LabelRepository labelRepository;
    private final ComponentRepository componentRepository;
    private final VersionRepository versionRepository;
    private final SprintRepository sprintRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ProjectConfigService projectConfigService;
    private final FieldValueService fieldValueService;
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

        // Delivery capabilities (HD-107 §9.1) — the SUGGESTION-only half of this slice.
        // Each list is a subset of the visible projects, so it can only ever narrow.
        // Nothing below narrows a name→id RESOLUTION map with these: `sprint`,
        // `fixVersion`, `affectsVersion` and `storyPoints` must keep resolving on every
        // project, or a saved filter would break the moment a curator flipped a toggle.
        // LinkedHashSets: membership is checked once per version/sprint row below, and a
        // workspace can hold many projects — a list scan would be O(projects × rows).
        var iterationProjectIds = new LinkedHashSet<UUID>();
        var releaseProjectIds = new LinkedHashSet<UUID>();
        var estimationProjectIds = new LinkedHashSet<UUID>();
        for (var project : visibleProjects) {
            if (project.getBoardMode() == BoardMode.SCRUM) iterationProjectIds.add(project.getId());
            if (project.isReleasesEnabled()) releaseProjectIds.add(project.getId());
            if (project.isEstimationEnabled()) estimationProjectIds.add(project.getId());
        }
        var capabilities = new ResolutionContext.Capabilities(
                List.copyOf(iterationProjectIds), List.copyOf(releaseProjectIds),
                List.copyOf(estimationProjectIds));

        Map<String, List<UUID>> statusIds = new LinkedHashMap<>();
        Map<String, List<UUID>> typeIds = new LinkedHashMap<>();
        Map<String, List<UUID>> priorityIds = new LinkedHashMap<>();
        Map<String, List<Priority>> prioritiesByName = new LinkedHashMap<>();
        // Original-cased display names, deduped case-insensitively (for /schema).
        Map<String, String> statusNames = new LinkedHashMap<>();
        Map<String, String> typeNames = new LinkedHashMap<>();
        Map<String, String> priorityNames = new LinkedHashMap<>();
        // Labels (HD-30) are WORKSPACE-scoped — no per-project narrowing, the workspace
        // is the tenant boundary. Archived labels are excluded from name resolution
        // (§6.1); issues carrying one still match by id.
        //
        // Deliberately an (id, name) PROJECTION, not findAllByWorkspace: resolution
        // must stay unbounded (a name typed in HQL has to resolve, so there is no
        // limit to apply), but this runs on every /search, /schema and /suggest — no
        // reason to hydrate whole Label entities and LEFT JOIN FETCH their creators.
        Map<String, List<UUID>> labelIds = new LinkedHashMap<>();
        Map<String, String> labelNames = new LinkedHashMap<>();
        for (var row : labelRepository.findIdAndNameByWorkspace(ws)) {
            UUID labelId = (UUID) row[0];
            String labelName = (String) row[1];
            labelNames.putIfAbsent(addId(labelIds, labelName, labelId), labelName);
        }
        // Components (HD-31) are PROJECT-scoped, so they are built from the VISIBLE
        // PROJECT set — never "all components of the workspace": a name must never
        // resolve through a project the search scope would hide. A name maps to a LIST
        // of ids on purpose (two visible projects may each own a "Billing"), exactly
        // like statuses. Archived components are excluded from name resolution; issues
        // carrying one still match by id (§6.1). Same (id, name) projection rationale
        // as labels above.
        Map<String, List<UUID>> componentIds = new LinkedHashMap<>();
        Map<String, String> componentNames = new LinkedHashMap<>();
        // Versions (HD-32) are PROJECT-scoped like components, so the same rules apply:
        // built from the VISIBLE PROJECT set only, a name maps to a LIST of ids (two
        // visible projects may each ship a "2.4.0"), archived versions are excluded from
        // name resolution but issues linked to one still match by id. ONE map serves
        // both fixVersion and affectsVersion — the role is applied by the compiler's
        // link_type filter, not by name resolution.
        Map<String, List<UUID>> versionIds = new LinkedHashMap<>();
        Map<String, String> versionNames = new LinkedHashMap<>();
        // Sprints (HD-22) follow components/versions exactly: PROJECT-scoped, so built
        // from the VISIBLE PROJECT set only, and a name maps to a LIST of ids (two
        // visible projects may each run a "Sprint 7"). COMPLETED sprints are excluded
        // from name resolution — years of history would flood the namespace — but issues
        // still carrying one match by id.
        Map<String, List<UUID>> sprintIds = new LinkedHashMap<>();
        Map<String, String> sprintNames = new LinkedHashMap<>();
        if (!visibleIds.isEmpty()) {   // an empty IN list is invalid in JPQL
            for (var row : componentRepository.findIdAndNameByProjectIds(visibleIds)) {
                UUID componentId = (UUID) row[0];
                String componentName = (String) row[1];
                componentNames.putIfAbsent(
                        addId(componentIds, componentName, componentId), componentName);
            }
            // Versions: RESOLUTION spans every visible project (unchanged — §9.1 is
            // absolute), but the /schema VERSION picklist only offers names from
            // projects with `releases` on. The two audiences are fed from ONE query;
            // row[0] is the owning project id.
            for (var row : versionRepository.findIdAndNameByProjectIds(visibleIds)) {
                UUID ownerProjectId = (UUID) row[0];
                UUID versionId = (UUID) row[1];
                String versionName = (String) row[2];
                String versionKey = addId(versionIds, versionName, versionId);
                if (releaseProjectIds.contains(ownerProjectId)) {
                    versionNames.putIfAbsent(versionKey, versionName);
                }
            }
            // Sprints: same split — every visible project's open sprints resolve by
            // name, only the SCRUM ones are SUGGESTED in the /schema SPRINT picklist.
            for (var row : sprintRepository.findIdAndNameByProjectIds(visibleIds)) {
                UUID ownerProjectId = (UUID) row[0];
                UUID sprintId = (UUID) row[1];
                String sprintName = (String) row[2];
                String sprintKey = addId(sprintIds, sprintName, sprintId);
                if (iterationProjectIds.contains(ownerProjectId)) {
                    sprintNames.putIfAbsent(sprintKey, sprintName);
                }
            }
        }
        // Custom fields (HD-52): union of non-archived field_defs reachable by any
        // visible project via its effective field set — the same source /schema uses.
        Map<String, CustomFieldMeta> customFields = new LinkedHashMap<>();

        for (var project : visibleProjects) {
            for (Status s : projectConfigService.statuses(project)) {
                if (s.getArchivedAt() != null) continue;
                statusNames.putIfAbsent(addId(statusIds, s.getName(), s.getId()), s.getName());
            }
            for (IssueType t : projectConfigService.types(project)) {
                if (t.getArchivedAt() != null) continue;
                typeNames.putIfAbsent(addId(typeIds, t.getName(), t.getId()), t.getName());
            }
            for (PrioritySetItem item : projectConfigService.priorityItems(project)) {
                Priority p = item.getPriority();
                if (p.getArchivedAt() != null) continue;
                String priorityKey = addId(priorityIds, p.getName(), p.getId());
                addPriority(prioritiesByName, priorityKey, p);
                priorityNames.putIfAbsent(priorityKey, p.getName());
            }
            for (var setItem : fieldValueService.fields(project)) {
                FieldDef field = setItem.getField();
                if (field.getArchivedAt() != null) continue;
                addCustomField(customFields, field);
            }
        }

        var members = new ArrayList<ResolutionContext.Member>();
        for (var m : workspaceMemberRepository.findAllByWorkspaceWithUser(ws)) {
            var u = m.getUser();
            members.add(new ResolutionContext.Member(u.getId(), u.getEmail(), u.getDisplayName()));
        }

        return new ResolutionContext(actor, ws, visibleIds,
                statusIds, typeIds, priorityIds, prioritiesByName, labelIds, componentIds,
                versionIds, sprintIds, members,
                List.copyOf(statusNames.values()),
                List.copyOf(typeNames.values()),
                List.copyOf(priorityNames.values()),
                List.copyOf(labelNames.values()),
                List.copyOf(componentNames.values()),
                List.copyOf(versionNames.values()),
                List.copyOf(sprintNames.values()),
                customFields, capabilities);
    }

    /**
     * Key every name→id map with {@link SearchNames#key(String)} — the SAME function
     * {@code HqlValueResolver} applies to the operand (HD-90). Labels/components/
     * versions/sprints are already normalized on write; statuses/types/priorities come
     * from the admin catalog and are NOT, so a stored {@code "In  Progress"} lands under
     * {@code "in progress"} here and stays reachable by both spellings.
     *
     * <p><strong>Returns the key it used</strong> so the caller can reuse it for the
     * sibling display-name map instead of folding the same string twice. {@code key()}
     * is NFC + two regex passes and this runs once per catalog row of the whole
     * workspace on every {@code /search}, {@code /schema} and {@code /suggest} — the
     * second call was pure duplicated CPU (see {@code ClassificationNames}).
     */
    private String addId(Map<String, List<UUID>> map, String name, UUID id) {
        String key = SearchNames.key(name);
        var list = map.computeIfAbsent(key, k -> new ArrayList<>());
        if (!list.contains(id)) list.add(id);
        return key;
    }

    /** Takes the key already computed by {@link #addId} — see the note there. */
    private void addPriority(Map<String, List<Priority>> map, String key, Priority p) {
        var list = map.computeIfAbsent(key, k -> new ArrayList<>());
        if (list.stream().noneMatch(e -> e.getId().equals(p.getId()))) list.add(p);
    }

    /**
     * Register a custom field by its {@code key}. The same field can be reached by
     * several projects — first-wins is fine (a global {@code field_def} id is the
     * same everywhere). SELECT/MULTI_SELECT option maps are read from {@code config}.
     *
     * <p>The field {@code key} deliberately keeps a PLAIN lower-case key (HD-90), unlike
     * every display name above: it is a machine slug, and its lookup operand is a lexer
     * {@code IDENT} token, which cannot contain whitespace or format characters — so
     * canonical folding could never change the outcome. It stays paired with
     * {@code ResolutionContext.customField}, which lower-cases the same way. Option
     * LABELS are the opposite case — user-authored display text quoted in HQL — so they
     * are keyed with {@link SearchNames}.
     */
    private void addCustomField(Map<String, CustomFieldMeta> map, FieldDef field) {
        String key = field.getKey().toLowerCase(Locale.ROOT);
        if (map.containsKey(key)) return;

        Map<String, String> optionsById = new LinkedHashMap<>();   // id → label
        Map<String, String> optionIdByLabel = new LinkedHashMap<>(); // lower(label|id) → id
        switch (field.getType()) {
            case SELECT, MULTI_SELECT -> {
                JsonNode cfg = field.getConfig();
                if (cfg != null && cfg.has("options")) {
                    for (JsonNode opt : cfg.get("options")) {
                        if (!opt.hasNonNull("id")) continue;
                        String id = opt.get("id").asText();
                        String label = opt.path("label").asText(id);
                        optionsById.putIfAbsent(id, label);
                        // Accept both the human label and the raw id when writing a value.
                        // ONE map serves both, so both entries must use the SAME key
                        // function as CustomFieldMeta.resolveOption (HD-90) — canonical
                        // for the label (user-authored text, same bug class as a version
                        // name), and a harmless no-op for the slug-shaped id.
                        optionIdByLabel.putIfAbsent(SearchNames.key(label), id);
                        optionIdByLabel.putIfAbsent(SearchNames.key(id), id);
                    }
                }
            }
            default -> { /* no options */ }
        }
        map.put(key, new CustomFieldMeta(field.getId(), field.getKey(), field.getName(),
                field.getType(), optionsById, optionIdByLabel));
    }
}
