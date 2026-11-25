package com.memecreator.web;

import com.memecreator.security.ApiKeyStore;
import com.memecreator.security.ApiKeyStore.IssuedKey;
import com.memecreator.security.ApiKeyStore.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** API keys for programs that call memecreator. ADMIN only (see SecurityConfig). Keys are never shown again. */
@Tag(name = "Admin: API keys", description = "Issue, list and revoke API keys for programs such as footballmeme")
@RestController
@RequestMapping("/api/admin/api-keys")
public class ApiKeyAdminController {

    private final ApiKeyStore store;

    public ApiKeyAdminController(ApiKeyStore store) {
        this.store = store;
    }

    /** A key without its secret. */
    public record ApiKeyInfo(String client, Set<Role> roles, Instant createdAt) {
    }

    /** @param roles default [SERVICE] */
    public record IssueRequest(@NotBlank String client, Set<Role> roles) {
    }

    @Operation(summary = "List API keys", description = "Clients and roles; the keys themselves are not stored.")
    @GetMapping
    public List<ApiKeyInfo> list() {
        return store.list().stream().map(c -> new ApiKeyInfo(c.client(), c.roles(), c.createdAt())).toList();
    }

    @Operation(summary = "Issue (or rotate) a client's key",
            description = "Returns the key once. Issuing again for the same client replaces the old key at once.")
    @PostMapping
    public IssuedKey issue(@Valid @RequestBody IssueRequest request) {
        Set<Role> roles = request.roles() == null || request.roles().isEmpty()
                ? EnumSet.of(Role.SERVICE) : request.roles();
        return store.issue(request.client(), roles);
    }

    @Operation(summary = "Revoke a client's key", description = "204, or 404 when the client has no key.")
    @DeleteMapping("/{client}")
    public ResponseEntity<Void> revoke(@PathVariable String client) {
        return store.revoke(client) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
