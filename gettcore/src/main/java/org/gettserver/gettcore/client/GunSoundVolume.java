package org.gettserver.gettcore.client;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import org.gettserver.gettcore.economy.ClientConfig;

/**
 * Turns down gun sounds made by <em>other</em> players. NTGL plays those as its own {@code GunShotSound} (shots and
 * reloads; no attenuation, audible for 100 blocks at nearly full volume), while the shooter's own shot is a plain
 * sound instance and stays as it is. The factor comes from the server ({@code economy.json otherGunSoundVolume}).
 */
public final class GunSoundVolume {
    /** Matched by name so gettcore does not need NTGL to compile or to run. */
    static final String GUN_SHOT_CLASS = "com.nukateam.ntgl.client.audio.GunShotSound";

    private GunSoundVolume() {}

    public static void onPlaySound(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();
        float factor = ClientConfig.otherGunSoundVolume();
        if (sound == null || !applies(sound.getClass().getName(), factor)) return;
        event.setSound(new Scaled(sound, factor));
    }

    static boolean applies(String soundClass, float factor) {
        return factor < 1.0F && GUN_SHOT_CLASS.equals(soundClass);
    }

    /** The same sound, only quieter. */
    record Scaled(SoundInstance inner, float factor) implements SoundInstance {
        @Override public ResourceLocation getLocation() { return inner.getLocation(); }
        @Override public WeighedSoundEvents resolve(SoundManager manager) { return inner.resolve(manager); }
        @Override public Sound getSound() { return inner.getSound(); }
        @Override public SoundSource getSource() { return inner.getSource(); }
        @Override public boolean isLooping() { return inner.isLooping(); }
        @Override public boolean isRelative() { return inner.isRelative(); }
        @Override public int getDelay() { return inner.getDelay(); }
        @Override public float getVolume() { return inner.getVolume() * factor; }
        @Override public float getPitch() { return inner.getPitch(); }
        @Override public double getX() { return inner.getX(); }
        @Override public double getY() { return inner.getY(); }
        @Override public double getZ() { return inner.getZ(); }
        @Override public Attenuation getAttenuation() { return inner.getAttenuation(); }
        @Override public boolean canStartSilent() { return inner.canStartSilent(); }
        @Override public boolean canPlaySound() { return inner.canPlaySound(); }
    }
}
