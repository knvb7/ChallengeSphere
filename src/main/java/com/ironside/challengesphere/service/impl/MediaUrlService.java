package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.util.GetSignedUrlForS3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MediaUrlService {

    private static final Logger logger = LogManager.getLogger(MediaUrlService.class);
    private static final long READ_SIGNED_URL_TTL_SECONDS = 6 * 60 * 60;
    private static final long READ_SIGNED_URL_CACHE_SAFETY_SECONDS = 5 * 60;
    private static final String PRIVATE_IMAGE_CACHE_CONTROL = "private, max-age=3600";

    @Value("${aws.s3.bucket.name}")
    private String bucketName;

    private final Map<String, CachedSignedUrl> readUrlCache = new ConcurrentHashMap<>();

    public String toReadableMediaUrls(String mediaUrls) {
        if (mediaUrls == null || mediaUrls.isBlank()) {
            return mediaUrls;
        }

        return String.join(",", toReadableMediaUrlList(mediaUrls));
    }

    public List<String> toReadableMediaUrlList(String mediaUrls) {
        if (mediaUrls == null || mediaUrls.isBlank()) {
            return List.of();
        }

        String[] urls = mediaUrls.split(",");
        List<String> readableUrls = new ArrayList<>();
        for (String rawUrl : urls) {
            String url = rawUrl == null ? "" : rawUrl.trim();
            if (!url.isEmpty()) {
                readableUrls.add(toReadableMediaUrl(url));
            }
        }
        return readableUrls;
    }

    public String toReadableMediaUrl(String mediaUrl) {
        String objectName = extractS3ObjectName(mediaUrl);
        if (objectName == null || objectName.isBlank()) {
            return mediaUrl;
        }

        try {
            String cacheKey = cacheKey(objectName);
            Instant now = Instant.now();
            CachedSignedUrl cached = readUrlCache.get(cacheKey);
            if (cached != null && cached.refreshAfter().isAfter(now)) {
                return cached.url();
            }

            CachedSignedUrl resolved = readUrlCache.compute(cacheKey, (key, localValue) -> {
                Instant computeNow = Instant.now();
                if (localValue != null && localValue.refreshAfter().isAfter(computeNow)) {
                    return localValue;
                }

                String signedUrl = GetSignedUrlForS3.generateV4GetObjectSignedUrl(
                        bucketName,
                        objectName,
                        READ_SIGNED_URL_TTL_SECONDS
                );
                return new CachedSignedUrl(
                        signedUrl,
                        computeNow.plusSeconds(READ_SIGNED_URL_TTL_SECONDS - READ_SIGNED_URL_CACHE_SAFETY_SECONDS)
                );
            });
            return resolved.url();
        } catch (Exception e) {
            logger.warn("Failed to sign readable media URL for object {}: {}", objectName, e.getMessage());
            return mediaUrl;
        }
    }

    public String createUploadUrl(
            String objectName,
            long expiresInSeconds,
            GeneratePresignedUrlsRequest.ContentType contentType) {
        return GetSignedUrlForS3.generatePutObjectSignedUrl(
                bucketName,
                objectName,
                expiresInSeconds,
                contentType
        );
    }

    public void applyPrivateImageCacheHeaders(List<String> mediaUrls) {
        if (mediaUrls == null || mediaUrls.isEmpty()) {
            return;
        }

        for (String mediaUrl : mediaUrls) {
            String objectName = extractS3ObjectName(mediaUrl);
            if (objectName == null || objectName.isBlank()) {
                continue;
            }

            try {
                GetSignedUrlForS3.updateObjectCacheControl(bucketName, objectName, PRIVATE_IMAGE_CACHE_CONTROL);
            } catch (Exception e) {
                logger.warn("Failed to set cache headers for S3 object {}: {}", objectName, e.getMessage());
            }
        }
    }

    public void uploadObject(String objectName, byte[] content, String contentType) {
        GetSignedUrlForS3.uploadObject(bucketName, objectName, content, contentType);
    }

    public List<String> toStoredMediaUrls(List<String> mediaUrls) {
        if (mediaUrls == null || mediaUrls.isEmpty()) {
            return List.of();
        }

        List<String> storedUrls = new ArrayList<>();
        for (String mediaUrl : mediaUrls) {
            if (mediaUrl == null || mediaUrl.isBlank()) {
                continue;
            }

            String objectName = extractS3ObjectName(mediaUrl);
            storedUrls.add(objectName == null || objectName.isBlank()
                    ? stripQuery(mediaUrl.trim())
                    : objectName);
        }
        return storedUrls;
    }

    public String toStoredMediaUrlsString(List<String> mediaUrls) {
        List<String> storedUrls = toStoredMediaUrls(mediaUrls);
        return storedUrls.isEmpty() ? null : String.join(",", storedUrls);
    }

    public void deleteMediaObjects(List<String> mediaUrls) {
        if (mediaUrls == null || mediaUrls.isEmpty()) {
            return;
        }

        for (String objectName : toS3ObjectNames(mediaUrls)) {
            try {
                GetSignedUrlForS3.deleteObject(bucketName, objectName);
            } catch (Exception e) {
                logger.warn("Failed to delete S3 object {}: {}", objectName, e.getMessage());
            }
        }
    }

    List<String> toS3ObjectNames(List<String> mediaUrls) {
        if (mediaUrls == null || mediaUrls.isEmpty()) {
            return List.of();
        }

        List<String> objectNames = new ArrayList<>();
        for (String mediaUrl : mediaUrls) {
            String objectName = extractS3ObjectName(mediaUrl);
            if (objectName != null && !objectName.isBlank()) {
                objectNames.add(objectName);
            }
        }
        return objectNames;
    }

    private String extractS3ObjectName(String mediaUrl) {
        if (mediaUrl == null || mediaUrl.isBlank()) {
            return null;
        }

        String trimmed = mediaUrl.trim();
        String s3Prefix = "s3://" + bucketName + "/";
        if (trimmed.startsWith(s3Prefix)) {
            return stripQuery(trimmed.substring(s3Prefix.length()));
        }

        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return stripQuery(trimmed).replaceFirst("^/+", "");
        }

        try {
            URI uri = URI.create(trimmed);
            String host = uri.getHost();
            if (host == null) {
                return null;
            }

            String path = uri.getPath();
            if (path == null || path.isBlank()) {
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
            logger.debug("Could not parse media URL as S3 object: {}", mediaUrl);
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

    private String cacheKey(String objectName) {
        return bucketName + "/" + objectName;
    }

    private record CachedSignedUrl(String url, Instant refreshAfter) {
    }
}
