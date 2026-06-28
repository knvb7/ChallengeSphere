package com.ironside.challengesphere;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmissionProofPayloadRegressionTest {

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void submissionsDoNotPersistOrReturnCodingProofPayloads() throws Exception {
        String request = read("src/main/java/com/ironside/challengesphere/api/request/CreateSubmissionRequest.java");
        String model = read("src/main/java/com/ironside/challengesphere/model/Submission.java");
        String response = read("src/main/java/com/ironside/challengesphere/api/response/SubmissionResponse.java");
        String feedItem = read("src/main/java/com/ironside/challengesphere/api/response/FeedItemResponse.java");
        String service = read("src/main/java/com/ironside/challengesphere/service/impl/SubmissionServiceImpl.java");

        assertFalse(request.contains("proofPayload"),
                "CreateSubmissionRequest must not accept coding proof payload JSON from the app");
        assertFalse(model.contains("proofPayload") || model.contains("proof_payload"),
                "Submission must not map or persist coding proof payloads");
        assertFalse(response.contains("proofPayload"),
                "SubmissionResponse must not return stored coding proof payloads");
        assertFalse(feedItem.contains("proofPayload"),
                "FeedItemResponse must not return stored coding proof payloads");
        assertFalse(service.contains("setProofPayload")
                        || service.contains("getProofPayload")
                        || service.contains("toProofPayloadJson"),
                "SubmissionServiceImpl must not store or serialize coding proof payloads");
    }

    @Test
    void proofPayloadRemovalMigrationTargetsTheProductionSchema() throws Exception {
        String migration = read("src/main/resources/db/migration/V23__drop_submission_proof_payload.sql");

        assertTrue(
                migration.contains("ALTER TABLE challengesphere.submissions"),
                "V23 must work even when the database search path is public"
        );
    }
}
