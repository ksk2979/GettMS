package org.gettserver.gettcore.quest.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Properties;
import org.gettserver.gettcore.quest.client.HudPosition.Anchor;
import org.junit.jupiter.api.Test;

class HudPositionTest {
    private static final int SW = 480;
    private static final int SH = 270;
    private static final int BW = 120;
    private static final int BH = 40;

    @Test
    void defaultMatchesOldPlacement() {
        HudPosition d = HudPosition.DEFAULT;
        assertEquals(Anchor.TOP_RIGHT, d.anchor());
        assertTrue(d.isDefault());
        // old code: x = guiWidth - w - MARGIN(4), y = MARGIN(4)
        assertEquals(SW - BW - 4, d.left(SW, BW));
        assertEquals(4, d.top(SH, BH));
    }

    @Test
    void nearestCornerTopLeft() {
        HudPosition p = HudPosition.fromAbsolute(10, 20, BW, BH, SW, SH);
        assertEquals(new HudPosition(Anchor.TOP_LEFT, 10, 20), p);
        assertEquals(10, p.left(SW, BW));
        assertEquals(20, p.top(SH, BH));
    }

    @Test
    void nearestCornerTopRight() {
        HudPosition p = HudPosition.fromAbsolute(SW - BW - 7, 3, BW, BH, SW, SH);
        assertEquals(new HudPosition(Anchor.TOP_RIGHT, 7, 3), p);
        assertEquals(SW - BW - 7, p.left(SW, BW));
    }

    @Test
    void nearestCornerBottomLeft() {
        HudPosition p = HudPosition.fromAbsolute(5, SH - BH - 9, BW, BH, SW, SH);
        assertEquals(new HudPosition(Anchor.BOTTOM_LEFT, 5, 9), p);
        assertEquals(SH - BH - 9, p.top(SH, BH));
    }

    @Test
    void nearestCornerBottomRight() {
        HudPosition p = HudPosition.fromAbsolute(SW - BW - 1, SH - BH - 2, BW, BH, SW, SH);
        assertEquals(new HudPosition(Anchor.BOTTOM_RIGHT, 1, 2), p);
        assertEquals(SW - BW - 1, p.left(SW, BW));
        assertEquals(SH - BH - 2, p.top(SH, BH));
    }

    @Test
    void defaultPlacementMapsBackToDefault() {
        HudPosition d = HudPosition.DEFAULT;
        assertEquals(d, HudPosition.fromAbsolute(d.left(SW, BW), d.top(SH, BH), BW, BH, SW, SH));
    }

    @Test
    void absoluteOffScreenIsClampedToNonNegativeDistances() {
        HudPosition p = HudPosition.fromAbsolute(SW + 50, -30, BW, BH, SW, SH);
        assertEquals(new HudPosition(Anchor.TOP_RIGHT, 0, 0), p);
    }

    @Test
    void clampsWhenScreenShrinks() {
        HudPosition p = new HudPosition(Anchor.TOP_LEFT, 400, 250);
        assertEquals(SW - BW, p.left(SW, BW));
        assertEquals(SH - BH, p.top(SH, BH));
        HudPosition r = new HudPosition(Anchor.BOTTOM_RIGHT, 1000, 1000);
        assertEquals(0, r.left(SW, BW));
        assertEquals(0, r.top(SH, BH));
    }

    @Test
    void boxLargerThanScreenSticksToTopLeft() {
        HudPosition p = HudPosition.DEFAULT;
        assertEquals(0, p.left(100, 150));
        assertEquals(0, p.top(30, 60));
    }

    @Test
    void propertiesRoundTrip() {
        HudPosition p = new HudPosition(Anchor.BOTTOM_LEFT, 12, 34);
        Properties props = new Properties();
        props.setProperty("questHud", "false");
        p.write(props);
        assertEquals("bottom_left", props.getProperty("hud.anchor"));
        assertEquals("12", props.getProperty("hud.x"));
        assertEquals("34", props.getProperty("hud.y"));
        assertEquals("false", props.getProperty("questHud"));
        assertEquals(p, HudPosition.read(props));
    }

    @Test
    void missingOrGarbageGivesDefault() {
        assertEquals(HudPosition.DEFAULT, HudPosition.read(new Properties()));
        assertEquals(HudPosition.DEFAULT, read("middle", "1", "2"));
        assertEquals(HudPosition.DEFAULT, read("top_left", "abc", "2"));
        assertEquals(HudPosition.DEFAULT, read("top_left", "1", ""));
        assertEquals(HudPosition.DEFAULT, read("top_left", "-5", "2"));
        assertEquals(new HudPosition(Anchor.TOP_LEFT, 1, 2), read(" TOP_LEFT ", " 1", "2 "));
    }

    private static HudPosition read(String a, String x, String y) {
        Properties props = new Properties();
        props.setProperty("hud.anchor", a);
        props.setProperty("hud.x", x);
        props.setProperty("hud.y", y);
        return HudPosition.read(props);
    }
}
