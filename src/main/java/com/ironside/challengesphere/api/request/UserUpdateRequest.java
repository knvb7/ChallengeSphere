package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserUpdateRequest {
    @Size(min = 2, max = 100, message = "Full name must be between 2 and 100 characters")
    private String fullName;
    
    @Size(max = 100, message = "Location cannot exceed 100 characters")
    private String location;
    
    @Size(max = 500, message = "Bio cannot exceed 500 characters")
    private String bio;
    
    @Size(min = 2, max = 10, message = "Language preference must be between 2 and 10 characters")
    private String languagePreference;
    
    @Size(max = 50, message = "Preferred tone cannot exceed 50 characters")
    private String preferredTone;
    
    private String profilePicUrl;
}