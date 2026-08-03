package com.ironside.challengesphere.scheduler;

import com.ironside.challengesphere.service.impl.RedisCacheServiceImpl;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OfficialGlobalCohortScheduler {

    private static final Logger logger = LogManager.getLogger(OfficialGlobalCohortScheduler.class);

    private final JdbcTemplate jdbcTemplate;
    private final RedisCacheServiceImpl cacheService;

    @Value("${app.global-cohorts.enabled:true}")
    private boolean enabled;

    public OfficialGlobalCohortScheduler(
            JdbcTemplate jdbcTemplate,
            RedisCacheServiceImpl cacheService) {
        this.jdbcTemplate = jdbcTemplate;
        this.cacheService = cacheService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void publishAtStartup() {
        publishCurrentCohorts();
    }

    @Scheduled(
            cron = "${app.global-cohorts.cron:0 10 0 * * *}",
            zone = "${app.global-cohorts.zone:UTC}"
    )
    public void publishCurrentCohorts() {
        if (!enabled) {
            logger.debug("Official global cohorts disabled, skipping");
            return;
        }

        try {
            Integer created = jdbcTemplate.queryForObject(
                    "SELECT challengesphere.ensure_rolling_global_cohorts(" +
                            "(CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::DATE)",
                    Integer.class
            );
            cacheService.invalidateRecentAndTrendingChallenges();
            cacheService.invalidateHashtagChallenges();
            if (created != null && created > 0) {
                logger.info("Published {} official weekly global cohort(s)", created);
            }
        } catch (Exception exception) {
            // Cohort publication should never prevent the API from starting. The next daily
            // run is idempotent and will safely retry after a transient database issue.
            logger.error(
                    "Unable to publish official global cohorts: {}",
                    exception.getClass().getSimpleName()
            );
        }
    }
}
