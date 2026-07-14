package com.hamstrack.issue.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.issue.dto.CreateStatusRequest;
import com.hamstrack.issue.dto.StatusResponse;
import com.hamstrack.issue.service.StatusService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Workspace-scoped status catalog (To Do / In Progress / Done seeded on
 * workspace creation). Each status carries a category (TO_DO / IN_PROGRESS /
 * DONE) that drives board grouping and backlog filtering. Listing is open to
 * all workspace members; mutations require workspace ADMIN. A status
 * referenced by existing issues cannot be deleted (409).
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/statuses")
@RequiredArgsConstructor
public class StatusController {

    private final StatusService statusService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StatusResponse create(@AuthenticationPrincipal User actor,
                                 @PathVariable UUID workspaceId,
                                 @Valid @RequestBody CreateStatusRequest req) {
        return statusService.create(actor, workspaceId, req);
    }

    @GetMapping
    public List<StatusResponse> list(@AuthenticationPrincipal User actor,
                                     @PathVariable UUID workspaceId) {
        return statusService.list(actor, workspaceId);
    }

    @PatchMapping("/{statusId}")
    public StatusResponse update(@AuthenticationPrincipal User actor,
                                 @PathVariable UUID workspaceId,
                                 @PathVariable UUID statusId,
                                 @Valid @RequestBody CreateStatusRequest req) {
        return statusService.update(actor, workspaceId, statusId, req);
    }

    @DeleteMapping("/{statusId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal User actor,
                       @PathVariable UUID workspaceId,
                       @PathVariable UUID statusId) {
        statusService.delete(actor, workspaceId, statusId);
    }
}
