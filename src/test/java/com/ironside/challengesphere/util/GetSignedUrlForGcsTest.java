package com.ironside.challengesphere.util;

import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GetSignedUrlForGcsTest {

    @AfterEach
    void tearDown() throws Exception {
        setStorage(null);
    }

    @Test
    void generatePutObjectSignedUrlUsesV4WithoutSigningContentTypeHeader() throws Exception {
        Storage storage = mock(Storage.class);
        when(storage.signUrl(
                any(BlobInfo.class),
                eq(3600L),
                eq(TimeUnit.SECONDS),
                any(Storage.SignUrlOption[].class)
        )).thenReturn(new URL("https://storage.googleapis.com/test-bucket/test-object.jpg?X-Goog-SignedHeaders=host"));
        setStorage(storage);

        GetSignedUrlForGcs.generatePutObjectSignedUrl(
                "test-bucket",
                "test-object.jpg",
                3600L,
                GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG
        );

        ArgumentCaptor<Storage.SignUrlOption[]> optionsCaptor = ArgumentCaptor.forClass(Storage.SignUrlOption[].class);
        verify(storage).signUrl(
                any(BlobInfo.class),
                eq(3600L),
                eq(TimeUnit.SECONDS),
                optionsCaptor.capture()
        );

        Set<String> optionNames = Arrays.stream(optionsCaptor.getValue())
                .map(GetSignedUrlForGcsTest::optionName)
                .collect(Collectors.toSet());
        assertTrue(optionNames.contains("SIGNATURE_VERSION"));
        assertFalse(optionNames.contains("EXT_HEADERS"));
        assertFalse(optionNames.contains("CONTENT_TYPE"));
    }

    private static void setStorage(Storage storage) throws Exception {
        Field storageField = GetSignedUrlForGcs.class.getDeclaredField("storage");
        storageField.setAccessible(true);
        storageField.set(null, storage);
    }

    private static String optionName(Storage.SignUrlOption option) {
        try {
            Method getOption = Storage.SignUrlOption.class.getDeclaredMethod("getOption");
            getOption.setAccessible(true);
            return String.valueOf(getOption.invoke(option));
        } catch (Exception e) {
            throw new AssertionError("Unable to read sign URL option", e);
        }
    }
}
