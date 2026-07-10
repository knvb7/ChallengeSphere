package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.util.GetSignedUrlForS3;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3DeletionCapabilityTest {

    @Test
    void runtimeExposesNoS3DeletionApi() {
        assertFalse(hasMethod(MediaUrlService.class, "deleteMediaObjects"));
        assertFalse(hasMethod(GetSignedUrlForS3.class, "deleteObject"));
    }

    @Test
    void productionSourceContainsNoS3DeletionCapability() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        List<String> forbiddenTokens = List.of(
                "DeleteObjectRequest",
                "DeleteObjectsRequest",
                ".deleteObject(",
                ".deleteObjects(");
        List<String> violations = new ArrayList<>();

        try (var paths = Files.walk(sourceRoot)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                String content = Files.readString(source);
                for (String token : forbiddenTokens) {
                    if (content.contains(token)) {
                        violations.add(source + " contains " + token);
                    }
                }
            }
        }

        assertTrue(
                violations.isEmpty(),
                () -> "S3 deletion capability found in production source: " + String.join(", ", violations));
    }

    private static boolean hasMethod(Class<?> type, String methodName) {
        return Arrays.stream(type.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals(methodName));
    }
}
