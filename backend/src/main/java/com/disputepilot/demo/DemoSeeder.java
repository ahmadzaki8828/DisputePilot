package com.disputepilot.demo;

import com.disputepilot.api.DisputeRepository;
import com.disputepilot.common.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * {@code DP_DEMO_SEED_ON_START=true}: loads the starter disputes into a database that has none, so a clean
 * {@code docker compose up} opens on a populated inbox. Never overwrites an existing demo.
 */
@Component
public class DemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private final AppProperties props;
    private final DisputeRepository disputes;
    private final DemoService demo;

    public DemoSeeder(AppProperties props, DisputeRepository disputes, DemoService demo) {
        this.props = props;
        this.disputes = disputes;
        this.demo = demo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.demo().seedOnStart()) {
            return;
        }
        if (disputes.countSimulated() > 0) {
            log.info("Starter disputes already present; not seeding");
            return;
        }
        try {
            demo.seedStarters();
        } catch (RuntimeException e) {
            log.warn("Seeding the starter disputes failed: {}", e.getMessage());
        }
    }
}
