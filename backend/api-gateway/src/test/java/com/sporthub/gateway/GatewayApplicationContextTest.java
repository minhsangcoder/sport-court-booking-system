package com.sporthub.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.data.redis.password=test-only")
class GatewayApplicationContextTest {

    @Test
    void applicationContextStarts() {
        // Context startup is the assertion.
    }
}
