package com.ironside.challengesphere.integration.strava;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StravaDetailedActivity {
    private Long id;
    private String name;
    private String type;

    @JsonProperty("sport_type")
    private String sportType;

    private Double distance;

    @JsonProperty("moving_time")
    private Integer movingTime;

    @JsonProperty("elapsed_time")
    private Integer elapsedTime;

    @JsonProperty("start_date")
    private String startDate;

    @JsonProperty("external_id")
    private String externalId;

    private Double kilojoules;

    @JsonProperty("average_speed")
    private Double averageSpeed;

    @JsonProperty("max_speed")
    private Double maxSpeed;
}
