package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RemoveParticipantRequest {
    @NotBlank(message = "A removal reason is required")
    @Size(max = 500, message = "A removal reason must not exceed 500 characters")
    private String reason;
}
