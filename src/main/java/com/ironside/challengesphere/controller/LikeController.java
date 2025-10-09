//package com.ironside.challengesphere.controller;
//
//import com.ironside.challengesphere.exception.ChallengeSphereException;
//import com.ironside.challengesphere.service.LikeService;
//import lombok.RequiredArgsConstructor;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//
//import java.util.List;
//
//@RestController
//@RequestMapping("/api/v1/likes")
//@RequiredArgsConstructor
//public class LikeController {
//    private final LikeService likeService;
//
//    @PostMapping("/toggle")
//    public ResponseEntity<Boolean> toggleLike(@RequestParam Long mediaId, @RequestParam Long userId) throws ChallengeSphereException {
//        boolean isLiked = likeService.toggleLike(mediaId, userId);
//        return ResponseEntity.ok(isLiked);
//    }
//
//    @GetMapping("/media/{mediaId}")
//    public ResponseEntity<List<Like>> getLikesForMedia(@PathVariable Long mediaId) throws ChallengeSphereException {
//        return ResponseEntity.ok(likeService.getLikesForMedia(mediaId));
//    }
//
//    @GetMapping("/media/{mediaId}/user/{userId}")
//    public ResponseEntity<Boolean> hasUserLiked(@PathVariable Long mediaId, @PathVariable Long userId) throws ChallengeSphereException {
//        return ResponseEntity.ok(likeService.hasUserLiked(mediaId, userId));
//    }
//}
