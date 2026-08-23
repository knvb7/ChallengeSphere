package com.ironside.challengesphere.scheduler;

import com.ironside.challengesphere.persistence.dynamodb.operations.DynamoOperationalStore;
import com.ironside.challengesphere.persistence.dynamodb.operations.DynamoOperationalStore.RetentionPurgeBatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AccountDeletionRetentionScheduler {

    private static final Logger logger = LogManager.getLogger(AccountDeletionRetentionScheduler.class);
    private static final int BATCH_SIZE = 100;
    private static final int MAX_BATCHES_PER_RUN = 10;

    private final DynamoOperationalStore operationalStore;

    public AccountDeletionRetentionScheduler(DynamoOperationalStore operationalStore) {
        this.operationalStore = operationalStore;
    }

    @Scheduled(cron = "${app.account-deletion.identity-cleanup-cron:0 20 3 * * *}", zone = "UTC")
    public void purgeExpiredIdentityHashes() {
        try {
            int purged = 0;
            for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
                RetentionPurgeBatch current = operationalStore
                        .purgeExpiredAccountDeletionIdentityHashesBatch(BATCH_SIZE);
                purged += current.purgedItems();
                if (current.examinedItems() < BATCH_SIZE) {
                    break;
                }
            }
            if (purged > 0) {
                logger.info("Purged pseudonymous identity fingerprints from {} deletion record(s)", purged);
            }
        } catch (Exception exception) {
            logger.error(
                    "Unable to purge expired account-deletion identity fingerprints: {}",
                    exception.getClass().getSimpleName()
            );
        }
    }
}
