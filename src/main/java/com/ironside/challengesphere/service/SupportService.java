package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.request.CreateSupportTicketRequest;
import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.api.response.PresignedUrlsResponse;
import com.ironside.challengesphere.api.response.SupportTicketResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;

public interface SupportService {

    PresignedUrlsResponse generatePresignedUrls(
            Long userId,
            GeneratePresignedUrlsRequest request
    ) throws ChallengeSphereException;

    SupportTicketResponse createTicket(
            Long userId,
            CreateSupportTicketRequest request
    ) throws ChallengeSphereException;
}
