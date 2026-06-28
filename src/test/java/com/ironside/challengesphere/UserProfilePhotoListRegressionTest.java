package com.ironside.challengesphere;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ironside.challengesphere.service.impl.MediaUrlService;

class UserProfilePhotoListRegressionTest {

    @Test
    void userListResponsesPrepareReadableProfilePhotoUrls() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/com/ironside/challengesphere/service/impl/UserServiceImpl.java"
        ));

        assertTrue(
                service.contains("visibleUsers.add(withReadableProfilePicUrl(restrictedProfile(user)))"),
                "search and suggested user lists must return signed/readable profile photo URLs"
        );
        assertTrue(
                service.contains("withReadableProfilePicUrls(followers)") &&
                        service.contains("withReadableProfilePicUrls(following)"),
                "followers/following lists must return signed/readable profile photo URLs"
        );
        assertTrue(
                service.contains("private List<User> withReadableProfilePicUrls"),
                "UserServiceImpl should have a shared helper for user-list profile photo signing"
        );
        assertTrue(
                service.contains("return withReadableProfilePicUrl(updatedUser)"),
                "profile updates should return the newly readable profile photo immediately"
        );
    }

    @Test
    void challengeLeaderboardAndNotificationResponsesPrepareReadableProfilePhotos() throws Exception {
        String challengeService = Files.readString(Path.of(
                "src/main/java/com/ironside/challengesphere/service/impl/ChallengeServiceImpl.java"
        ));
        String leaderboardService = Files.readString(Path.of(
                "src/main/java/com/ironside/challengesphere/service/impl/LeaderboardServiceImpl.java"
        ));
        String notificationService = Files.readString(Path.of(
                "src/main/java/com/ironside/challengesphere/service/impl/NotificationServiceImpl.java"
        ));

        assertTrue(
                challengeService.contains("withReadableParticipantProfilePhotos(users)"),
                "challenge participants should receive readable profile photo URLs"
        );
        assertTrue(
                countOccurrences(leaderboardService, "readableProfilePic(user.getProfilePicUrl())") >= 2,
                "database and cache leaderboard paths should both return readable profile photos"
        );
        assertTrue(
                notificationService.contains("readableProfilePic(actor.getProfilePicUrl())")
                        && notificationService.contains("readableProfilePic(recipient.getProfilePicUrl())"),
                "notification actor and target profile photos should be readable"
        );
    }

    @Test
    void externalProfilePhotoUrlPassesThroughUnchanged() {
        MediaUrlService mediaUrlService = new MediaUrlService();
        ReflectionTestUtils.setField(mediaUrlService, "bucketName", "challengesphere-media");
        String externalUrl = "https://lh3.googleusercontent.com/a/example-avatar";

        assertEquals(externalUrl, mediaUrlService.toReadableMediaUrl(externalUrl));
    }

    private int countOccurrences(String source, String value) {
        return source.split(java.util.regex.Pattern.quote(value), -1).length - 1;
    }
}
