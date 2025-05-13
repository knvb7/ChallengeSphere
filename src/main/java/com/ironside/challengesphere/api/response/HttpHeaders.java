package com.ironside.challengesphere.api.response;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

public class HttpHeaders extends org.springframework.http.HttpHeaders {
    @Override
    @JsonIgnore
    public void setDate(Instant date){
        super.setDate(date);
    }

    @Override
    @JsonIgnore
    public void setIfModifiedSince(Instant ifModifiedSince){
        super.setIfModifiedSince(ifModifiedSince);
    }

    @Override
    @JsonIgnore
    public void setExpires(Instant expires){
        super.setDate(expires);
    }

}
