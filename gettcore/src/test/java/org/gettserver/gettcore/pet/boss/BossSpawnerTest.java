package org.gettserver.gettcore.pet.boss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BossSpawnerTest {
    private static final long MINUTE = 20L * 60L;

    @Test
    void fullPointDoesNotSpawn() {
        assertFalse(BossSpawner.shouldSpawn(1, 1, -1, 100 * MINUTE, 30, true));
        assertFalse(BossSpawner.shouldSpawn(3, 2, -1, 100 * MINUTE, 30, true));
    }

    @Test
    void waitsForTheRespawnInterval() {
        assertFalse(BossSpawner.shouldSpawn(0, 1, 0, 29 * MINUTE, 30, true));
        assertFalse(BossSpawner.shouldSpawn(0, 1, 0, 30 * MINUTE - 1, 30, true));
    }

    @Test
    void spawnsOnceTheIntervalHasPassed() {
        assertTrue(BossSpawner.shouldSpawn(0, 1, 0, 30 * MINUTE, 30, true));
        assertTrue(BossSpawner.shouldSpawn(1, 2, 5 * MINUTE, 40 * MINUTE, 30, true));
    }

    @Test
    void noPlayerNearbyMeansNoSpawn() {
        assertFalse(BossSpawner.shouldSpawn(0, 1, -1, 100 * MINUTE, 30, false));
        assertFalse(BossSpawner.shouldSpawn(0, 1, 0, 100 * MINUTE, 30, false));
    }

    @Test
    void neverGoneCountsAsElapsed() {
        assertTrue(BossSpawner.shouldSpawn(0, 1, -1, 0, 30, true));
    }

    @Test
    void zeroMaxCountNeverSpawns() {
        assertFalse(BossSpawner.shouldSpawn(0, 0, -1, 100 * MINUTE, 30, true));
    }
}
