package org.gettserver.gettcore.pet.boss;

import net.minecraft.server.level.ServerBossEvent;

/**
 * A Gett boss entity (the six {@code gettcore:boss_*} types). Capturing one yields a boss captured-mob item.
 * Implementations only hold a {@link BossState}; the shared logic lives in {@link BossBehavior}.
 */
public interface GettBoss {
    /** Per-entity boss state (point id, scale, boss bar). Never null. */
    BossState bossState();

    /** The boss point that spawned this boss, or "" (e.g. summoned with /summon). */
    default String pointId() {
        return bossState().pointId();
    }

    /** The boss bar shown to the players tracking this boss. */
    default ServerBossEvent bossEvent() {
        return bossState().bossEvent();
    }
}
