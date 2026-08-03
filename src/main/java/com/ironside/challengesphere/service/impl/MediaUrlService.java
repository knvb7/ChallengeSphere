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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MediaUrlService {

    private static final Logger logger = LogManager.getLogger(MediaUrlService.class);
    // Keep read URLs comfortably inside the lifetime of temporary EC2 role
    // credentials. A URL cannot outlive the session credentials that signed it.
    private static final long READ_SIGNED_URL_TTL_SECONDS = 30 * 60;
    private static final long READ_SIGNED_URL_CACHE_SAFETY_SECONDS = 5 * 60;
    private static final int MAX_READ_URL_CACHE_ENTRIES = 10_000;
    private static final Pattern PROFILE_OBJECT_KEY = Pattern.compile(
            "^profiles/user_(\\d+)/\\d{10,17}_[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(?:jpg|png|gif)$"
    );
    private static final Pattern SIGNABLE_PRIVATE_OBJECT_KEY = Pattern.compile(
            "^(?:"
                    + "submissions/user_\\d+/SUBMISSION_(?:PHOTO|VIDEO)/"
                    + "\\d{13}_[0-9a-fA-F-]{36}\\.(?:jpg|jpeg|png|gif|mp4)"
                    + "|challenges/user_\\d+/covers/"
                    + "\\d{13}_[0-9a-fA-F-]{36}\\.(?:jpg|png|gif)"
                    + "|profiles/user_\\d+/"
                    + "\\d{10,17}_[0-9a-fA-F-]{36}\\.(?:jpg|png|gif)"
                    + "|support/user_\\d+/screenshots/"
                    + "\\d{13}_[0-9a-fA-F-]{36}\\.(?:jpg|png|gif)"
                    + "|official/global-challenges/[A-Za-z0-9._-]+\\.(?:jpg|png|gif)"
                    + ")$"
    );
    private static final Set<String> GOOGLE_AVATAR_HOSTS = Set.of(
            "lh3.googleusercontent.com",
            "lh4.googleusercontent.com",
            "lh5.googleusercontent.com",
            "lh6.googleusercontent.com"
    );

    @Value("${aws.s3.bucket.name}")
    private String bucketName;

    private final Object readUrlCacheLock = new Object();
    private final Map<String, CachedSignedUrl> readUrlCache = new LinkedHashMap<>(256, 0.75f, true);

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
                String readableUrl = toReadableMediaUrl(url);
                if (readableUrl != null && !readableUrl.isBlank()) {
                    readableUrls.add(readableUrl);
                }
            }
        }
        return readableUrls;
    }

    public String toReadableMediaUrl(String mediaUrl) {
        String objectName = extractS3ObjectName(mediaUrl);
        if (objectName == null || objectName.isBlank()) {
            return mediaUrl;
        }
        if (!SIGNABLE_PRIVATE_OBJECT_KEY.matcher(objectName).matches()) {
            logger.warn("Refused to sign media object outside approved namespaces");
            return null;
        }

        try {
            String cacheKey = cacheKey(objectName);
            Instant now = Instant.now();
            CachedSignedUrl cached;
            synchronized (readUrlCacheLock) {
                cached = readUrlCache.get(cacheKey);
            }
            if (cached != null && cached.refreshAfter().isAfter(now)) {
                return cached.url();
            }

            Instant computeNow = Instant.now();
            CachedSignedUrl generated = new CachedSignedUrl(
                    GetSignedUrlForS3.generateV4GetObjectSignedUrl(
                            bucketName,
                            objectName,
                            READ_SIGNED_URL_TTL_SECONDS
                    ),
                    computeNow.plusSeconds(READ_SIGNED_URL_TTL_SECONDS - READ_SIGNED_URL_CACHE_SAFETY_SECONDS)
            );
            CachedSignedUrl resolved;
            synchronized (readUrlCacheLock) {
                CachedSignedUrl concurrent = readUrlCache.get(cacheKey);
                resolved = concurrent != null && concurrent.refreshAfter().isAfter(Instant.now())
                        ? concurrent
                        : generated;
                readUrlCache.put(cacheKey, resolved);
                evictReadUrlCacheEntries();
            }
            return resolved.url();
        } catch (Exception e) {
            // Never expose the unsigned URL for a private S3 object. Doing so
            // only causes clients to retry a URL that S3 must reject.
            logger.warn("Failed to sign readable media URL: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    public String toReadableProfilePhoto(Long ownerUserId, String profilePhotoValue) {
        if (ownerUserId == null || profilePhotoValue == null || profilePhotoValue.isBlank()) {
            return null;
        }

        String trimmed = profilePhotoValue.trim();
        if (trimmed.startsWith("https://")) {
            try {
                URI uri = URI.create(trimmed);
                String host = uri.getHost();
                if (host != null
                        && GOOGLE_AVATAR_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
                    return trimmed;
                }
            } catch (Exception ignored) {
                return null;
            }
        }

        String objectKey = extractS3ObjectName(trimmed);
        Matcher matcher = objectKey == null
                ? PROFILE_OBJECT_KEY.matcher("")
                : PROFILE_OBJECT_KEY.matcher(objectKey);
        if (!matcher.matches() || !Long.toString(ownerUserId).equals(matcher.group(1))) {
            return null;
        }
        return toReadableMediaUrl(objectKey);
    }

    public String createUploadUrl(
            String objectName,
            long expiresInSeconds,
            GeneratePresignedUrlsRequest.ContentType contentType,
            Long contentLength) {
        return GetSignedUrlForS3.generatePutObjectSignedUrl(
                bucketName,
                objectName,
                expiresInSeconds,
                contentType,
                contentLength
        );
    }

    private void evictReadUrlCacheEntries() {
        Iterator<Map.Entry<String, CachedSignedUrl>> iterator = readUrlCache.entrySet().iterator();
        while (readUrlCache.size() > MAX_READ_URL_CACHE_ENTRIES && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    public void uploadObject(String objectName, byte[] content, String contentType) {
        GetSignedUrlForS3.uploadObject(bucketName, objectName, content, contentType);
    }

    public boolean objectExists(String objectName) {
        return GetSignedUrlForS3.objectExists(bucketName, objectName);
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
            logger.debug("Could not parse media URL as an S3 object");
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
