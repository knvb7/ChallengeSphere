package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class VerifyOtpRequest {

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^\\+?[0-9]{8,20}$", message = "Phone number must be a valid E.164 number")
    private String phone;

    @NotBlank(message = "Verification code is required")
    @Size(min = 4, max = 10, message = "Verification code must be 4-10 digits")
    private String code;
}
