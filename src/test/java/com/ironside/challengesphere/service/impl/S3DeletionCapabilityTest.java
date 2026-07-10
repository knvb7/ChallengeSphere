package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.util.GetSignedUrlForS3;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;

class S3DeletionCapabilityTest {

    @Test
    void runtimeExposesNoS3DeletionApi() {
        assertFalse(hasMethod(MediaUrlService.class, "deleteMediaObjects"));
        assertFalse(hasMethod(GetSignedUrlForS3.class, "deleteObject"));
    }

    private static boolean hasMethod(Class<?> type, String methodName) {
        return Arrays.stream(type.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals(methodName));
    }
}
