package com.ironside.challengesphere;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedDisabledUserCountRegressionTest {

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void usersApiExposesTotalUserCountForDisabledFeedMessaging() throws Exception {
        String controller = read("src/main/java/com/ironside/challengesphere/controller/UserController.java");
        String service = read("src/main/java/com/ironside/challengesphere/service/UserService.java");
        String serviceImpl = read("src/main/java/com/ironside/challengesphere/service/impl/UserServiceImpl.java");

        assertTrue(controller.contains("@GetMapping(\"/count\")"));
        assertTrue(controller.contains("userService.getTotalUserCount()"));
        assertTrue(controller.contains("Map.of(\"totalUsers\", totalUsers)"));
        assertTrue(service.contains("long getTotalUserCount();"));
        assertTrue(serviceImpl.contains("public long getTotalUserCount()"));
        assertTrue(serviceImpl.contains("userRepository.count()"));
    }
}
