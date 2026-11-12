package org.gettserver.gettcore.skill.client;

/** Pure: opacity of the skill-name shout, fully visible for 15 ticks, then linear to nothing at {@link #LIFE_TICKS}. */
public final class ShoutFade {
    public static final int LIFE_TICKS = 20;
    private static final int FADE_TICKS = 5;

    private ShoutFade() {}

    public static float alpha(int ageTicks, float partial) {
        float t = ageTicks + partial;
        if (t <= LIFE_TICKS - FADE_TICKS) return 1.0f;
        if (t >= LIFE_TICKS) return 0.0f;
        return (LIFE_TICKS - t) / FADE_TICKS;
    }
}
