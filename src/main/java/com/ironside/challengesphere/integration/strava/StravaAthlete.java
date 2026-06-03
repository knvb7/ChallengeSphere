package com.ironside.challengesphere.integration.strava;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StravaAthlete {
    private Long id;
    private String username;
    private String firstname;
    private String lastname;
    private String city;
    private String state;
    private String country;
    private String sex;
    private String profile;

    @JsonProperty("profile_medium")
    private String profileMedium;
}
