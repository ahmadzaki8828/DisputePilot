package com.disputepilot.e2e;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.disputepilot.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Outside mock mode, /api/dev/** stays off unless DP_DEV_ENDPOINTS=true. */
@SpringBootTest(properties = {"dp.admin-token=test-token", "dp.paypal.mode=sandbox", "dp.dev-endpoints=false"})
@AutoConfigureMockMvc
class LinkTransactionDisabledTest {

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    @Autowired MockMvc mvc;

    @Test
    void linkIsForbiddenInSandboxModeWithoutTheDevFlag() throws Exception {
        mvc.perform(post("/api/dev/link-transaction").header("X-Admin-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellerTransactionId\":\"T-1\",\"orderId\":\"ORD-1042\"}"))
                .andExpect(status().isForbidden());
    }
}
