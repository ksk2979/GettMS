package org.gettserver.gettcore.pet.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HealthBarSettingTest {
    @Test
    void autoHidesWhenEpicFightIsLoaded() {
        assertFalse(HealthBarSetting.show("auto", true));
        assertTrue(HealthBarSetting.show("auto", false));
    }

    @Test
    void onAndOffOverrideAuto() {
        assertTrue(HealthBarSetting.show("on", true));
        assertFalse(HealthBarSetting.show("off", false));
    }

    @Test
    void missingOrUnknownMeansAuto() {
        assertFalse(HealthBarSetting.show(null, true));
        assertTrue(HealthBarSetting.show("weird", false));
        assertTrue(HealthBarSetting.show(" ON ", true));
    }
}
