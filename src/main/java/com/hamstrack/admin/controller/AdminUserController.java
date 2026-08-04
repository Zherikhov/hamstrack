package com.hamstrack.admin.controller;

import com.hamstrack.admin.dto.*;
import com.hamstrack.admin.service.AdminUserService;
import com.hamstrack.auth.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
    public List<AdminUserResponse> list() {
        return userService.list();
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
