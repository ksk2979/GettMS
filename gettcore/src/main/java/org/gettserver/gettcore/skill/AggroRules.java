package org.gettserver.gettcore.skill;

import java.util.OptionalDouble;

/** Pure 선공/후공 rules for a boss; the entity glue is {@code pet.boss.BossAggro}. */
public final class AggroRules {
    /** Seeing a player refreshes the timer at most this often (the memory is a synched tag; calm is minutes). */
    public static final long REFRESH_TICKS = 20;

    private AggroRules() {}

    /** {@code provokedTick} is the game time of the last hit by (or sighting of) the player side. */
    public record Memory(boolean angry, long provokedTick) {
        public static final Memory CALM = new Memory(false, 0);
    }

    /** A hit by a player, a player's projectile, or an owned pet counts as the player's. */
    public static boolean playerSide(boolean attackerIsPlayer, boolean attackerOwnedByPlayer) {
        return attackerIsPlayer || attackerOwnedByPlayer;
    }

    public static Memory onHurt(Memory m, boolean playerSide, long now) {
        return playerSide ? new Memory(true, now) : m;
    }

    /** seesPlayer: a valid player within searchRange. Angry + seen refreshes the timer (at most once per {@link #REFRESH_TICKS}); expiry calms. */
    public static Memory tick(Memory m, boolean seesPlayer, long now, int calmSeconds) {
        if (!m.angry()) return m;
        if (seesPlayer) return now - m.provokedTick() >= REFRESH_TICKS ? new Memory(true, now) : m;
        if (calmSeconds > 0 && now - m.provokedTick() >= calmSeconds * 20L) return Memory.CALM;
        return m;
    }

    /** May this boss pick a player it was not hit by as a target right now? */
    public static boolean mayAcquirePlayer(BossConfig.Aggro mode, Memory m, double distance, double reaggroRange) {
        return mode == BossConfig.Aggro.FIRST || (m.angry() && distance <= reaggroRange);
    }

    /**
     * Should a boss let go of the player-side target it holds? A calm 후공 boss does (switched from 선공, or its anger
     * expired); a real hit makes it angry before the hurt-by goal picks the attacker.
     */
    public static boolean shouldDropPlayerTarget(BossConfig.Aggro mode, Memory m) {
        return mode == BossConfig.Aggro.AFTER && !m.angry();
    }

    public static boolean shouldRelease(BossConfig.Aggro mode, double distance, double releaseRange) {
        return mode == BossConfig.Aggro.AFTER && distance > releaseRange;
    }

    /**
     * The FOLLOW_RANGE base a boss should have: a 후공 boss holds its target out to {@code releaseRange} (vanilla target
     * goals drop it beyond the follow range, 16 for most mobs); empty = keep the species' own value (선공).
     */
    public static OptionalDouble followRange(BossConfig.Aggro mode, double releaseRange) {
        return mode == BossConfig.Aggro.AFTER ? OptionalDouble.of(releaseRange) : OptionalDouble.empty();
    }

    /** Does the boss keep a target it cannot see? A 후공 boss does (its wind line goes through walls); 선공 is vanilla. */
    public static boolean keepsUnseenTarget(BossConfig.Aggro mode) {
        return mode == BossConfig.Aggro.AFTER;
    }
}
