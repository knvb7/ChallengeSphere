package com.ironside.challengesphere;

import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.impl.SubmissionServiceImpl;
import com.ironside.challengesphere.util.MediaTypes;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SubmissionUploadAbuseControlsTest {

    @Test
    void presignedUrlRequestRejectsMoreThanFiveSubmissionMediaFiles() {
        GeneratePresignedUrlsRequest request = new GeneratePresignedUrlsRequest();
        request.setMediaFiles(new ArrayList<>(List.of(
                mediaFile("one.jpg", MediaTypes.SUBMISSION_PHOTO, GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG),
                mediaFile("two.jpg", MediaTypes.SUBMISSION_PHOTO, GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG),
                mediaFile("three.jpg", MediaTypes.SUBMISSION_PHOTO, GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG),
                mediaFile("four.jpg", MediaTypes.SUBMISSION_PHOTO, GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG),
                mediaFile("five.jpg", MediaTypes.SUBMISSION_PHOTO, GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG),
                mediaFile("six.jpg", MediaTypes.SUBMISSION_PHOTO, GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG)
        )));

        Set<ConstraintViolation<GeneratePresignedUrlsRequest>> violations = validator().validate(request);

        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("mediaFiles")
                        && v.getMessage().contains("at most 5")),
                "Submission presign requests must reject oversized media batches before service work starts");
    }

    @Test
    void presignedUrlRequestValidatesNestedMediaFileInfo() {
        GeneratePresignedUrlsRequest request = new GeneratePresignedUrlsRequest();
        GeneratePresignedUrlsRequest.MediaFileInfo mediaFile = new GeneratePresignedUrlsRequest.MediaFileInfo();
        request.setMediaFiles(List.of(mediaFile));

        Set<ConstraintViolation<GeneratePresignedUrlsRequest>> violations = validator().validate(request);

        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().contains("mediaFiles")
                        && v.getMessage().contains("File name is required")),
                "Nested media file fields must be validated, not just the outer list");
    }

    @Test
    void submissionPresignRejectsMismatchedMediaAndContentTypesBeforeUserLookup() {
        SubmissionServiceImpl service = new SubmissionServiceImpl();
        UserRepository userRepository = mock(UserRepository.class);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);

        GeneratePresignedUrlsRequest request = new GeneratePresignedUrlsRequest();
        request.setUserId(10L);
        request.setMediaFiles(List.of(mediaFile(
                "proof.mp4",
                MediaTypes.SUBMISSION_PHOTO,
                GeneratePresignedUrlsRequest.ContentType.VIDEO_MP4
        )));

        ChallengeSphereException exception = assertThrows(ChallengeSphereException.class,
                () -> service.generatePresignedUrls(request));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertTrue(exception.getResponseMessage().contains("Content type"));
        verifyNoInteractions(userRepository);
    }

    @Test
    void fallbackUploadRejectsOversizedFileBeforeReadingBytes() {
        SubmissionServiceImpl service = new SubmissionServiceImpl();
        UserRepository userRepository = mock(UserRepository.class);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);

        MultipartFile file = new SizeOnlyMultipartFile("proof.jpg", (8 * 1024 * 1024) + 1);

        ChallengeSphereException exception = assertThrows(ChallengeSphereException.class,
                () -> service.uploadSubmissionMedia(
                        10L,
                        file,
                        MediaTypes.SUBMISSION_PHOTO,
                        GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertTrue(exception.getResponseMessage().contains("exceeds"));
        verifyNoInteractions(userRepository);
    }

    private static Validator validator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        return factory.getValidator();
    }

    private static GeneratePresignedUrlsRequest.MediaFileInfo mediaFile(
            String fileName,
            MediaTypes mediaType,
            GeneratePresignedUrlsRequest.ContentType contentType) {
        GeneratePresignedUrlsRequest.MediaFileInfo info = new GeneratePresignedUrlsRequest.MediaFileInfo();
        info.setFileName(fileName);
        info.setMediaType(mediaType);
        info.setContentType(contentType);
        return info;
    }

    private static class SizeOnlyMultipartFile implements MultipartFile {
        private final String originalFilename;
        private final long size;

        private SizeOnlyMultipartFile(String originalFilename, long size) {
            this.originalFilename = originalFilename;
            this.size = size;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return "image/jpeg";
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public long getSize() {
            return size;
        }

        @Override
        public byte[] getBytes() throws IOException {
            throw new IOException("Oversized uploads must be rejected before reading bytes into memory");
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void transferTo(java.io.File dest) {
            throw new UnsupportedOperationException("Not needed for this test");
        }
    }
}
