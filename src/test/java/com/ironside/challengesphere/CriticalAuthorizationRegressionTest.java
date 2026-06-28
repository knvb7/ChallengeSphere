package com.ironside.challengesphere;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CriticalAuthorizationRegressionTest {

    private static String source(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void securityConfigEnablesMethodSecurityForAdminRoleChecks() throws Exception {
        String securityConfig = source("src/main/java/com/ironside/challengesphere/config/SecurityConfig.java");

        assertTrue(securityConfig.contains("@EnableMethodSecurity"),
                "Spring method security must be enabled so @PreAuthorize admin gates are enforced");
    }

    @Test
    void userPrincipalUsesPersistedAdminRole() throws Exception {
        String user = source("src/main/java/com/ironside/challengesphere/model/User.java");
        String principal = source("src/main/java/com/ironside/challengesphere/security/UserPrincipal.java");
        String migration = source("src/main/resources/db/migration/V24__add_user_roles.sql");

        assertTrue(user.contains("private UserRole role"),
                "User must persist a role so admin users can be distinguished from normal users");
        assertTrue(principal.contains("ROLE_ADMIN"),
                "Admin users must receive ROLE_ADMIN authority");
        assertTrue(migration.contains("ADD COLUMN IF NOT EXISTS role"),
                "Databases must migrate existing users to an explicit default USER role");
    }

    @Test
    void adminAndInternalMutationEndpointsRequireAdminRole() throws Exception {
        assertTrue(source("src/main/java/com/ironside/challengesphere/controller/AdminController.java")
                        .contains("@PreAuthorize(\"hasRole('ADMIN')\")"),
                "Moderation/admin controller must be restricted to admins");
        assertTrue(source("src/main/java/com/ironside/challengesphere/controller/CacheAdminController.java")
                        .contains("@PreAuthorize(\"hasRole('ADMIN')\")"),
                "Cache admin controller must be restricted to admins");

        String userController = source("src/main/java/com/ironside/challengesphere/controller/UserController.java");
        assertTrue(userController.contains("@PatchMapping(\"/activation\")")
                        && userController.contains("@PreAuthorize(\"hasRole('ADMIN')\")"),
                "User activation toggle must be admin-only");

        String challengeController = source("src/main/java/com/ironside/challengesphere/controller/ChallengeController.java");
        assertTrue(challengeController.contains("@DeleteMapping")
                        && challengeController.contains("@PreAuthorize(\"hasRole('ADMIN')\")"),
                "Bulk challenge deletion/disable endpoints must be admin-only");

        String leaderboardController = source("src/main/java/com/ironside/challengesphere/controller/LeaderboardController.java");
        assertTrue(leaderboardController.contains("@PostMapping(\"/score\")")
                        && leaderboardController.contains("@PreAuthorize(\"hasRole('ADMIN')\")"),
                "Manual leaderboard score mutation must be admin-only");
    }

    @Test
    void inviteEndpointsDeriveActorFromAuthenticatedPrincipal() throws Exception {
        String controller = source("src/main/java/com/ironside/challengesphere/controller/InviteController.java");
        String createRequest = source("src/main/java/com/ironside/challengesphere/api/request/CreateInviteLinkRequest.java");
        String redeemRequest = source("src/main/java/com/ironside/challengesphere/api/request/RedeemInviteRequest.java");

        assertTrue(controller.contains("@AuthenticationPrincipal UserPrincipal principal"),
                "Invite endpoints must receive the authenticated principal");
        assertTrue(controller.contains("SecurityAccess.requireUserId(principal)"),
                "Invite endpoints must derive actor userId from the authenticated principal");
        assertTrue(createRequest.contains("private Long userId;")
                        && !createRequest.contains("@NotNull(message = \"User ID is required\")\n    private Long userId;"),
                "Create invite requests must not require/trust client-supplied userId");
        assertTrue(redeemRequest.contains("private Long userId;")
                        && !redeemRequest.contains("@NotNull(message = \"User ID is required\")\n    private Long userId;"),
                "Redeem invite requests must not require/trust client-supplied userId");
    }
}
