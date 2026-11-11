package org.gettserver.gettcore.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.gettserver.gettcore.data.EconomyConfig;
import org.junit.jupiter.api.Test;

/** 총소리 음량 배율: 남이 쏜 소리(NTGL GunShotSound)와 내 총소리(ntgl·cgs 소리), 배율이 1 미만일 때만. */
class GunSoundVolumeTest {
    @Test
    void onlyGunSoundsAndOnlyWhenTurnedDown() {
        String simple = "net.minecraft.client.resources.sounds.SimpleSoundInstance";
        assertTrue(GunSoundVolume.applies(GunSoundVolume.GUN_SHOT_CLASS, "minecraft", 0.2F));
        assertTrue(GunSoundVolume.applies(GunSoundVolume.GUN_SHOT_CLASS, "cgs", 0.0F));
        assertTrue(GunSoundVolume.applies(simple, "cgs", 0.2F), "my own shot");
        assertTrue(GunSoundVolume.applies(simple, "ntgl", 0.2F));
        assertFalse(GunSoundVolume.applies(GunSoundVolume.GUN_SHOT_CLASS, "cgs", 1.0F));
        assertFalse(GunSoundVolume.applies(simple, "minecraft", 0.2F));
    }

    @Test
    void configDefaultsToTwentyPercentAndIsClamped() {
        assertEquals(0.2, EconomyConfig.DEFAULT.gunSoundVolume());
        assertEquals(0.2, parse("{}").gunSoundVolume());
        assertEquals(0.5, parse("{\"gunSoundVolume\":0.5}").gunSoundVolume());
        assertEquals(1.0, parse("{\"gunSoundVolume\":7}").gunSoundVolume());
        assertEquals(0.0, parse("{\"gunSoundVolume\":-1}").gunSoundVolume());
    }

    private static EconomyConfig parse(String json) {
        return EconomyConfig.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }
}
