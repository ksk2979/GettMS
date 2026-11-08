package org.gettserver.gettcore.pet.boss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class BossPointDataTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void unknownPointIsEmptyAndNeverGone() {
        BossPointData data = new BossPointData();
        assertTrue(data.alive("p").isEmpty());
        assertEquals(-1, data.lastGone("p"));
    }

    @Test
    void goneRemovesTheBossAndRecordsTheTime() {
        BossPointData data = new BossPointData();
        data.registerAlive("p", A);
        data.registerAlive("p", B);
        data.onBossGone("p", A, 500);
        assertEquals(List.of(B), data.alive("p"));
        assertEquals(500, data.lastGone("p"));
    }

    @Test
    void resetClearsAliveAndLastGone() {
        BossPointData data = new BossPointData();
        data.registerAlive("p", A);
        data.onBossGone("p", B, 500);
        data.reset("p");
        assertTrue(data.alive("p").isEmpty());
        assertEquals(-1, data.lastGone("p"));
    }

    @Test
    void pruneDropsMissingBossesAndStartsTheTimerWhenNeverGone() {
        BossPointData data = new BossPointData();
        data.registerAlive("p", A);
        data.registerAlive("p", B);
        assertEquals(1, data.prune("p", Set.of(A)::contains, 900));
        assertEquals(List.of(A), data.alive("p"));
        assertEquals(900, data.lastGone("p"));
    }

    @Test
    void pruneKeepsAnEarlierGoneTime() {
        BossPointData data = new BossPointData();
        data.registerAlive("p", A);
        data.onBossGone("p", B, 100);
        assertEquals(0, data.prune("p", u -> false, 900));
        assertEquals(100, data.lastGone("p"));
    }

    @Test
    void saveLoadRoundTrip() {
        BossPointData data = new BossPointData();
        data.registerAlive("p", A);
        data.registerAlive("q", B);
        data.onBossGone("q", B, 1234);
        CompoundTag tag = data.save(new CompoundTag(), null);
        BossPointData back = BossPointData.load(tag, null);
        assertEquals(List.of(A), back.alive("p"));
        assertEquals(-1, back.lastGone("p"));
        assertTrue(back.alive("q").isEmpty());
        assertEquals(1234, back.lastGone("q"));
    }

    @Test
    void removeForgetsThePoint() {
        BossPointData data = new BossPointData();
        data.registerAlive("p", A);
        data.onBossGone("p", B, 7);
        data.remove("p");
        assertTrue(data.alive("p").isEmpty());
        assertEquals(-1, data.lastGone("p"));
    }
}
