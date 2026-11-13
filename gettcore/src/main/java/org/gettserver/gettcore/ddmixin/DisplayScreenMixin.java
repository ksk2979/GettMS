package org.gettserver.gettcore.ddmixin;

import com.dreamdisplays.core.protocol.common.packets.DisplayInfo;
import com.dreamdisplays.core.protocol.common.packets.DisplaySync;
import com.dreamdisplays.platform.client.displays.DisplayScreen;
import java.util.UUID;
import org.gettserver.gettcore.display.client.ClientDisplayState;
import org.gettserver.gettcore.display.client.PersonalFlag;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dream Displays 1.10.9: while this client watches a personal video on a display, the server's display info (it
 * would drop the personal address for players who may not set the shared one) and the shared timeline (it would
 * seek and pause the personal video) are ignored for that display.
 */
@Pseudo
@Mixin(value = DisplayScreen.class, remap = false)
public abstract class DisplayScreenMixin implements PersonalFlag {
    @Shadow private boolean clientUrlOverride;
    @Shadow @Final private UUID uuid;

    @Override
    public boolean gettcore$personal() {
        return clientUrlOverride;
    }

    @Override
    public void gettcore$setPersonal(boolean on) {
        clientUrlOverride = on;
    }

    @Unique
    private boolean gettcore$watchingPersonal() {
        return clientUrlOverride && ClientDisplayState.personal(uuid);
    }

    @Inject(method = "updateData(Lcom/dreamdisplays/core/protocol/common/packets/DisplayInfo;)V",
            at = @At("HEAD"), cancellable = true)
    private void gettcore$keepPersonalOnInfo(DisplayInfo info, CallbackInfo ci) {
        if (gettcore$watchingPersonal()) ci.cancel();
    }

    @Inject(method = "updateData(Lcom/dreamdisplays/core/protocol/common/packets/DisplaySync;)V",
            at = @At("HEAD"), cancellable = true)
    private void gettcore$keepPersonalOnSync(DisplaySync sync, CallbackInfo ci) {
        if (gettcore$watchingPersonal()) ci.cancel();
    }
}
