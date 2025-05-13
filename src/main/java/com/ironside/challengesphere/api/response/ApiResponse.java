package com.ironside.challengesphere.api.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.io.Serial;
import java.io.Serializable;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@AllArgsConstructor
@NoArgsConstructor
public class ApiResponse<T> implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private HttpStatus status = HttpStatus.OK;
    private T payload;
    private String responseMessage;
    private ApiError error;
    private HttpHeaders headers;

    @JsonIgnore
    public HttpHeaders getHeaders() {
        return headers;
    }

    public void setHeaders(HttpHeaders headers) {
        this.headers.addAll(headers);
    }
}
