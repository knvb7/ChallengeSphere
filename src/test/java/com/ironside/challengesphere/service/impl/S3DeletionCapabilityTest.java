package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.util.GetSignedUrlForS3;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3DeletionCapabilityTest {

    private static final List<Pattern> FORBIDDEN_DELETION_PATTERNS = List.of(
            Pattern.compile("\\bDeleteObjects?Request\\b"),
            Pattern.compile("\\.\\s*deleteObjects?\\s*\\("),
            Pattern.compile("::\\s*deleteObjects?\\b"));

    @Test
    void runtimeExposesOnlyTheNarrowModerationDeletionApi() {
        assertFalse(hasMethod(MediaUrlService.class, "deleteMediaObjects"));
        assertFalse(hasMethod(GetSignedUrlForS3.class, "deleteObject"));
        assertTrue(hasMethod(ModeratedMediaObjectStore.class, "delete"));
    }

    @Test
    void productionDeletionCapabilityIsConfinedToTheS3Utility() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        List<String> violations = new ArrayList<>();

        try (var paths = Files.walk(sourceRoot)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                String content = Files.readString(source);
                if (FORBIDDEN_DELETION_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(content).find())) {
                    violations.add(source.toString());
                }
            }
        }

        assertEquals(
                List.of(Path.of("src", "main", "java", "com", "ironside", "challengesphere", "service", "impl", "ModeratedMediaObjectStore.java").toString()),
                violations);
    }

    @Test
    void detectsSingularAndPluralDeleteRequestTypeMutations() {
        assertTrue(containsS3DeletionCapability("DeleteObjectRequest request;"));
        assertTrue(containsS3DeletionCapability("DeleteObjectsRequest request;"));
    }

    @Test
    void detectsWhitespaceSeparatedDeleteCallMutations() {
        assertTrue(containsS3DeletionCapability("s3Client.deleteObject \n (request);"));
        assertTrue(containsS3DeletionCapability("s3Client.deleteObjects\t (request);"));
    }

    @Test
    void detectsDeleteMethodReferenceMutations() {
        assertTrue(containsS3DeletionCapability("s3Client::deleteObject"));
        assertTrue(containsS3DeletionCapability("s3Client:: deleteObjects"));
    }

    private static boolean containsS3DeletionCapability(String source) {
        return FORBIDDEN_DELETION_PATTERNS.stream()
                .anyMatch(pattern -> pattern.matcher(source).find());
    }

    private static boolean hasMethod(Class<?> type, String methodName) {
        return Arrays.stream(type.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals(methodName));
    }
}
