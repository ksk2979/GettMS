package org.gettserver.gettcore.pet.boss;

import java.util.List;

/** Pure rules of a ground boss's chase (the entity glue is {@code BossBehavior.BossMeleeGoal}). */
public final class ChaseRules {
    /** Vanilla sprinting: MOVEMENT_SPEED x 1.3 (Naturalist's Bear sprints while its chase modifier is >= 1.25). */
    public static final double SPRINT_FACTOR = 1.3;
    /** Ticks between two position samples of the stuck check. */
    public static final int STUCK_WINDOW_TICKS = 10;
    /** Stuck: less than this share of the way the move speed should have covered in one window. */
    static final double STUCK_FRACTION = 0.25;
    /** How long a stuck boss walks straight at its target before it tries its path again. */
    public static final int DIRECT_HOLD_TICKS = 20;

    private ChaseRules() {}

    /**
     * Steady walking speed on ordinary ground (block friction 0.6) of a mob whose move control speed is
     * {@code moveSpeed} (MOVEMENT_SPEED with the sprint modifier, times the goal's speed modifier). Mob.setSpeed also
     * puts the speed into the forward input (zza, times 0.98), so the push per tick is speed x speed x 0.98 and the
     * result is quadratic: 20 x 0.98 x v^2 / (1 - 0.6 x 0.91), about 43.2 x v^2. A zombie (0.23) walks 2.3 blocks/s.
     */
    public static double groundBlocksPerSecond(double moveSpeed) {
        return 20.0 * 0.98 * moveSpeed * moveSpeed / (1.0 - 0.6 * 0.91);
    }

    /** True when {@code movedBlocks} in {@code windowTicks} is under a quarter of what {@code moveSpeed} should cover. */
    public static boolean stuck(double movedBlocks, double moveSpeed, int windowTicks) {
        double expected = groundBlocksPerSecond(moveSpeed) * windowTicks / 20.0;
        return expected > 0 && movedBlocks < expected * STUCK_FRACTION;
    }

    /**
     * Ticks of walking straight after a stuck-check window: {@link #DIRECT_HOLD_TICKS} when the boss stalled while
     * following its path; 0 when it was not stuck, or stalled while already walking straight (then the path, which may
     * lead round the obstacle, gets its turn again).
     */
    public static int straightTicksAfterWindow(boolean stuck, boolean walkedStraight) {
        return stuck && !walkedStraight ? DIRECT_HOLD_TICKS : 0;
    }

    /** What the chase goal does this tick. HOLD: it would walk straight but the ground ahead is a cliff or lava. */
    public enum Mode { CAST, ATTACK, PATH, DIRECT, HOLD }

    /**
     * A cast owns the movement; within reach the vanilla melee goal bites; a working path is followed; with no
     * usable path (none, or finished short of the target) or while stuck the boss walks straight at its target.
     */
    public static Mode mode(boolean casting, boolean inReach, boolean pathUsable, boolean stuck) {
        if (casting) return Mode.CAST;
        if (inReach) return Mode.ATTACK;
        if (!pathUsable || stuck) return Mode.DIRECT;
        return Mode.PATH;
    }

    /** One block of the column just ahead of the boss. */
    public enum Ground { AIR, SOLID, WATER, HAZARD }

    /**
     * {@code column} runs from one block above the feet down to three below. Safe when a solid block or water comes
     * before any hazard (lava, fire); a column of air (a drop of four or more) is not safe.
     */
    public static boolean safeAhead(List<Ground> column) {
        for (Ground g : column) {
            switch (g) {
                case SOLID, WATER -> { return true; }
                case HAZARD -> { return false; }
                case AIR -> { }
            }
        }
        return false;
    }
}
