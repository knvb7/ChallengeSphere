package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.config.AwsCredentialsProviderFactory;
import com.ironside.challengesphere.service.ContentModerationService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.DetectModerationLabelsRequest;
import software.amazon.awssdk.services.rekognition.model.DetectModerationLabelsResponse;
import software.amazon.awssdk.services.rekognition.model.Image;
import software.amazon.awssdk.services.rekognition.model.ModerationLabel;
import software.amazon.awssdk.services.rekognition.model.S3Object;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ContentModerationServiceImpl implements ContentModerationService {

    private static final Logger logger = LogManager.getLogger(ContentModerationServiceImpl.class);
    private static final Set<String> IMAGE_EXTS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic", ".heif"
    );

    @Value("${aws.region}")
    private String awsRegion;

    @Value("${aws.s3.bucket.name}")
    private String bucketName;

    @Value("${aws.credentials.profile:}")
    private String awsProfile;

    @Value("${aws.credentials.access-keys-csv:}")
    private String accessKeysCsv;

    @Value("${aws.rekognition.min-confidence:70}")
    private Float minConfidence;

    @Value("${aws.rekognition.likely-confidence:80}")
    private Float likelyConfidence;

    @Value("${aws.rekognition.very-likely-confidence:90}")
    private Float veryLikelyConfidence;

    private volatile RekognitionClient client;

    @PostConstruct
    void init() {
        try {
            this.client = RekognitionClient.builder()
                    .region(Region.of(awsRegion))
                    .credentialsProvider(AwsCredentialsProviderFactory.create(awsProfile, accessKeysCsv))
                    .build();
            logger.info("Rekognition moderation client initialized for region {}", awsRegion);
        } catch (Exception e) {
            logger.error("Failed to initialize Rekognition moderation client; moderation will no-op until fixed", e);
        }
    }

    @PreDestroy
    void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignore) {
            }
        }
    }

    @Override
    public Result scan(List<String> mediaUrls) {
        return scanInternal(mediaUrls, false);
    }

    @Override
    public Result scanStrict(List<String> mediaUrls) {
        return scanInternal(mediaUrls, true);
    }

    private Result scanInternal(List<String> mediaUrls, boolean strict) {
        if (mediaUrls == null || mediaUrls.isEmpty()) {
            return new Result(Verdict.CLEAN, List.of());
        }
        if (client == null) {
            logger.error("Rekognition client not initialized; skipping moderation for {} URLs", mediaUrls.size());
            if (strict) {
                throw new IllegalStateException("Rekognition client is not initialized");
            }
            return new Result(Verdict.CLEAN, List.of());
        }

        List<Finding> findings = new ArrayList<>();
        boolean anyVeryLikely = false;
        boolean anyLikely = false;
        int scannedCount = 0;

        for (String url : mediaUrls) {
            if (url == null || url.isBlank()) {
                continue;
            }
            if (!isImage(url)) {
                logger.debug("Skipping non-image URL from moderation: {}", url);
                continue;
            }

            String objectName = toS3ObjectName(url);
            if (objectName == null || objectName.isBlank()) {
                logger.warn("Skipping moderation for unrecognized S3 media URL: {}", url);
                if (strict) {
                    throw new IllegalArgumentException("Unrecognized S3 media URL: " + url);
                }
                continue;
            }

            DetectModerationLabelsResponse response;
            try {
                response = client.detectModerationLabels(DetectModerationLabelsRequest.builder()
                        .image(Image.builder()
                                .s3Object(S3Object.builder()
                                        .bucket(bucketName)
                                        .name(objectName)
                                        .build())
                                .build())
                        .minConfidence(minConfidence)
                        .build());
            } catch (Exception e) {
                logger.error("Rekognition moderation call failed; allowing submission through", e);
                if (strict) {
                    throw new IllegalStateException("Rekognition moderation call failed", e);
                }
                return new Result(Verdict.CLEAN, List.of());
            }

            scannedCount++;
            for (ModerationLabel label : response.moderationLabels()) {
                boolean[] hits = recordHit(findings, url, label);
                anyVeryLikely |= hits[0];
                anyLikely |= hits[1];
            }
        }

        Verdict verdict = anyVeryLikely ? Verdict.BLOCK_AND_BAN
                : anyLikely ? Verdict.FLAG_FOR_REVIEW
                : Verdict.CLEAN;
        logger.info("Rekognition moderation result: verdict={}, findings={}, scanned={}",
                verdict, findings.size(), scannedCount);
        return new Result(verdict, findings);
    }

    private boolean isImage(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        int q = lower.indexOf('?');
        if (q >= 0) lower = lower.substring(0, q);
        for (String ext : IMAGE_EXTS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private String toS3ObjectName(String url) {
        String trimmed = url.trim();
        String s3Prefix = "s3://" + bucketName + "/";
        if (trimmed.startsWith(s3Prefix)) {
            return stripQuery(trimmed.substring(s3Prefix.length()));
        }

        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            String objectKey = stripQuery(trimmed).replaceFirst("^/+", "");
            String bucketPrefix = bucketName + "/";
            return objectKey.startsWith(bucketPrefix) ? objectKey.substring(bucketPrefix.length()) : objectKey;
        }

        try {
            URI uri = URI.create(trimmed);
            String host = uri.getHost();
            String path = uri.getPath();
            if (host == null || path == null || path.isBlank()) {
                return null;
            }

            String objectPath = null;
            if (isPathStyleS3Host(host)) {
                String bucketPrefix = "/" + bucketName + "/";
                if (path.startsWith(bucketPrefix)) {
                    objectPath = path.substring(bucketPrefix.length());
                }
            } else if (isVirtualHostedS3Host(host)) {
                objectPath = path.startsWith("/") ? path.substring(1) : path;
            }

            return objectPath == null ? null : URLDecoder.decode(objectPath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.debug("Could not parse media URL as S3 object: {}", url);
            return null;
        }
    }

    private boolean isPathStyleS3Host(String host) {
        return "s3.amazonaws.com".equals(host)
                || (host.startsWith("s3.") && host.endsWith(".amazonaws.com"))
                || (host.startsWith("s3-") && host.endsWith(".amazonaws.com"));
    }

    private boolean isVirtualHostedS3Host(String host) {
        String prefix = bucketName + ".";
        if (!host.startsWith(prefix) || !host.endsWith(".amazonaws.com")) {
            return false;
        }

        String serviceHost = host.substring(prefix.length());
        return "s3.amazonaws.com".equals(serviceHost)
                || serviceHost.startsWith("s3.")
                || serviceHost.startsWith("s3-");
    }

    private String stripQuery(String value) {
        int queryStart = value.indexOf('?');
        return queryStart >= 0 ? value.substring(0, queryStart) : value;
    }

    private boolean[] recordHit(List<Finding> out, String url, ModerationLabel label) {
        Float confidence = label.confidence();
        if (confidence == null) {
            return new boolean[]{false, false};
        }

        String category = label.name() == null || label.name().isBlank()
                ? "MODERATION_LABEL"
                : label.name();
        if (label.parentName() != null && !label.parentName().isBlank()) {
            category = label.parentName() + "/" + category;
        }

        if (confidence >= veryLikelyConfidence) {
            out.add(new Finding(url, category, "VERY_LIKELY"));
            return new boolean[]{true, false};
        }
        if (confidence >= likelyConfidence) {
            out.add(new Finding(url, category, "LIKELY"));
            return new boolean[]{false, true};
        }
        return new boolean[]{false, false};
    }
}
