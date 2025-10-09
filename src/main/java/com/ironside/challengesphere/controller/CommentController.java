//package com.ironside.challengesphere.controller;
//
//import com.ironside.challengesphere.exception.ChallengeSphereException;
//import com.ironside.challengesphere.service.CommentService;
//import lombok.RequiredArgsConstructor;
//import org.springframework.data.domain.Page;
//import org.springframework.data.domain.PageRequest;
//import org.springframework.data.domain.Pageable;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//
//@RestController
//@RequestMapping("/api/v1/comments")
//@RequiredArgsConstructor
//public class CommentController {
//    private final CommentService commentService;
//
//    @PostMapping("")
//    public ResponseEntity<Comment> addComment(@RequestParam Long mediaId,
//                                              @RequestParam Long userId,
//                                              @RequestParam String content) throws ChallengeSphereException {
//        return ResponseEntity.ok(commentService.addComment(mediaId, userId, content));
//    }
//
//    @GetMapping("/media/{mediaId}")
//    public ResponseEntity<Page<Comment>> getCommentsForMedia(@PathVariable Long mediaId,
//                                                            @RequestParam(defaultValue = "0") int page,
//                                                            @RequestParam(defaultValue = "10") int size) {
//        Pageable pageable = PageRequest.of(page, size);
//        Page<Comment> comments = commentService.getCommentsForMedia(mediaId, pageable);
//        return ResponseEntity.ok(comments);
//    }
//
//    @DeleteMapping("/{commentId}")
//    public ResponseEntity<Void> deleteComment(@PathVariable Long commentId,
//                                              @RequestParam Long userId) throws ChallengeSphereException {
//        commentService.softDeleteComment(commentId, userId);
//        return ResponseEntity.ok().build();
//    }
//}
