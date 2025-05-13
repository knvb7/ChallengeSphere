package com.ironside.challengesphere.api.response;

import org.springframework.http.ResponseEntity;

public class ResponseHandler {
    @SuppressWarnings("rawtypes")
    public static ResponseEntity<ApiResponse> generateResponse(ApiResponse apiResponse){
        return new ResponseEntity<>(apiResponse,apiResponse.getHeaders(), apiResponse.getStatus());
    }
}
