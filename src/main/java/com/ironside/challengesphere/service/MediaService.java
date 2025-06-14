package com.ironside.challengesphere.service;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Media;
import org.springframework.web.multipart.MultipartFile;

public interface MediaService {
    Media uploadMedia(MultipartFile file, Long uploaderId, Long challengeId) throws ChallengeSphereException;
    String generateSignedUploadUrl(String filename, String contentType) throws ChallengeSphereException;
    Media registerMedia(Media media, Long challengeId) throws ChallengeSphereException;
}
