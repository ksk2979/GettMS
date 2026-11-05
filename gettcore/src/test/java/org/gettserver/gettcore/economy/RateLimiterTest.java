package org.gettserver.gettcore.economy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RateLimiterTest {
    private final UUID id = UUID.randomUUID();

    @Test
    void firstCallPasses() {
        assertTrue(new RateLimiter(1000).tryAcquire(id, 0));
    }

    @Test
    void secondCallWithinWindowFails() {
        RateLimiter r = new RateLimiter(1000);
        r.tryAcquire(id, 0);
        assertFalse(r.tryAcquire(id, 500));
    }

    @Test
    void passesAfterWindow() {
        RateLimiter r = new RateLimiter(1000);
        r.tryAcquire(id, 0);
        assertTrue(r.tryAcquire(id, 1000));
    }
}
