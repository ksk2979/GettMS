package org.gettserver.gettcore.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 거미줄 발사기: 비행 시간, 줄 길이, 줄 제약(진자), 설정 정리. */
class WebRulesTest {
    @Test
    void flightTimeFollowsDistanceAndSpeed() {
        assertEquals(6, WebRules.flightTicks(32, 120)); // 6 blocks per tick
        assertEquals(1, WebRules.flightTicks(0.5, 120));
        assertEquals(1, WebRules.flightTicks(0, 120));
        assertEquals(0.5, WebRules.progress(3, 6));
        assertEquals(1.0, WebRules.progress(9, 6));
        assertEquals(0.0, WebRules.progress(-1, 6));
    }

    @Test
    void lengthReelsWithinLimitsAndTakesUpSlackInTheAir() {
        // on the ground the slack stays
        assertEquals(10, WebRules.length(10, 6, false, false, false, 0.4, 1.5, 32));
        // in the air the rope winds up to the current distance
        assertEquals(6, WebRules.length(10, 6, true, false, false, 0.4, 1.5, 32));
        assertEquals(9.6, WebRules.length(10, 10, true, true, false, 0.4, 1.5, 32), 1e-9);
        assertEquals(10.4, WebRules.length(10, 10, true, false, true, 0.4, 1.5, 32), 1e-9);
        assertEquals(1.5, WebRules.length(1.6, 1.6, true, true, false, 0.4, 1.5, 32));
        assertEquals(32, WebRules.length(31.9, 31.9, true, false, true, 0.4, 1.5, 32));
    }

    @Test
    void slackRopeLeavesVelocityAlone() {
        double[] vel = {0.3, -0.1, 0};
        assertSame(vel, WebRules.constrain(new double[]{0, -5, 0}, vel, 10));
        assertFalse(WebRules.taut(new double[]{0, -5, 0}, vel, 10));
    }

    @Test
    void tautRopeKeepsThePlayerOnTheSphere() {
        // hanging straight down at full length and falling: the fall is cancelled
        double[] hang = WebRules.constrain(new double[]{0, -10, 0}, new double[]{0, -0.5, 0}, 10);
        assertArrayEquals(new double[]{0, 0, 0}, hang, 1e-9);
        assertTrue(WebRules.taut(new double[]{0, -10, 0}, new double[]{0, -0.5, 0}, 10));

        // moving sideways at the bottom: stays at rope length, keeps going sideways
        double[] rel = {0, -10, 0};
        double[] swing = WebRules.constrain(rel, new double[]{1, 0, 0}, 10);
        double nx = rel[0] + swing[0], ny = rel[1] + swing[1], nz = rel[2] + swing[2];
        assertEquals(10, Math.sqrt(nx * nx + ny * ny + nz * nz), 1e-9);
        assertTrue(swing[0] > 0.9 && swing[1] > 0, "sideways speed kept, path curves up");
    }

    @Test
    void pendulumKeepsSwingingBackAndForth() {
        // released level with the anchor, gravity only: it must pass the bottom and rise on the far side
        double[] rel = {10, 0, 0};
        double[] vel = {0, 0, 0};
        double minX = rel[0];
        for (int t = 0; t < 200; t++) {
            vel = new double[]{vel[0], vel[1] - 0.08, vel[2]};
            vel = WebRules.constrain(rel, vel, 10);
            rel = new double[]{rel[0] + vel[0], rel[1] + vel[1], rel[2] + vel[2]};
            minX = Math.min(minX, rel[0]);
            assertEquals(10, Math.sqrt(rel[0] * rel[0] + rel[1] * rel[1] + rel[2] * rel[2]), 1e-6);
        }
        assertTrue(minX < -8, "reached the far side: " + minX);
    }

    @Test
    void speedCapKeepsDirection() {
        assertArrayEquals(new double[]{1.5, 0, 0}, WebRules.capSpeed(new double[]{3, 0, 0}, 1.5), 1e-9);
        double[] slow = {0.1, 0.2, 0};
        assertSame(slow, WebRules.capSpeed(slow, 1.5));
    }

    @Test
    void rollTurnsOnceOverItsDuration() {
        assertEquals(0f, WebRules.rollDegrees(0, 10));
        assertEquals(180f, WebRules.rollDegrees(5, 10));
        assertEquals(0f, WebRules.rollDegrees(10, 10));
        assertEquals(0f, WebRules.rollDegrees(-1, 10));
    }

