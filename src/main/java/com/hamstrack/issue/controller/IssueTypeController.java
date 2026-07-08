package com.hamstrack.issue.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.issue.dto.CreateIssueTypeRequest;
import com.hamstrack.issue.dto.IssueTypeResponse;
import com.hamstrack.issue.service.IssueTypeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/issue-types")
@RequiredArgsConstructor
public class IssueTypeController {

    private final IssueTypeService issueTypeService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IssueTypeResponse create(@AuthenticationPrincipal User actor,
                                    @PathVariable UUID workspaceId,
                                    @Valid @RequestBody CreateIssueTypeRequest req) {
        return issueTypeService.create(actor, workspaceId, req);
    }

    @GetMapping
    public List<IssueTypeResponse> list(@AuthenticationPrincipal User actor,
                                        @PathVariable UUID workspaceId) {
        return issueTypeService.list(actor, workspaceId);
    }

    @PatchMapping("/{typeId}")
    public IssueTypeResponse update(@AuthenticationPrincipal User actor,
                                    @PathVariable UUID workspaceId,
                                    @PathVariable UUID typeId,
                                    @Valid @RequestBody CreateIssueTypeRequest req) {
        return issueTypeService.update(actor, workspaceId, typeId, req);
    }

    @DeleteMapping("/{typeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal User actor,
                       @PathVariable UUID workspaceId,
                       @PathVariable UUID typeId) {
        issueTypeService.delete(actor, workspaceId, typeId);
    }
}
