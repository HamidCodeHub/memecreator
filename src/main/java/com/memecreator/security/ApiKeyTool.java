package com.memecreator.security;

import com.memecreator.security.ApiKeyStore.ApiClient;
import com.memecreator.security.ApiKeyStore.IssuedKey;
import com.memecreator.security.ApiKeyStore.Role;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Manage API keys from the command line, without starting the app (and without signing in). The running app
 * picks the change up by itself.
 *
 * <pre>
 * issue &lt;client&gt; [SERVICE|ADMIN ...]   new key for a client (default role SERVICE); replaces its old key
 * revoke &lt;client&gt;
 * list
 * options: --file=&lt;path&gt;   default private/api-keys.json (relative to the working directory)
 * </pre>
 *
 * IntelliJ: run this class with program arguments {@code issue footballmeme} (working directory = the project).
 * Command line, after {@code mvn package}:
 * <pre>
 * java -cp target/memecreator-0.0.1-SNAPSHOT.jar -Dloader.main=com.memecreator.security.ApiKeyTool
 *      org.springframework.boot.loader.launch.PropertiesLauncher issue footballmeme
 * </pre>
 */
public final class ApiKeyTool {

    private ApiKeyTool() {
    }

    public static void main(String[] args) {
        Path file = Path.of("private/api-keys.json");
        List<String> rest = new ArrayList<>();
        for (String a : args) {
            if (a.startsWith("--file=")) {
                file = Path.of(a.substring("--file=".length()));
            } else {
                rest.add(a);
            }
        }
        try {
            System.exit(run(new ApiKeyStore(file), rest));
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
        }
    }

    static int run(ApiKeyStore store, List<String> args) {
        String command = args.isEmpty() ? "help" : args.get(0);
        switch (command) {
            case "issue" -> {
                if (args.size() < 2) {
                    return usage();
                }
                Set<Role> roles = args.size() > 2
                        ? EnumSet.copyOf(args.subList(2, args.size()).stream()
                        .map(r -> Role.valueOf(r.toUpperCase(Locale.ROOT))).toList())
                        : EnumSet.of(Role.SERVICE);
                IssuedKey key = store.issue(args.get(1), roles);
                System.out.println("API key for '" + key.client() + "' " + key.roles() + " (stored in "
                        + store.file().toAbsolutePath() + "):");
                System.out.println();
                System.out.println("  " + key.key());
                System.out.println();
                System.out.println("It is shown only now. Give it to the client as a secret (an environment variable,");
                System.out.println("e.g. MEMECREATOR_API_KEY), never in code or git. Issuing again replaces it.");
                return 0;
            }
            case "revoke" -> {
                if (args.size() < 2) {
                    return usage();
                }
                boolean removed = store.revoke(args.get(1));
                System.out.println(removed ? "Revoked the key of '" + args.get(1) + "'."
                        : "'" + args.get(1) + "' has no key.");
                return removed ? 0 : 1;
            }
            case "list" -> {
                List<ApiClient> clients = store.list();
                if (clients.isEmpty()) {
                    System.out.println("No API keys in " + store.file().toAbsolutePath());
                }
                for (ApiClient c : clients) {
                    System.out.println(c.client() + "  " + c.roles().stream().map(Enum::name)
                            .collect(Collectors.joining(",")) + "  since " + c.createdAt());
                }
                return 0;
            }
            default -> {
                return usage();
            }
        }
    }

    private static int usage() {
        System.out.println("""
                Usage: ApiKeyTool [--file=private/api-keys.json] <command>
                  issue <client> [SERVICE|ADMIN ...]   new key (default role SERVICE); replaces the client's old key
                  revoke <client>
                  list
                Roles: %s""".formatted(Arrays.toString(Role.values())));
        return 1;
    }
}
