package com.hamstrack.admin.controller;

import com.hamstrack.admin.dto.*;
import com.hamstrack.admin.service.AdminUserService;
import com.hamstrack.auth.entity.User;
import com.hamstrack.common.dto.PageResponse;
import com.hamstrack.common.dto.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * User directory for the system administrator. The whole /api/admin/** area is
 * guarded by {@code hasRole("ADMIN")} in SecurityConfig. Accounts are created
 * without a password or email — the response carries a one-time setup link the
 * admin hands over. On DC this is the primary way to onboard users (public
 * self-registration is closed there).
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService userService;

    @GetMapping
    public PageResponse<AdminUserResponse> list(@RequestParam(required = false) @Max(Paging.MAX_PAGE) Integer page,
                                                @RequestParam(required = false) Integer size) {
        return userService.list(Paging.of(page, size, Sort.by("createdAt").ascending()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedUserResponse create(@Valid @RequestBody CreateUserRequest req) {
        return userService.create(req);
    }

    @PostMapping("/{id}/setup-link")
    public SetupLinkResponse regenerateSetupLink(@PathVariable UUID id) {
        return userService.regenerateSetupLink(id);
    }

    @PatchMapping("/{id}")
    public AdminUserResponse update(@AuthenticationPrincipal User actor,
                                    @PathVariable UUID id,
                                    @Valid @RequestBody UpdateUserRequest req) {
        return userService.update(id, req, actor);
    }
}
