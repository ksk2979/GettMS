package org.gettserver.gettcore.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.gettserver.gettcore.skill.SkillRunner.Machine;
import org.gettserver.gettcore.skill.SkillRunner.Machine.Phase;
import org.junit.jupiter.api.Test;

/** The level-free phase machine inside {@link SkillRunner}. */
class SkillRunnerTest {
    private static SkillSpec spec(String id, double chargeSeconds) {
        return new SkillSpec(id, SkillSpec.STOMP, true, false, "", 10, 1, 0, 6, chargeSeconds, 12,
                new SkillSpec.Shape(0, 0, 6), new SkillSpec.Aftershock(0, 0, 0), new SkillSpec.Terrain(false, 0, 0));
    }

    /** Records every call; active() keeps going for {@code activeTicks} ticks. */
    private static final class Recorder implements SkillType {
        final List<String> calls = new ArrayList<>();
        final List<SkillSpec> released = new ArrayList<>();
        final int activeTicks;

        Recorder(int activeTicks) {
            this.activeTicks = activeTicks;
        }

        @Override public void charge(Cast cast, int age) {
            calls.add("charge" + age);
        }

        @Override public void release(Cast cast) {
            calls.add("release");
            released.add(cast.spec());
        }

        @Override public boolean active(Cast cast, int age) {
            calls.add("active" + age);
            return age < activeTicks - 1;
        }
    }

    private static Supplier<Optional<Machine.Start>> offer(Recorder type, SkillSpec spec) {
        return () -> Optional.of(new Machine.Start(type, new SkillType.Cast(null, null, spec, 7, null)));
    }

    @Test void chargeThenReleaseThenIdle() {
        Machine m = new Machine();
        Recorder type = new Recorder(3);
        SkillSpec s = spec("stomp", 1.0);   // 20 charge ticks
        long now = 100;
        assertTrue(m.tick(true, now, offer(type, s), x -> 50));
        for (int i = 1; i < 20; i++) {
            assertEquals(Phase.CHARGE, m.phase());
            assertFalse(m.tick(true, now + i, offer(type, s), x -> 50));
        }
        assertEquals(Phase.CHARGE, m.phase());
        m.tick(true, now + 20, offer(type, s), x -> 50);   // release
        assertEquals(Phase.ACTIVE, m.phase());
        assertEquals(now + 20 + 50, m.readyTick().get("stomp"));
        m.tick(true, now + 21, offer(type, s), x -> 50);
        m.tick(true, now + 22, offer(type, s), x -> 50);
        assertTrue(m.casting());
        m.tick(true, now + 23, offer(type, s), x -> 50);   // active returns false
        assertEquals(Phase.IDLE, m.phase());
        assertFalse(m.casting());

        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 20; i++) expected.add("charge" + i);
        expected.addAll(List.of("release", "active0", "active1", "active2"));
        assertEquals(expected, type.calls);
    }

    @Test void runnerCancelsWhenCasterGone() {
        SkillSpec s = spec("stomp", 1.0);
        // Mid-charge.
        Machine m = new Machine();
        Recorder type = new Recorder(5);
        m.tick(true, 0, offer(type, s), x -> 50);
        m.tick(true, 1, offer(type, s), x -> 50);
        m.tick(false, 2, offer(type, s), x -> 50);
        assertEquals(Phase.IDLE, m.phase());
        assertFalse(type.calls.contains("release"));
        assertFalse(m.readyTick().containsKey("stomp"));
        // Mid-active.
        Machine m2 = new Machine();
        Recorder type2 = new Recorder(5);
        for (int t = 0; t <= 21; t++) m2.tick(true, t, offer(type2, s), x -> 50);
        assertEquals(Phase.ACTIVE, m2.phase());
        int calls = type2.calls.size();
        m2.tick(false, 22, offer(type2, s), x -> 50);
        assertEquals(Phase.IDLE, m2.phase());
        assertEquals(calls, type2.calls.size());
        // A dead caster never starts a cast.
        Machine m3 = new Machine();
        assertFalse(m3.tick(false, 0, offer(new Recorder(1), s), x -> 50));
        assertEquals(Phase.IDLE, m3.phase());
    }

    @Test void runningCastKeepsItsSpecWhenListChanges() {
        Machine m = new Machine();
        Recorder type = new Recorder(1);
        SkillSpec original = spec("stomp", 1.0);
        SkillSpec edited = spec("stomp", 5.0);
        m.tick(true, 0, offer(type, original), x -> 50);
        for (int t = 1; t <= 20; t++) m.tick(true, t, offer(type, edited), x -> 50);
        assertEquals(1, type.released.size());
        assertSame(original, type.released.get(0));
    }
}
