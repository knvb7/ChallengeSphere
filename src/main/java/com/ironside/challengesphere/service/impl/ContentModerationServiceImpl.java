package com.ironside.challengesphere.service.impl;

import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.vision.v1.AnnotateImageRequest;
import com.google.cloud.vision.v1.AnnotateImageResponse;
import com.google.cloud.vision.v1.BatchAnnotateImagesResponse;
import com.google.cloud.vision.v1.Feature;
import com.google.cloud.vision.v1.Image;
import com.google.cloud.vision.v1.ImageAnnotatorClient;
import com.google.cloud.vision.v1.ImageAnnotatorSettings;
import com.google.cloud.vision.v1.ImageSource;
import com.google.cloud.vision.v1.Likelihood;
import com.google.cloud.vision.v1.SafeSearchAnnotation;
import com.ironside.challengesphere.service.ContentModerationService;
import com.ironside.challengesphere.util.Constants;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ContentModerationServiceImpl implements ContentModerationService {

    private static final Logger logger = LogManager.getLogger(ContentModerationServiceImpl.class);

    // Image suffixes Vision can analyze directly. Videos and other binaries are
    // skipped — those would need the separate Video Intelligence API.
    private static final Set<String> IMAGE_EXTS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic", ".heif"
    );

    @Value("${gcs.key.path}")
    private String keyPath;

    private volatile ImageAnnotatorClient client;

    @PostConstruct
    void init() {
        try {
            ImageAnnotatorSettings settings = ImageAnnotatorSettings.newBuilder()
                    .setCredentialsProvider(() -> ServiceAccountCredentials.fromStream(new FileInputStream(keyPath)))
                    .build();
            this.client = ImageAnnotatorClient.create(settings);
            logger.info("Vision SafeSearch client initialized");
        } catch (Exception e) {
            // If creds are missing at startup we don't want to crash the app —
            // scan() will log and short-circuit to CLEAN until ops fixes the key.
            logger.error("Failed to initialize Vision SafeSearch client — moderation will no-op until fixed", e);
        }
    }

    @PreDestroy
    void close() {
        if (client != null) {
            try { client.close(); } catch (Exception ignore) {}
        }
    }

    @Override
    public Result scan(List<String> mediaUrls) {
        if (mediaUrls == null || mediaUrls.isEmpty()) {
            return new Result(Verdict.CLEAN, List.of());
        }
        if (client == null) {
            logger.error("Vision client not initialized — skipping moderation for {} URLs", mediaUrls.size());
            return new Result(Verdict.CLEAN, List.of());
        }

        List<AnnotateImageRequest> requests = new ArrayList<>();
        List<String> scannableUrls = new ArrayList<>();
        for (String url : mediaUrls) {
            if (url == null || url.isBlank()) continue;
            if (!isImage(url)) {
                logger.debug("Skipping non-image URL from moderation: {}", url);
                continue;
            }
            String gcsUri = toGcsUri(url);
            ImageSource source = ImageSource.newBuilder().setImageUri(gcsUri).build();
            Image image = Image.newBuilder().setSource(source).build();
            requests.add(AnnotateImageRequest.newBuilder()
                    .addFeatures(Feature.newBuilder().setType(Feature.Type.SAFE_SEARCH_DETECTION).build())
                    .setImage(image)
                    .build());
            scannableUrls.add(url);
        }

        if (requests.isEmpty()) {
            return new Result(Verdict.CLEAN, List.of());
        }

        BatchAnnotateImagesResponse batch;
        try {
            batch = client.batchAnnotateImages(requests);
        } catch (Exception e) {
            // Fail-open: we log and treat as clean. Banning honest users on a
            // transient Vision outage would be worse than briefly letting
            // content through.
            logger.error("Vision SafeSearch batch call failed — allowing submission through", e);
            return new Result(Verdict.CLEAN, List.of());
        }

        List<Finding> findings = new ArrayList<>();
        boolean anyVeryLikely = false;
        boolean anyLikely = false;

        List<AnnotateImageResponse> responses = batch.getResponsesList();
        for (int i = 0; i < responses.size(); i++) {
            AnnotateImageResponse r = responses.get(i);
            String url = scannableUrls.get(i);
            if (r.hasError()) {
                logger.warn("Vision SafeSearch error on {}: {}", url, r.getError().getMessage());
                continue;
            }
            SafeSearchAnnotation s = r.getSafeSearchAnnotation();

            // Scan the three categories we care about. Medical / spoof are also
            // returned by SafeSearch but aren't grounds for a ban on this app.
            boolean[] hits = recordHit(findings, url, "ADULT", s.getAdult());
            anyVeryLikely |= hits[0];
            anyLikely |= hits[1];
            hits = recordHit(findings, url, "RACY", s.getRacy());
            anyVeryLikely |= hits[0];
            anyLikely |= hits[1];
            hits = recordHit(findings, url, "VIOLENCE", s.getViolence());
            anyVeryLikely |= hits[0];
            anyLikely |= hits[1];
        }

        Verdict verdict = anyVeryLikely ? Verdict.BLOCK_AND_BAN
                : anyLikely ? Verdict.FLAG_FOR_REVIEW
                : Verdict.CLEAN;
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

    // Normalize whatever the client stored in `mediaUrls` into the gs:// URI
    // Vision expects for private-bucket reads. We accept three shapes:
    //  - gs://bucket/object                          (pass through)
    //  - https://storage.googleapis.com/bucket/obj   (rewrite)
    //  - submissions/user_1/IMAGE/...jpg             (bare fileKey → prepend bucket)
    private static String toGcsUri(String url) {
        if (url.startsWith("gs://")) return url;
        final String httpsPrefix = "https://storage.googleapis.com/";
        if (url.startsWith(httpsPrefix)) {
            return "gs://" + url.substring(httpsPrefix.length());
        }
        // Strip any signed-URL query string before treating as fileKey.
        int q = url.indexOf('?');
        String path = q >= 0 ? url.substring(0, q) : url;
        if (path.startsWith("/")) path = path.substring(1);
        return "gs://" + Constants.GCSBUCKETFORSUBMISSIONS + "/" + path;
    }

    // [veryLikely, likely] — avoids returning an enum just for two bools.
    private static boolean[] recordHit(List<Finding> out, String url, String category, Likelihood l) {
        Finding e = new Finding(url, category, l.name());
        if (l == Likelihood.VERY_LIKELY) {
            out.add(e);
            return new boolean[]{true, false};
        }
        if (l == Likelihood.LIKELY) {
            out.add(e);
            return new boolean[]{false, true};
        }
        return new boolean[]{false, false};
    }
}
