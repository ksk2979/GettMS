package org.gettserver.gettcore.pet.client;

import java.util.Optional;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.client.ClientPetConfig;
import org.gettserver.gettcore.network.ThrowCaptureBallPayload;
import org.gettserver.gettcore.pet.CaptureBallItem;
import org.gettserver.gettcore.pet.CaptureRules;
import org.gettserver.gettcore.pet.CaptureService;
import org.gettserver.gettcore.pet.PetConfig;
import org.gettserver.gettcore.pet.PetSpecies;

/**
 * Client side of capture-ball handling: right click held = aim (FOV zoom + capture chance under the crosshair), left
 * click = throw ({@link ThrowCaptureBallPayload}; the server judges everything). Attack and block breaking are
 * suppressed while a ball is in the main hand.
 */
public final class CaptureAimClient {
    public static final ResourceLocation LAYER_ID = GettCore.id("capture_aim");
    private static final double REACH = 32.0;
    private static final int GREEN = 0xFF55FF55, GRAY = 0xFFAAAAAA, RED = 0xFFFF5555, BACKGROUND = 0x90000000;

    /** True once the current attack-key press was handled; the key event repeats every tick while held on a block. */
    private static boolean attackHandled;

    private CaptureAimClient() {}

    /** Aiming = using (right click held) a capture ball in either hand. */
    public static boolean isAiming(Player player) {
        return player != null && player.isUsingItem() && player.getUseItem().getItem() instanceof CaptureBallItem;
    }

    /**
     * Pre so it runs before vanilla key handling, which swallows attack clicks while an item is in use: a left click
     * during aiming is read here instead.
     */
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            CaptureAim.reset();
            attackHandled = false;
            return;
        }
        boolean aiming = isAiming(player);
        CaptureAim.tick(aiming);
        if (!mc.options.keyAttack.isDown()) attackHandled = false;
        if (aiming && mc.screen == null && player.getMainHandItem().getItem() instanceof CaptureBallItem) {
            boolean clicked = false;
            while (mc.options.keyAttack.consumeClick()) clicked = true;
            if (clicked) {
                attackHandled = true;
                tryThrow(player);
            }
        }
    }

    /** Left click with a ball in the main hand: no attack or block breaking; the first event of a press throws. */
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack() || event.getHand() != InteractionHand.MAIN_HAND) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !(player.getMainHandItem().getItem() instanceof CaptureBallItem)) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        if (!attackHandled) {
            attackHandled = true;
            tryThrow(player);
        }
    }

    /** Sends the throw unless the client already knows it would be refused (cooldown), and swings the hand. */
    private static void tryThrow(LocalPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (!CaptureService.canThrow(main, player.getCooldowns().isOnCooldown(main.getItem()))) return;
        PacketDistributor.sendToServer(new ThrowCaptureBallPayload());
        player.swing(InteractionHand.MAIN_HAND);
    }

    /** World FOV only (not the hand); scaled by the FOV effects accessibility slider like vanilla zooms. */
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (!event.usedConfiguredFov()) return;
        double zoom = CaptureAim.currentZoom((float) event.getPartialTick());
        if (zoom >= 1.0) return;
        double effect = Minecraft.getInstance().options.fovEffectScale().get();
        event.setFOV(event.getFOV() * (1.0 - (1.0 - zoom) * effect));
    }

    /** GUI layer above the crosshair: the looked-at mob's capture status while aiming. */
    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (!isAiming(player)) return;
        float partial = delta.getGameTimeDeltaPartialTick(true);
        Optional<LivingEntity> target = lookedAt(mc.getCameraEntity(), player, partial);
        if (target.isEmpty()) return;
        LivingEntity e = target.get();
        ItemStack main = player.getMainHandItem();
        ItemStack ball = main.getItem() instanceof CaptureBallItem ? main : player.getUseItem();
        if (!(ball.getItem() instanceof CaptureBallItem ballItem)) return;

        PetConfig.Capture capture = ClientPetConfig.get().capture();
        Optional<PetSpecies> species = ClientPetConfig.species(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
        double ratio = e.getMaxHealth() > 0 ? Math.max(0, Math.min(1, e.getHealth() / e.getMaxHealth())) : 0;
        Component text;
        int color;
        switch (CaptureAim.status(species, CaptureService.hasOwner(e), ratio, capture)) {
            case CAPTURABLE -> {
                double chance = CaptureRules.chance(species.get(), ballItem.tier(), capture, ratio);
                text = Component.translatable("gettcore.hud.aim.chance", Math.round(chance));
                color = GREEN;
            }
            case HEALTH_TOO_HIGH -> {
                text = Component.translatable("gettcore.hud.aim.health", (int) capture.maxPercent());
                color = GRAY;
            }
            case HEALTH_TOO_LOW -> {
                text = Component.translatable("gettcore.hud.aim.health_low", (int) Math.ceil(capture.minPercent()));
                color = GRAY;
            }
            case OWNED -> {
                text = Component.translatable("gettcore.hud.aim.owned");
                color = RED;
            }
            default -> {
                text = Component.translatable("gettcore.hud.aim.not_capturable");
                color = RED;
            }
        }
        Font font = mc.font;
        int w = font.width(text);
        int x = (g.guiWidth() - w) / 2;
        int y = g.guiHeight() / 2 + 12;
        g.fill(x - 3, y - 2, x + w + 3, y + font.lineHeight + 1, BACKGROUND);
        g.drawString(font, text, x, y, color, true);
    }

    /** Nearest living entity (not the player) along the camera's view within {@link #REACH}, stopped by blocks. */
    private static Optional<LivingEntity> lookedAt(Entity camera, Player player, float partial) {
        if (camera == null) return Optional.empty();
        Vec3 eye = camera.getEyePosition(partial);
        Vec3 view = camera.getViewVector(partial);
        double reach = REACH;
        HitResult block = camera.pick(REACH, partial, false);
        if (block.getType() != HitResult.Type.MISS) reach = block.getLocation().distanceTo(eye);
        Vec3 end = eye.add(view.scale(reach));
        AABB box = camera.getBoundingBox().expandTowards(view.scale(reach)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(camera, eye, end, box,
                en -> en instanceof LivingEntity && en != player && en != camera && !en.isSpectator(), reach * reach);
        return hit != null && hit.getEntity() instanceof LivingEntity le ? Optional.of(le) : Optional.empty();
    }
}
