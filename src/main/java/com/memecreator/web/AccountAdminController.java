package com.memecreator.web;

import com.memecreator.account.AccountService;
import com.memecreator.account.UserAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Accounts, ADMIN only (see SecurityConfig). Premium can't be bought yet, so during the alpha an admin puts
 * testers on PREMIUM (or back on FREE) here.
 */
@Tag(name = "Admin: accounts", description = "Change an account's plan while payments are not open")
@RestController
@RequestMapping("/api/admin/accounts")
public class AccountAdminController {

    private final AccountService accounts;

    public AccountAdminController(AccountService accounts) {
        this.accounts = accounts;
    }

    public record PlanRequest(@NotBlank @Email String email, @NotNull UserAccount.Plan plan) {
    }

    public record PlanChanged(long accountId, String email, UserAccount.Plan plan) {
    }

    @Operation(summary = "Set an account's plan", description = "FREE or PREMIUM. 400 when no account has the email.")
    @PutMapping("/plan")
    public PlanChanged setPlan(@Valid @RequestBody PlanRequest r) {
        UserAccount a = accounts.setPlan(r.email(), r.plan());
        return new PlanChanged(a.getId(), a.getEmail(), a.getPlan());
    }
}
