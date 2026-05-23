package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.util.GetSignedUrlForGcs;
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

    @Value("${gcs.bucket.name}")
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
        String objectName = extractGcsObjectName(mediaUrl);
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

            String signedUrl = GetSignedUrlForGcs.generateV4GetObjectSignedUrl(bucketName, objectName, READ_SIGNED_URL_TTL_SECONDS);
            readUrlCache.put(cacheKey, new CachedSignedUrl(
                    signedUrl,
                    now.plusSeconds(READ_SIGNED_URL_TTL_SECONDS - READ_SIGNED_URL_CACHE_SAFETY_SECONDS)
            ));
            return signedUrl;
        } catch (Exception e) {
            logger.warn("Failed to sign readable media URL for object {}: {}", objectName, e.getMessage());
            return mediaUrl;
        }
    }

    public void applyPrivateImageCacheHeaders(List<String> mediaUrls) {
        if (mediaUrls == null || mediaUrls.isEmpty()) {
            return;
        }

        for (String mediaUrl : mediaUrls) {
            String objectName = extractGcsObjectName(mediaUrl);
            if (objectName == null || objectName.isBlank()) {
                continue;
            }

            try {
                GetSignedUrlForGcs.updateObjectCacheControl(bucketName, objectName, PRIVATE_IMAGE_CACHE_CONTROL);
            } catch (Exception e) {
                logger.warn("Failed to set cache headers for GCS object {}: {}", objectName, e.getMessage());
            }
        }
    }

    public void uploadObject(String objectName, byte[] content, String contentType) {
        GetSignedUrlForGcs.uploadObject(bucketName, objectName, content, contentType);
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

            String objectName = extractGcsObjectName(mediaUrl);
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

        for (String objectName : toGcsObjectNames(mediaUrls)) {
            try {
                GetSignedUrlForGcs.deleteObject(bucketName, objectName);
            } catch (Exception e) {
                logger.warn("Failed to delete GCS object {}: {}", objectName, e.getMessage());
            }
        }
    }

    List<String> toGcsObjectNames(List<String> mediaUrls) {
        if (mediaUrls == null || mediaUrls.isEmpty()) {
            return List.of();
        }

        List<String> objectNames = new ArrayList<>();
        for (String mediaUrl : mediaUrls) {
            String objectName = extractGcsObjectName(mediaUrl);
            if (objectName != null && !objectName.isBlank()) {
                objectNames.add(objectName);
            }
        }
        return objectNames;
    }

    private String extractGcsObjectName(String mediaUrl) {
        if (mediaUrl == null || mediaUrl.isBlank()) {
            return null;
        }

        String gcsPrefix = "gs://" + bucketName + "/";
        if (mediaUrl.startsWith(gcsPrefix)) {
            return stripQuery(mediaUrl.substring(gcsPrefix.length()));
        }

        if (!mediaUrl.startsWith("http://") && !mediaUrl.startsWith("https://")) {
            return stripQuery(mediaUrl);
        }

        try {
            URI uri = URI.create(mediaUrl);
            String host = uri.getHost();
            if (host == null) {
                return null;
            }

            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                return null;
            }

            String objectPath = null;
            if ("storage.googleapis.com".equals(host)) {
                String bucketPrefix = "/" + bucketName + "/";
                if (path.startsWith(bucketPrefix)) {
                    objectPath = path.substring(bucketPrefix.length());
                }
            } else if (host.equals(bucketName + ".storage.googleapis.com")) {
                objectPath = path.startsWith("/") ? path.substring(1) : path;
            }

            return objectPath == null ? null : URLDecoder.decode(objectPath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.debug("Could not parse media URL as GCS object: {}", mediaUrl);
            return null;
        }
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
