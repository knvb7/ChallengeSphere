package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.CreateSupportTicketRequest;
import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.api.response.PresignedUrlsResponse;
import com.ironside.challengesphere.api.response.SupportTicketResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.service.SupportService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SupportServiceImpl implements SupportService {

    private static final Logger logger = LogManager.getLogger(SupportServiceImpl.class);

    @Override
    public PresignedUrlsResponse generatePresignedUrls(
            Long userId,
            GeneratePresignedUrlsRequest request
    ) throws ChallengeSphereException {
        throw supportGone();
    }

    @Override
    public SupportTicketResponse createTicket(
            Long userId,
            CreateSupportTicketRequest request
    ) throws ChallengeSphereException {
        throw supportGone();
    }

    private ChallengeSphereException supportGone() {
        return new ChallengeSphereException(
                "Support tickets are unavailable. Please use feature requests.",
                HttpStatus.GONE,
                logger
        );
    }
}
