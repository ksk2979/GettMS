package org.gettserver.gettcore.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.gettserver.gettcore.data.EconomyConfig;
import org.junit.jupiter.api.Test;

/** 다른 사람 총소리 음량 배율: NTGL 의 GunShotSound 만, 배율이 1 미만일 때만. */
class GunSoundVolumeTest {
    @Test
    void onlyOtherPlayersGunShotsAndOnlyWhenTurnedDown() {
        assertTrue(GunSoundVolume.applies(GunSoundVolume.GUN_SHOT_CLASS, 0.1F));
        assertTrue(GunSoundVolume.applies(GunSoundVolume.GUN_SHOT_CLASS, 0.0F));
        assertFalse(GunSoundVolume.applies(GunSoundVolume.GUN_SHOT_CLASS, 1.0F));
        assertFalse(GunSoundVolume.applies("net.minecraft.client.resources.sounds.SimpleSoundInstance", 0.1F));
    }

    @Test
    void configDefaultsToTenPercentAndIsClamped() {
        assertEquals(0.1, EconomyConfig.DEFAULT.otherGunSoundVolume());
        assertEquals(0.1, parse("{}").otherGunSoundVolume());
        assertEquals(0.5, parse("{\"otherGunSoundVolume\":0.5}").otherGunSoundVolume());
        assertEquals(1.0, parse("{\"otherGunSoundVolume\":7}").otherGunSoundVolume());
        assertEquals(0.0, parse("{\"otherGunSoundVolume\":-1}").otherGunSoundVolume());
    }

    private static EconomyConfig parse(String json) {
        return EconomyConfig.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }
}
