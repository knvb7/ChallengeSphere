package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.request.ContentReportRequest;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.security.SecurityAccess;
import com.ironside.challengesphere.security.UserPrincipal;
import com.ironside.challengesphere.service.impl.ContentReportService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/content-reports")
public class ContentReportController {

    private final ContentReportService contentReportService;

    public ContentReportController(ContentReportService contentReportService) {
        this.contentReportService = contentReportService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> report(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ContentReportRequest request) throws ChallengeSphereException {
        Long reporterUserId = SecurityAccess.requireUserId(principal);
        return ResponseEntity.ok(contentReportService.report(reporterUserId, request));
    }
}
