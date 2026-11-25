package com.memecreator.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Locale;

/**
 * @param adminEmails  people who get the ADMIN role when they sign in with Google or Facebook using this email
 * @param apiKeysFile  where {@link ApiKeyStore} keeps the key hashes; inside the private folder, never served
 */
@Validated
@ConfigurationProperties(prefix = "meme.security")
public record SecurityProperties(
        @DefaultValue("") List<String> adminEmails,
        @DefaultValue("private/api-keys.json") @NotBlank String apiKeysFile
) {

    public boolean isAdminEmail(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        String e = email.trim().toLowerCase(Locale.ROOT);
        return adminEmails.stream().anyMatch(a -> a != null && a.trim().toLowerCase(Locale.ROOT).equals(e));
    }
}
