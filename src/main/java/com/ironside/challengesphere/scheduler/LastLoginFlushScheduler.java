package com.ironside.challengesphere.scheduler;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.impl.RedisCacheServiceImpl;

/**
 * Hourly scheduler that batch-flushes dirty last_login_at timestamps from Redis → DB.
 *
 * Flow:
 *  1. On every getUser() call, stampLastLogin() updates the in-memory User + Redis cache
 *     and adds the userId to the Redis Set "dirty:last_login".
 *  2. Every hour this scheduler atomically drains that set (SPOP loop) and issues a
 *     single batch UPDATE ... WHERE id IN (...) to PostgreSQL.
 *
 * Benefits vs per-request DB writes:
 *  - De-duplication: if a user triggers 500 getUser() calls in an hour, only 1 DB write.
 *  - Batching: one SQL UPDATE for all dirty users instead of N individual ones.
 *  - Zero impact on API latency: stampLastLogin() never touches the DB.
 */
@Component
public class LastLoginFlushScheduler {

    private static final Logger logger = LogManager.getLogger(LastLoginFlushScheduler.class);

    @Autowired
    private RedisCacheServiceImpl cacheService;

    @Autowired
    private UserRepository userRepository;

    /**
     * Runs every hour at minute :00.
     * Drains the dirty-login set from Redis and batch-updates the DB.
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void flushDirtyLogins() {
        logger.info("LastLoginFlushScheduler — starting hourly flush");
        long start = System.currentTimeMillis();

        try {
            List<Long> dirtyIds = cacheService.drainDirtyLogins();

            if (dirtyIds.isEmpty()) {
                logger.info("No dirty logins to flush");
                return;
            }

            int updated = userRepository.batchUpdateLastLoginAt(dirtyIds);

            long elapsed = System.currentTimeMillis() - start;
            logger.info("Flushed last_login_at for {} users ({} rows updated) in {}ms",
                    dirtyIds.size(), updated, elapsed);

        } catch (Exception e) {
            logger.error("LastLoginFlushScheduler failed: {}", e.getMessage(), e);
        }
    }
}

