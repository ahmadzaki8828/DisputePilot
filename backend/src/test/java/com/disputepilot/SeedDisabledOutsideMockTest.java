package com.disputepilot;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"dp.admin-token=test-token", "dp.paypal.mode=sandbox"})
@AutoConfigureMockMvc
class SeedDisabledOutsideMockTest {

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    @Autowired MockMvc mvc;

    @Test
    void seedIsRefusedInSandboxMode() throws Exception {
        mvc.perform(post("/api/dev/seed").header("X-Admin-Token", "test-token")).andExpect(status().isForbidden());
    }
}
