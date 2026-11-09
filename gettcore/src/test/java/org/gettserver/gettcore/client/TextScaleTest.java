package org.gettserver.gettcore.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Font scale snapped so that scale × GUI scale is a whole number (pixel font stays crisp). */
class TextScaleTest {
    @Test
    void snapsToWholeScreenPixels() {
        assertEquals(1.5f, TextScale.snap(1.25f, 2), 1e-6);
        assertEquals(1.0f, TextScale.snap(0.75f, 2), 1e-6);
        assertEquals(4f / 3f, TextScale.snap(1.25f, 3), 1e-6);
        assertEquals(2f / 3f, TextScale.snap(0.75f, 3), 1e-6);
        assertEquals(0.75f, TextScale.snap(0.75f, 4), 1e-6);
    }

    @Test
    void neverBelowOneScreenPixel() {
        assertEquals(1.0f, TextScale.snap(0.2f, 1), 1e-6);
    }
}
