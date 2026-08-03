package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdatePeerReminderRequest {
    @NotNull(message = "Reminder preference is required")
    private Boolean enabled;
}
