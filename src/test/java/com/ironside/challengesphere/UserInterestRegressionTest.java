package com.ironside.challengesphere;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UserInterestRegressionTest {

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void userProfilePersistsInterestsAndCanSuggestUsers() throws Exception {
        String user = read("src/main/java/com/ironside/challengesphere/model/User.java");
        String request = read("src/main/java/com/ironside/challengesphere/api/request/UpdateUserRequest.java");
        String service = read("src/main/java/com/ironside/challengesphere/service/impl/UserServiceImpl.java");
        String serviceInterface = read("src/main/java/com/ironside/challengesphere/service/UserService.java");
        String repository = read("src/main/java/com/ironside/challengesphere/repository/UserRepository.java");
        String controller = read("src/main/java/com/ironside/challengesphere/controller/UserController.java");
        String migration = read("src/main/resources/db/migration/V21__add_user_interests.sql");

        assertTrue(user.contains("private List<String> interests;"));
        assertTrue(user.contains("@Convert(converter = StringListConverter.class)"));
        assertTrue(request.contains("private List<String> interests;"));
        assertTrue(service.contains("updateUserRequest.getInterests()"));
        assertTrue(service.contains("user.setInterests(updateUserRequest.getInterests())"));
        assertTrue(migration.contains("ADD COLUMN IF NOT EXISTS interests TEXT"));

        assertTrue(serviceInterface.contains("UserSearchResponse getSuggestedUsers(Long currentUserId, Integer page, Integer pageSize)"));
        assertTrue(service.contains("public UserSearchResponse getSuggestedUsers"));
        assertTrue(repository.contains("findSuggestedUsers"));
        assertTrue(repository.contains("findFallbackSuggestedUsers"));
        assertTrue(service.contains("loadFallbackSuggestedUsers"));
        assertTrue(service.contains("if (interestCsv.isBlank())"));
        assertTrue(service.contains("if (results.isEmpty()"));
        assertTrue(repository.contains("NOT EXISTS (SELECT 1 FROM challengesphere.follows"));
        assertTrue(repository.contains("user_blocks"));
        assertTrue(controller.contains("@GetMapping(\"/suggestions\")"));
        assertTrue(controller.contains("userService.getSuggestedUsers"));
    }
}
