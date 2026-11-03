package com.disputepilot.api;

import com.disputepilot.common.ApiException;
import com.disputepilot.common.AppProperties;
import com.disputepilot.paypal.DisputeQuery;
import com.disputepilot.paypal.PayPalDispute;
import com.disputepilot.paypal.SimulatedPayPalDisputesClient;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dev")
public class DevController {

    private final AppProperties props;
    private final SimulatedPayPalDisputesClient simulated;
    private final DisputeRepository repo;

    public DevController(AppProperties props, SimulatedPayPalDisputesClient simulated, DisputeRepository repo) {
        this.props = props;
        this.simulated = simulated;
        this.repo = repo;
    }

    /** Loads the fixture disputes (via the simulated client) into the disputes table. Mock mode only. */
    @PostMapping("/seed")
    public Map<String, Object> seed() {
        if (props.paypal().mode() != AppProperties.PaypalMode.MOCK) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Seeding disabled",
                    "POST /api/dev/seed is only available when PAYPAL_MODE=mock.");
        }
        int count = 0;
        for (PayPalDispute d : simulated.listDisputes(DisputeQuery.all()).items()) {
            repo.upsert(d);
            count++;
        }
        return Map.of("seeded", count);
    }
}
