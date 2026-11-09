package org.gettserver.gettcore.hunting;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import org.gettserver.gettcore.hunting.HuntingConfig.Zone;
import org.junit.jupiter.api.Test;

class HuntingRulesTest {
    private static final Zone Z = new Zone("숲", Level.OVERWORLD, new BlockPos(0, 60, 0), new BlockPos(20, 80, 20),
            10, 10, List.of());

    @Test
    void containsIsInclusiveAndBoundaryOutsideIsNot() {
        assertTrue(HuntingRules.contains(Z, Level.OVERWORLD, new BlockPos(0, 60, 0)));
        assertTrue(HuntingRules.contains(Z, Level.OVERWORLD, new BlockPos(20, 80, 20)));
        assertTrue(HuntingRules.contains(Z, Level.OVERWORLD, new BlockPos(10, 70, 10)));
        assertFalse(HuntingRules.contains(Z, Level.OVERWORLD, new BlockPos(-1, 70, 10)));
        assertFalse(HuntingRules.contains(Z, Level.OVERWORLD, new BlockPos(21, 70, 10)));
        assertFalse(HuntingRules.contains(Z, Level.OVERWORLD, new BlockPos(10, 59, 10)));
        assertFalse(HuntingRules.contains(Z, Level.OVERWORLD, new BlockPos(10, 81, 10)));
        assertFalse(HuntingRules.contains(Z, Level.OVERWORLD, new BlockPos(10, 70, -1)));
        assertFalse(HuntingRules.contains(Z, Level.OVERWORLD, new BlockPos(10, 70, 21)));
        assertFalse(HuntingRules.contains(Z, Level.NETHER, new BlockPos(10, 70, 10)));
    }

    @Test
    void blocksOnlyNaturalSpawnsInsideZones() {
        HuntingConfig cfg = new HuntingConfig(List.of(Z));
        BlockPos in = new BlockPos(5, 65, 5);
        BlockPos out = new BlockPos(21, 65, 5);
        assertTrue(HuntingRules.blocksSpawn(cfg, Level.OVERWORLD, in, MobSpawnType.NATURAL, false));
        assertTrue(HuntingRules.blocksSpawn(cfg, Level.OVERWORLD, in, MobSpawnType.CHUNK_GENERATION, false));
        assertFalse(HuntingRules.blocksSpawn(cfg, Level.OVERWORLD, out, MobSpawnType.NATURAL, false));
        assertFalse(HuntingRules.blocksSpawn(cfg, Level.NETHER, in, MobSpawnType.NATURAL, false));
        for (MobSpawnType t : List.of(MobSpawnType.SPAWNER, MobSpawnType.SPAWN_EGG, MobSpawnType.COMMAND,
                MobSpawnType.BREEDING, MobSpawnType.EVENT, MobSpawnType.TRIAL_SPAWNER, MobSpawnType.CONVERSION)) {
            assertFalse(HuntingRules.blocksSpawn(cfg, Level.OVERWORLD, in, t, false), t.name());
        }
        // Our own variants are never blocked.
        assertFalse(HuntingRules.blocksSpawn(cfg, Level.OVERWORLD, in, MobSpawnType.NATURAL, true));
    }

    @Test
    void nearbyIsZonePlus32SameDimensionOnly() {
        assertTrue(HuntingRules.nearby(Z, Level.OVERWORLD, 10, 70, 10));
        assertTrue(HuntingRules.nearby(Z, Level.OVERWORLD, -32, 70, 10));
        assertTrue(HuntingRules.nearby(Z, Level.OVERWORLD, 52.9, 70, 10));
        assertFalse(HuntingRules.nearby(Z, Level.OVERWORLD, -32.1, 70, 10));
        assertFalse(HuntingRules.nearby(Z, Level.OVERWORLD, 53.1, 70, 10));
        assertFalse(HuntingRules.nearby(Z, Level.OVERWORLD, 10, 130, 10));
        assertFalse(HuntingRules.nearby(Z, Level.NETHER, 10, 70, 10));
    }

