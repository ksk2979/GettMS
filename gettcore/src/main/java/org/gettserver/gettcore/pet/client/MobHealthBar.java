package org.gettserver.gettcore.pet.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.markusbordihn.easynpc.entity.easynpc.EasyNPC;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import org.gettserver.gettcore.client.ClientPetConfig;
import org.gettserver.gettcore.pet.PetConfig;
import org.gettserver.gettcore.pet.boss.GettBoss;
import org.gettserver.gettcore.pet.entity.GettPet;
import org.joml.Matrix4f;

/**
 * Head-over health bar + percent for mobs the local player (or their pet) hit. Visible for
 * {@code fadeDelayTicks} after the last hit, then fades; stays while health is at or below the capture maximum.
 * Client-only; render thread only.
 */
public final class MobHealthBar {
    private static final long FORGET_TICKS = 1200;
    private static final long PRUNE_INTERVAL_TICKS = 20;
    private static final float SCALE = 0.025f;
    private static final float HALF_WIDTH = 20f;   // 40 units * 0.025 = 1 block
    private static final float BAR_HEIGHT = 4f;
    private static final float TEXT_SCALE = 2f;

    /** entityId → client game time of the last hit by the local player or their pet. */
    private static final Map<Integer, Long> lastHit = new HashMap<>();
    private static long lastPrune;

    private MobHealthBar() {}

    public static float alpha(long ticksSinceHit, double healthRatio, int fadeDelayTicks, int fadeTicks,
                              double captureMax) {
        return HealthBarFade.alpha(ticksSinceHit, healthRatio, fadeDelayTicks, fadeTicks, captureMax);
    }

    public static void onHit(int entityId, long gameTime) {
        lastHit.put(entityId, gameTime);
    }

    /** NeoForge event bus listener (registered in GettCoreClient). */
    public static void onRenderLiving(RenderLivingEvent.Post<?, ?> event) {
        render(event.getEntity(), event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight(),
                event.getPartialTick());
    }

    public static void render(LivingEntity e, PoseStack ps, MultiBufferSource buf, int light, float partialTick) {
        long now = e.level().getGameTime();
        if (now - lastPrune >= PRUNE_INTERVAL_TICKS || now < lastPrune) {
            lastPrune = now;
            lastHit.values().removeIf(t -> now - t > FORGET_TICKS || t > now);
        }
        if (lastHit.isEmpty()) return;
        if (e instanceof Player || e instanceof GettPet || e instanceof EasyNPC) return;
        if (e.isInvisible() || e.isDeadOrDying()) return;
        Long hit = lastHit.get(e.getId());
        if (hit == null) return;

        PetConfig cfg = ClientPetConfig.get();
        PetConfig.HealthBar hb = cfg.healthbar();
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        double dist = hb.showDistance();
        if (dispatcher.distanceToSqr(e) > dist * dist) return;

        float max = e.getMaxHealth();
        double ratio = max > 0 ? Math.max(0, Math.min(1, e.getHealth() / max)) : 0;
        double captureMax = cfg.capture().maxPercent() / 100.0;
        float a = alpha(now - hit, ratio, hb.fadeDelayTicks(), hb.fadeTicks(), captureMax);
        if (a < 0.02f) return;
        boolean capturable = ratio <= captureMax
                && cfg.species(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())).isPresent();
        int captureRgb = hb.captureColor() & 0xFFFFFF;

        ps.pushPose();
        ps.translate(0, e.getBbHeight() + 0.6, 0);
        ps.mulPose(dispatcher.cameraOrientation());
        ps.scale(SCALE, -SCALE, SCALE);   // nameplate space: +y is down, 1 unit = 1/40 block
        Matrix4f m = ps.last().pose();

        float textY;
        if (e instanceof GettBoss) {
            textY = 0;
        } else {
            VertexConsumer vc = buf.getBuffer(RenderType.textBackgroundSeeThrough());
            if (capturable) {
                quad(vc, m, -HALF_WIDTH - 1, -1, HALF_WIDTH + 1, BAR_HEIGHT + 1, withAlpha(captureRgb, a), light);
            }
            quad(vc, m, -HALF_WIDTH, 0, HALF_WIDTH, BAR_HEIGHT, withAlpha(0x000000, a * 0.5f), light);
            float fillRight = -HALF_WIDTH + (float) (2 * HALF_WIDTH * ratio);
            if (fillRight > -HALF_WIDTH) {
                quad(vc, m, -HALF_WIDTH, 0, fillRight, BAR_HEIGHT, withAlpha(0xE03030, a), light);
            }
            textY = BAR_HEIGHT + 2;
        }

        Font font = Minecraft.getInstance().font;
        String text = (int) Math.ceil(ratio * 100) + "%";
        ps.translate(0, textY, 0);
        ps.scale(TEXT_SCALE, TEXT_SCALE, TEXT_SCALE);
        font.drawInBatch(text, -font.width(text) / 2f, 0, withAlpha(capturable ? captureRgb : 0xFFFFFF, a), false,
                ps.last().pose(), buf, Font.DisplayMode.SEE_THROUGH, 0, light);
        ps.popPose();
    }

    private static int withAlpha(int rgb, float alpha) {
        int a = Math.round(Math.max(0, Math.min(1, alpha)) * 255);
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    /** Rectangle (x0,y0)-(x1,y1) in nameplate space, wound like Font's text background so culling keeps it. */
    private static void quad(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, int argb,
                             int light) {
        vc.addVertex(m, x0, y1, 0).setColor(argb).setLight(light);
        vc.addVertex(m, x1, y1, 0).setColor(argb).setLight(light);
        vc.addVertex(m, x1, y0, 0).setColor(argb).setLight(light);
        vc.addVertex(m, x0, y0, 0).setColor(argb).setLight(light);
    }
}
