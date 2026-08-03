package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.util.MediaTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SubmissionMediaKeyPolicyTest {

    private static final String PHOTO =
            "submissions/user_7/SUBMISSION_PHOTO/1752148800000_123e4567-e89b-12d3-a456-426614174000.jpg";
    private static final String VIDEO =
            "submissions/user_7/SUBMISSION_VIDEO/1752148800000_123e4567-e89b-12d3-a456-426614174001.mp4";
    private static final UUID UNIQUE_ID =
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    private final SubmissionMediaKeyPolicy policy = new SubmissionMediaKeyPolicy();

    @Test
    void acceptsCanonicalOwnedPhoto() throws Exception {
        assertEquals(List.of(PHOTO), policy.validateAndNormalize(7L, List.of(PHOTO)));
    }

    @ParameterizedTest
    @MethodSource("keysWithSurroundingControlCharacters")
    void rejectsControlCharactersBeforeTrimming(String rawKey) {
        assertThrows(
                ChallengeSphereException.class,
                () -> policy.validateAndNormalize(7L, List.of(rawKey)));
    }

    static Stream<String> keysWithSurroundingControlCharacters() {
        return Stream.of("\n" + PHOTO, PHOTO + "\t");
    }

    @Test
    void permitsHarmlessSurroundingOrdinaryWhitespace() throws Exception {
        assertEquals(List.of(PHOTO), policy.validateAndNormalize(7L, List.of("  " + PHOTO + "  ")));
    }

    @Test
    void normalizesMissingMediaBeforeServiceAppliesRequiredPhotoRule() throws Exception {
        assertEquals(List.of(), policy.validateAndNormalize(7L, null));
        assertEquals(List.of(), policy.validateAndNormalize(7L, List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "submissions/user_8/SUBMISSION_PHOTO/1752148800000_123e4567-e89b-12d3-a456-426614174000.jpg",
            "submissions/user_70/SUBMISSION_PHOTO/1752148800000_123e4567-e89b-12d3-a456-426614174000.jpg",
            "profiles/user_7/1752148800000_123e4567-e89b-12d3-a456-426614174000.jpg",
            "victim.jpg",
            "https://bucket.s3.amazonaws.com/submissions/user_7/SUBMISSION_PHOTO/victim.jpg",
            "s3://bucket/submissions/user_7/SUBMISSION_PHOTO/victim.jpg",
            "submissions/user_7/../user_8/SUBMISSION_PHOTO/victim.jpg",
            "submissions/user_7/SUBMISSION_PHOTO/1752148800000_123e4567-e89b-12d3-a456-426614174000.jpg?x=1",
            "submissions/user_7/SUBMISSION_PHOTO/1752148800000_123e4567-e89b-12d3-a456-426614174000.mp4",
            "submissions/user_7/SUBMISSION_VIDEO/1752148800000_123e4567-e89b-12d3-a456-426614174000.mp4",
            "submissions/user_7/SUBMISSION_VIDEO/1752148800000_123e4567-e89b-12d3-a456-426614174000.jpg"
    })
    void rejectsNonCanonicalOrForeignKeys(String key) {
        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> policy.validateAndNormalize(7L, List.of(key)));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
    }

    @Test
    void rejectsDuplicateAndOversizedLists() {
        assertThrows(ChallengeSphereException.class,
                () -> policy.validateAndNormalize(7L, List.of(PHOTO, PHOTO)));
        assertThrows(ChallengeSphereException.class,
                () -> policy.validateAndNormalize(7L, List.of(PHOTO, VIDEO, PHOTO, VIDEO, PHOTO, VIDEO)));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void validationRejectsNonPositiveUserIds(long userId) {
        assertThrows(
                ChallengeSphereException.class,
                () -> policy.validateAndNormalize(userId, List.of()));
    }

    @ParameterizedTest
    @CsvSource({
            "SUBMISSION_PHOTO,IMAGE_JPEG,.jpg",
            "SUBMISSION_PHOTO,IMAGE_PNG,.png",
            "SUBMISSION_PHOTO,IMAGE_GIF,.gif"
    })
    void createsCanonicalKeyFromValidatedMediaTypeAndContentType(
            MediaTypes mediaType,
            GeneratePresignedUrlsRequest.ContentType contentType,
            String expectedSuffix) throws Exception {
        String key = policy.createKey(7L, mediaType, contentType, 1752148800000L, UNIQUE_ID);
        assertEquals(
                "submissions/user_7/" + mediaType + "/1752148800000_"
                        + "123e4567-e89b-12d3-a456-426614174000" + expectedSuffix,
                key);
    }

    @Test
    void generatedKeyRoundTripsThroughValidation() throws Exception {
        String key = policy.createKey(
                7L,
                MediaTypes.SUBMISSION_PHOTO,
                GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG,
                1752148800000L,
                UNIQUE_ID);

        assertEquals(List.of(key), policy.validateAndNormalize(7L, List.of(key)));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void keyCreationRejectsNonPositiveUserIds(long userId) {
        assertThrows(ChallengeSphereException.class, () -> policy.createKey(
                userId,
                MediaTypes.SUBMISSION_PHOTO,
                GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG,
                1752148800000L,
                UNIQUE_ID));
    }

    @ParameterizedTest
    @ValueSource(longs = {1_000_000_000_000L, 9_999_999_999_999L})
    void keyCreationAcceptsInclusiveThirteenDigitEpochBoundaries(long epochMillis) throws Exception {
        String key = policy.createKey(
                7L,
                MediaTypes.SUBMISSION_PHOTO,
                GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG,
                epochMillis,
                UNIQUE_ID);

        assertEquals(List.of(key), policy.validateAndNormalize(7L, List.of(key)));
    }

    @ParameterizedTest
    @ValueSource(longs = {999_999_999_999L, 10_000_000_000_000L})
    void keyCreationRejectsEpochsOutsideThirteenDigitRange(long epochMillis) {
        assertThrows(ChallengeSphereException.class, () -> policy.createKey(
                7L,
                MediaTypes.SUBMISSION_PHOTO,
                GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG,
                epochMillis,
                UNIQUE_ID));
    }

    @Test
    void keyCreationRejectsMediaAndContentTypeMismatch() {
        assertThrows(ChallengeSphereException.class, () -> policy.createKey(
                7L,
                MediaTypes.SUBMISSION_PHOTO,
                GeneratePresignedUrlsRequest.ContentType.VIDEO_MP4,
                1752148800000L,
                UNIQUE_ID));
    }
}
