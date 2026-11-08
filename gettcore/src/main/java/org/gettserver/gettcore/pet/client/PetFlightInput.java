package org.gettserver.gettcore.pet.client;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.network.PetFlightInputPayload;
import org.gettserver.gettcore.pet.PetSpecies;
import org.gettserver.gettcore.pet.entity.GettPet;

/**
 * Flying-mount climb/descend keys (jump / sprint). The rider's client moves the mount, so the keys are applied to the
 * local entity every tick; the server copy is updated only when the pair changes (and once with false/false on
 * dismount). Client only; registered from GettCoreClient.
 */
public final class PetFlightInput {
    private static boolean lastUp;
    private static boolean lastDown;

    private PetFlightInput() {}

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        boolean up = false;
        boolean down = false;
        if (mc.player != null && mc.player.getVehicle() instanceof GettPet pet
                && pet.kind() == PetSpecies.Kind.FLYING_MOUNT) {
            up = mc.options.keyJump.isDown();
            down = mc.options.keySprint.isDown();
            pet.setFlightInput(up, down);
        }
        if (up == lastUp && down == lastDown) return;
        lastUp = up;
        lastDown = down;
        if (mc.getConnection() != null) PacketDistributor.sendToServer(new PetFlightInputPayload(up, down));
    }
}