    @Test
    void pickEntryByWeight() {
        assertEquals(OptionalInt.of(0), HuntingRules.pickEntry(List.of(1, 3), 0.0));
        assertEquals(OptionalInt.of(0), HuntingRules.pickEntry(List.of(1, 3), 0.24));
        assertEquals(OptionalInt.of(1), HuntingRules.pickEntry(List.of(1, 3), 0.25));
        assertEquals(OptionalInt.of(1), HuntingRules.pickEntry(List.of(1, 3), 0.999));
        assertEquals(OptionalInt.of(2), HuntingRules.pickEntry(List.of(0, 0, 5), 0.5));
        assertEquals(OptionalInt.empty(), HuntingRules.pickEntry(List.of(), 0.5));
        assertEquals(OptionalInt.empty(), HuntingRules.pickEntry(List.of(0, 0), 0.5));
    }

    @Test
    void refillOnlyWithPlayersBelowMaxAfterInterval() {
        assertTrue(HuntingRules.shouldRefill(3, 10, true, 10, 10));
        assertFalse(HuntingRules.shouldRefill(3, 10, false, 100, 10));
        assertFalse(HuntingRules.shouldRefill(10, 10, true, 100, 10));
        assertFalse(HuntingRules.shouldRefill(0, 0, true, 100, 10));
        assertFalse(HuntingRules.shouldRefill(3, 10, true, 9.9, 10));
    }

    @Test
    void strayedFarOutside() {
        assertFalse(HuntingRules.strayed(Z, 10, 70, 10));
        assertFalse(HuntingRules.strayed(Z, -64, 70, 10));
        assertFalse(HuntingRules.strayed(Z, 84.9, 70, 10));
        assertTrue(HuntingRules.strayed(Z, -64.1, 70, 10));
        assertTrue(HuntingRules.strayed(Z, 10, 70, 85.1));
        assertTrue(HuntingRules.strayed(Z, 10, -10, 10));
    }

    @Test
    void damageLineNumbers() {
        assertEquals("12.5", HuntingRules.number(12.5));
        assertEquals("100", HuntingRules.number(100.0));
        assertEquals("87.5", HuntingRules.number(87.5));
        assertEquals("0.3", HuntingRules.number(0.333));
        assertEquals("0", HuntingRules.number(-2));
    }

    @Test
    void zonesAreSpreadOverTheIntervalOncePerWindow() {
        for (String id : List.of("a", "b", "zone_with_long_name", "숲")) {
            int due = 0;
            for (long t = 1000; t < 1020; t++) if (HuntingRules.dueThisTick(id, t, 20)) due++;
            assertEquals(1, due, id);
        }
        int[] perTick = new int[20];
        for (int i = 0; i < 40; i++) {
            for (int t = 0; t < 20; t++) if (HuntingRules.dueThisTick("zone" + i, t, 20)) perTick[t]++;
        }
        for (int n : perTick) assertTrue(n < 40, "not all zones on one tick");
    }

    @Test
    void spawnYCandidatesAreFewAndInsideTheZone() {
        java.util.Random r = new java.util.Random(1);
        int[] ys = HuntingRules.spawnYCandidates(60, 80, 70, r::nextInt, 6);
        assertEquals(7, ys.length);
        assertEquals(70, ys[0], "surface first");
        for (int y : ys) assertTrue(y >= 60 && y <= 80, String.valueOf(y));
        ys = HuntingRules.spawnYCandidates(-64, 319, 400, r::nextInt, 6);
        assertEquals(6, ys.length, "surface outside the zone: random ys only");
        assertEquals(0, HuntingRules.spawnYCandidates(80, 60, 70, r::nextInt, 6).length);
    }

    @Test
    void findFloorWalksDownABoundedNumberOfBlocks() {
        int[] calls = {0};
        java.util.function.IntPredicate floorAt65 = y -> {
            calls[0]++;
            return y == 65;
        };
        assertEquals(java.util.OptionalInt.of(65), HuntingRules.findFloor(68, 60, floorAt65, 6));
        calls[0] = 0;
        assertTrue(HuntingRules.findFloor(75, 60, floorAt65, 6).isEmpty());
        assertEquals(6, calls[0], "at most maxSteps checks");
        assertTrue(HuntingRules.findFloor(62, 61, y -> y == 60, 6).isEmpty(), "not below the zone");
    }
}
