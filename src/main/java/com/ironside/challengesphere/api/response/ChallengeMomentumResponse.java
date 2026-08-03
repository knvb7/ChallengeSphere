package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.service.impl.ChallengeMomentumPolicy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChallengeMomentumResponse {
    private ChallengeMomentumPolicy.State state;
    private int totalDays;
    private int completedDays;
    private int remainingRequiredCheckIns;
    private int remainingAvailableCheckIns;
    private boolean submittedToday;

    public static ChallengeMomentumResponse from(ChallengeMomentumPolicy.Result result) {
        if (result == null) {
            return null;
        }
        return ChallengeMomentumResponse.builder()
                .state(result.state())
                .totalDays(result.totalDays())
                .completedDays(result.completedDays())
                .remainingRequiredCheckIns(result.remainingRequiredCheckIns())
                .remainingAvailableCheckIns(result.remainingAvailableCheckIns())
                .submittedToday(result.submittedToday())
                .build();
    }
}
