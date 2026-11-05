package org.gettserver.gettcore.perm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PermsTest {
    @Test
    void fallbackUsesOpLevel() {
        assertTrue(Perms.fallback(false, Perms.ECONOMY_CHECK));
        assertFalse(Perms.fallback(false, Perms.ECONOMY_ADMIN));
        assertTrue(Perms.fallback(true, Perms.ECONOMY_ADMIN));
    }
}
