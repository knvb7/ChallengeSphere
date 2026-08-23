package com.ironside.challengesphere.api.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class InviteChallengeUsersRequest {
    @NotEmpty(message = "Select at least one user to invite")
    @Size(max = 20, message = "A challenge can invite at most 20 users at once")
    @Valid
    private List<@NotBlank String> invitedUserIds;
}
