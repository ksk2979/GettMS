package org.gettserver.gettcore.ddmixin;

import com.dreamdisplays.platform.client.displays.DisplayScreen;
import com.dreamdisplays.platform.client.player.platform.DisplayPlaybackHost;
import org.gettserver.gettcore.display.client.ClientDisplayState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dream Displays 1.10.9 always restarts a finished video on a display in the world. Here a display repeats only when
 * its repeat switch is on (synced from the server); fullscreen playback keeps the mod's own rule.
 */
@Pseudo
@Mixin(value = DisplayPlaybackHost.class, remap = false)
public abstract class DisplayPlaybackHostMixin {
    @Shadow @Final private DisplayScreen screen;

    @Inject(method = "getShouldLoopOnEnd", at = @At("HEAD"), cancellable = true)
    private void gettcore$repeatSwitch(CallbackInfoReturnable<Boolean> cir) {
        if (!screen.isFullscreenActive()) cir.setReturnValue(ClientDisplayState.loops(screen.getUuid()));
    }
}
