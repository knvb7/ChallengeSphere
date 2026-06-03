package com.ironside.challengesphere;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class BeanCycleRegressionTest {

    @Test
    void notificationServiceDoesNotInjectUserService() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/ironside/challengesphere/service/impl/NotificationServiceImpl.java"
        ));

        assertFalse(
                source.contains("import com.ironside.challengesphere.service.UserService;")
                        || source.contains("UserService userService"),
                "NotificationServiceImpl must not inject UserService because UserServiceImpl already injects NotificationService"
        );
    }
}
