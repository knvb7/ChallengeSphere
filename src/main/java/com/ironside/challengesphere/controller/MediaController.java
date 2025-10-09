//package com.ironside.challengesphere.controller;
//
//import com.ironside.challengesphere.exception.ChallengeSphereException;
//import com.ironside.challengesphere.service.MediaService;
//import lombok.RequiredArgsConstructor;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//import org.springframework.web.multipart.MultipartFile;
//
//@RestController
//@RequestMapping("/api/v1/media")
//@RequiredArgsConstructor
//public class MediaController {
//    private final MediaService mediaService;
//
//    @PostMapping("/upload")
//    public ResponseEntity<Media> uploadMedia(@RequestParam("file") MultipartFile file,
//                                             @RequestParam("uploaderId") Long uploaderId,
//                                             @RequestParam("challengeId") Long challengeId) throws ChallengeSphereException {
//        Media media = mediaService.uploadMedia(file, uploaderId, challengeId);
//        return ResponseEntity.ok(media);
//    }
//
//    @PostMapping("/signed-url")
//    public ResponseEntity<String> generateSignedUrl(@RequestParam("filename") String filename,
//                                                    @RequestParam("contentType") String contentType) throws ChallengeSphereException {
//        String signedUrl = mediaService.generateSignedUploadUrl(filename, contentType);
//        return ResponseEntity.ok(signedUrl);
//    }
//
//    @PostMapping("/register")
//    public ResponseEntity<Media> registerMedia(@RequestBody Media media,
//                                               @RequestParam("challengeId") Long challengeId) throws ChallengeSphereException {
//        Media savedMedia = mediaService.registerMedia(media, challengeId);
//        return ResponseEntity.ok(savedMedia);
//    }
//}
