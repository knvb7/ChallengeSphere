package com.ironside.challengesphere.service.impl;

import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Media;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.MediaRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.MediaService;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.repository.ChallengeRepository;

@Service
@RequiredArgsConstructor
public class MediaServiceImpl implements MediaService {

    private final Logger logger = LogManager.getLogger(MediaServiceImpl.class);

    @Autowired
    private final MediaRepository mediaRepository;
    @Autowired
    private final ChallengeRepository challengeRepository;
    @Autowired
    private final UserRepository userRepository;

    @Value("${gcs.bucket.name}")
    private String bucketName;

    private final Storage storage = StorageOptions.getDefaultInstance().getService();

    @Override
    public Media uploadMedia(MultipartFile file, Long uploaderId, Long challengeId) throws ChallengeSphereException {
        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.contains(".") ? originalFilename.substring(originalFilename.lastIndexOf('.')) : "";
        String filename = UUID.randomUUID() + extension;
        String contentType = file.getContentType();
        String mediaType = contentType != null && contentType.startsWith("video") ? "video" : "photo";
        try {
            BlobInfo blobInfo = BlobInfo.newBuilder(bucketName, filename)
                    .setContentType(contentType)
                    .build();
            storage.create(blobInfo, file.getBytes());
            String url = String.format("https://storage.googleapis.com/%s/%s", bucketName, filename);
            Challenge challenge = challengeRepository.findById(challengeId)
                    .orElseThrow(() -> new ChallengeSphereException(
                        "Challenge not found: " + challengeId,
                        HttpStatus.NOT_FOUND,
                        logger
                    ));
            User uploader = userRepository.findById(uploaderId)
                    .orElseThrow(() -> new ChallengeSphereException(
                        "User not found: " + uploaderId,
                        HttpStatus.NOT_FOUND,
                        logger
                    ));
            Media media = Media.builder()
                    .filename(filename)
                    .url(url)
                    .mediaType(mediaType)
                    .user(uploader)
                    .createdAt(Timestamp.from(Instant.now()))
                    .challenge(challenge)
                    .build();
            return mediaRepository.save(media);
        } catch (Exception e) {
            throw new ChallengeSphereException(
                "Failed to upload file to GCS",
                HttpStatus.INTERNAL_SERVER_ERROR,
                logger,
                e
            );
        }
    }

    @Override
    public String generateSignedUploadUrl(String filename, String contentType) throws ChallengeSphereException {
        try {
            BlobInfo blobInfo = BlobInfo.newBuilder(bucketName, filename)
                    .setContentType(contentType)
                    .build();
            // Signed URL valid for 15 minutes
            return storage.signUrl(blobInfo, 15, java.util.concurrent.TimeUnit.MINUTES,
                    Storage.SignUrlOption.withV4Signature(),
                    Storage.SignUrlOption.httpMethod(HttpMethod.PUT),
                    Storage.SignUrlOption.withContentType()).toString();
        } catch (Exception e) {
            throw new ChallengeSphereException(
                "Failed to generate signed upload URL: " + e.getMessage(),
                HttpStatus.INTERNAL_SERVER_ERROR,
                logger,
                e
            );
        }
    }

    @Override
    public Media registerMedia(Media media, Long challengeId) throws ChallengeSphereException {
        try {
            Challenge challenge = challengeRepository.findById(challengeId)
                    .orElseThrow(() -> new ChallengeSphereException(
                        "Challenge not found: " + challengeId,
                        HttpStatus.NOT_FOUND,
                        logger
                    ));
            media.setChallenge(challenge);
            // Set createdAt if not set
            if (media.getCreatedAt() == null) {
                media.setCreatedAt(Timestamp.from(Instant.now()));
            }
            return mediaRepository.save(media);
        } catch (Exception e) {
            throw new ChallengeSphereException(
                "Failed to register media: " + e.getMessage(),
                HttpStatus.INTERNAL_SERVER_ERROR,
                logger,
                e
            );
        }
    }
}
