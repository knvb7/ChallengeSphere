package com.ironside.challengesphere;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmissionDeleteRegressionTest {

    @Test
    void userChallengeSubmissionQueriesExcludeSoftDeletedRows() throws Exception {
        String repository = Files.readString(Path.of(
                "src/main/java/com/ironside/challengesphere/repository/SubmissionRepository.java"
        ));
        String service = Files.readString(Path.of(
                "src/main/java/com/ironside/challengesphere/service/impl/SubmissionServiceImpl.java"
        ));

        assertTrue(
                service.contains("submission.setActive(false);"),
                "Deleting a submission should keep using the soft-delete active flag"
        );
        assertTrue(
                repository.contains("WHERE user_id = :userId AND challenge_id = :challengeId AND active = true ORDER BY day_number ASC"),
                "User challenge timelines must not return inactive submissions after delete"
        );
        assertTrue(
                repository.contains("countQuery = \"SELECT COUNT(*) FROM challengesphere.submissions WHERE user_id = :userId AND challenge_id = :challengeId AND active = true\""),
                "Paginated user challenge timelines must count only active submissions"
        );
    }
}
