package com.ironside.challengesphere.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.sql.Timestamp;

@Data
@AllArgsConstructor
public class FeatureRequestResponse {
    private Long id;
    private String status;
    private Timestamp createdAt;
    private String message;
}
