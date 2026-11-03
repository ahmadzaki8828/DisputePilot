package com.disputepilot.sync;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring scheduling for the outbox worker and the reconciliation poller, and binds
 * {@link SyncProperties}. Whether the scheduled methods do anything is controlled by
 * {@code dp.sync.scheduling-enabled}, so tests can drive both directly.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(SyncProperties.class)
public class SyncConfig {
}
