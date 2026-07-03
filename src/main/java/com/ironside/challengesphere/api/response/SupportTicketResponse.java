package com.ironside.challengesphere.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SupportTicketResponse {
    private boolean success;
    private String message;
}