    @Test
    void wallMovesAlongTheWallAndNeverIntoIt() {
        // facing a wall to the south (+z): up is up, left (A) is east (+x)
        assertArrayEquals(new double[]{0, 0.2, 0}, WebRules.climb(0, 1, 1, 0, 0.2), 1e-9);
        assertArrayEquals(new double[]{0.2, 0, 0}, WebRules.climb(0, 1, 0, 1, 0.2), 1e-9);
        // facing a wall to the east (+x): left is north (-z)
        assertArrayEquals(new double[]{0, -0.2, -0.2}, WebRules.climb(1, 0, -1, 1, 0.2), 1e-9);
        // not touching the keys: stays put
        assertArrayEquals(new double[]{0, 0, 0}, WebRules.climb(1, 0, 0, 0, 0.2), 1e-9);
    }

    @Test
    void wallJumpLeavesTheWallAndMantleGoesOverIt() {
        double[] jump = WebRules.wallJump(1, 0);
        assertTrue(jump[0] < 0 && jump[1] > 0 && jump[2] == 0, "away from the wall and up");
        double[] over = WebRules.mantle(0, -1);
        assertTrue(over[2] < 0 && over[1] > 0 && over[0] == 0, "towards the wall and up");
    }

    @Test
    void slingChargesFromItsMinimumToFull() {
        assertEquals(0.3, WebRules.slingPower(0, 20, 30), 1e-9);
        assertEquals(0.65, WebRules.slingPower(10, 20, 30), 1e-9);
        assertEquals(1.0, WebRules.slingPower(20, 20, 30), 1e-9);
        assertEquals(1.0, WebRules.slingPower(500, 20, 30), 1e-9);
        assertEquals(1.0, WebRules.slingPower(0, 0, 30), 1e-9);
    }

    @Test
    void undoingAnAirTickRestoresTheVelocityBeforeIt() {
        // vanilla turns (1.0, 0.5, -2.0) into (0.91, (0.5 - 0.08) * 0.98, -1.82)
        double[] after = {0.91, (0.5 - 0.08) * 0.98, -1.82};
        assertArrayEquals(new double[]{1.0, 0.5, -2.0}, WebRules.undoAirTick(after), 1e-9);
    }

    @Test
    void combatAndSlingSectionsDefaultAndRepair() {
        WebConfig c = WebConfig.fromJsonString("{\"combat\":{\"shotDamage\":9},\"sling\":{\"power\":-1}}");
        assertEquals(9, c.combat().shotDamage());
        assertEquals(WebConfig.Combat.DEFAULT.bindSeconds(), c.combat().bindSeconds());
        List<String> warnings = new ArrayList<>();
        WebConfig fixed = c.sanitized(warnings);
        assertEquals(WebConfig.Sling.DEFAULT.power(), fixed.sling().power());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("sling.power"));
        assertEquals(WebConfig.DEFAULT, WebConfig.fromJsonString(WebConfig.toJsonString(WebConfig.DEFAULT)));
    }

    @Test
    void configReadsMissingFieldsAsDefaultsAndRepairsBadValues() {
        assertTrue(WebConfig.DEFAULT.wallCling());
        assertFalse(WebConfig.fromJsonString("{\"wallCling\":false}").wallCling());
        assertEquals(6, WebConfig.fromJsonString("{\"climbSpeed\":6}").climbSpeed());
        assertEquals(WebConfig.DEFAULT, WebConfig.fromJsonString("{}"));
        assertEquals(WebConfig.DEFAULT, WebConfig.fromJsonString(WebConfig.toJsonString(WebConfig.DEFAULT)));

        WebConfig edited = WebConfig.fromJsonString("{\"range\":48,\"cooldownSeconds\":2.5}");
        assertEquals(48, edited.range());
        assertEquals(2.5, edited.cooldownSeconds());
        assertEquals(WebConfig.DEFAULT.shotSpeed(), edited.shotSpeed());
        List<String> none = new ArrayList<>();
        assertSame(edited, edited.sanitized(none));
        assertTrue(none.isEmpty());

        List<String> warnings = new ArrayList<>();
        WebConfig fixed = WebConfig.fromJsonString("{\"range\":500,\"landingDamagePercent\":-5}").sanitized(warnings);
        assertEquals(WebConfig.DEFAULT.range(), fixed.range());
        assertEquals(WebConfig.DEFAULT.landingDamagePercent(), fixed.landingDamagePercent());
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0).startsWith("web.json: range"));
    }
}
