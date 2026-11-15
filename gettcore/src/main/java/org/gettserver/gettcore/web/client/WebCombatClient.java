package org.gettserver.gettcore.web.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.network.WebAttackPayload;
import org.gettserver.gettcore.network.WebShotSyncPayload;
import org.gettserver.gettcore.network.WebSlingPayload;
import org.gettserver.gettcore.web.ClientWebConfig;
import org.gettserver.gettcore.web.WebConfig;
import org.gettserver.gettcore.web.WebHands;
import org.gettserver.gettcore.web.WebRules;
import org.lwjgl.glfw.GLFW;

/**
 * 발사기를 오른손(주 손)에 찼을 때의 클라이언트 쪽: 좌클릭 = 거미줄 쏘기, 묶기 키 = 거미줄 묶기, 앉아서 우클릭을
 * 누르고 있다 떼면 슬링샷. 날아가는 탄·묶인 대상·슬링샷 줄의 표시 상태도 여기서 들고 있다.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID, value = Dist.CLIENT)
public final class WebCombatClient {
    public static final KeyMapping BIND = new KeyMapping("key.gettcore.web_bind", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R, "gettcore.key.category");

    /** A web ball on its way from a player's wrist to {@code end}. */
    record Shot(int shooter, boolean offhand, boolean bind, Vec3 end, long start, int flight) {}

    private static final List<Shot> SHOTS = new ArrayList<>();
    /** Bound entities: entity id -> game time the binding ends. */
    private static final Map<Integer, Long> BOUND = new HashMap<>();
    /** Players charging a slingshot: entity id -> {right anchor, left anchor}. */
    private static final Map<Integer, Vec3[]> SLINGS = new HashMap<>();
    private static boolean nextOffhand;
    private static long lastShot = Long.MIN_VALUE;
    private static long lastBind = Long.MIN_VALUE;
    private static long slingStart = Long.MIN_VALUE;
    /** After a launch: game time until which the flight keeps its speed, and when it began. */
    private static long glideUntil = Long.MIN_VALUE;
    private static long glideFrom = Long.MIN_VALUE;

    private WebCombatClient() {}

    /** With a shooter on each wrist the hands take turns. */
    static InteractionHand nextHand() {
        nextOffhand = !nextOffhand;
        return nextOffhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    static List<Shot> shots() {
        return SHOTS;
    }

    /** Is that player charging a slingshot (both arms on the lines)? */
    public static boolean slinging(int entityId) {
        return SLINGS.containsKey(entityId);
    }

    static Vec3[] sling(int entityId) {
        return SLINGS.get(entityId);
    }

    static Map<Integer, Vec3[]> slings() {
        return SLINGS;
    }

    static void clear() {
        SHOTS.clear();
        BOUND.clear();
        SLINGS.clear();
        lastShot = Long.MIN_VALUE;
        lastBind = Long.MIN_VALUE;
        slingStart = Long.MIN_VALUE;
        glideUntil = Long.MIN_VALUE;
    }

    static void expire(Minecraft mc, long now) {
        SHOTS.removeIf(s -> now > s.start() + s.flight() + 1);
        BOUND.values().removeIf(until -> now >= until);
        SLINGS.keySet().removeIf(id -> !(mc.level.getEntity(id) instanceof Player));
    }

    // ---- server -> client ----

    public static void onShotSync(WebShotSyncPayload p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        SHOTS.add(new Shot(p.shooterId(), p.offhand(), p.bind(), new Vec3(p.x(), p.y(), p.z()),
                mc.level.getGameTime(), Math.max(1, p.flightTicks())));
    }

    public static void onBoundSync(int entityId, int ticks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (ticks <= 0) BOUND.remove(entityId);
        else BOUND.put(entityId, mc.level.getGameTime() + ticks);
    }

    public static void onSlingSync(WebSlingPayload p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getId() == p.entityId()) return; // mine is set locally
        if (p.state() == WebSlingPayload.START) {
            SLINGS.put(p.entityId(), new Vec3[]{new Vec3(p.ax(), p.ay(), p.az()), new Vec3(p.bx(), p.by(), p.bz())});
        } else {
            SLINGS.remove(p.entityId());
        }
    }

    // ---- attacks ----

    /** 좌클릭: 손이 닿는 블록을 보고 있지 않으면 주먹질 대신 거미줄을 쏜다. */
    @SubscribeEvent
    public static void onAttackKey(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (!event.isAttack() || mc.player == null || mc.level == null || !WebHands.main(mc.player)) return;
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) return;
        event.setSwingHand(false);
        event.setCanceled(true);
        shoot(mc, false);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (BIND.consumeClick()) {
            if (mc.player != null && mc.level != null && mc.screen == null && WebHands.main(mc.player)) shoot(mc, true);
        }
    }

    private static void shoot(Minecraft mc, boolean bind) {
        WebConfig.Combat cfg = ClientWebConfig.get().combat();
        long now = mc.level.getGameTime();
        long last = bind ? lastBind : lastShot;
        double seconds = bind ? cfg.bindCooldownSeconds() : cfg.shotCooldownSeconds();
        long wait = Math.round(seconds * 20);
        if (last != Long.MIN_VALUE && now - last < wait) {
            if (bind) {
                mc.player.displayClientMessage(Component.translatable("gettcore.web.bind_wait",
                        String.format("%.1f", (wait - (now - last)) / 20.0)), true);
            }
            return;
        }
        if (bind) lastBind = now;
        else lastShot = now;
        // 양손에 찼으면 번갈아, 아니면 오른손에서 나간다.
        boolean offhand = WebHands.dual(mc.player) && nextHand() == InteractionHand.OFF_HAND;
        PacketDistributor.sendToServer(new WebAttackPayload(bind, offhand));
    }

    // ---- slingshot ----

    /** Right and left anchor: a point on the terrain ahead to either side, else on the ground there. */
    private static Vec3[] anchors(LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        float yaw = player.getYRot() * Mth.DEG_TO_RAD;
        Vec3 forward = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
        Vec3[] out = new Vec3[2];
        for (int i = 0; i < 2; i++) {
            // yRot with a negative angle turns to the player's right
            Vec3 dir = forward.yRot(i == 0 ? -0.6f : 0.6f).add(0, 0.15, 0).normalize();
            BlockHitResult ahead = player.level().clip(new ClipContext(eye, eye.add(dir.scale(12)),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (ahead.getType() != HitResult.Type.MISS) {
                out[i] = ahead.getLocation();
                continue;
            }
            Vec3 over = eye.add(dir.x * 8, 0, dir.z * 8);
            BlockHitResult ground = player.level().clip(new ClipContext(over, over.add(0, -12, 0),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            out[i] = ground.getType() != HitResult.Type.MISS ? ground.getLocation() : over;
        }
        return out;
    }

    static void cancelSling(LocalPlayer player) {
        if (slingStart == Long.MIN_VALUE) return;
        slingStart = Long.MIN_VALUE;
        SLINGS.remove(player.getId());
        PacketDistributor.sendToServer(WebSlingPayload.end(0, WebSlingPayload.CANCEL));
    }

    private static boolean canSling(Minecraft mc, LocalPlayer player) {
        return WebHands.main(player) && mc.options.keyShift.isDown() && ClientWebConfig.get().sling().power() > 0
                && !player.isPassenger();
    }

    /**
     * The use key just went down. Starts the slingshot when sneaking on the ground with the shooter on the main
     * hand and no line out. Returns true when the slingshot owns the press (it must not fire a line).
     */
    static boolean startSling(Minecraft mc, LocalPlayer player, long now, boolean lineOut) {
        if (slingStart != Long.MIN_VALUE) return true;
        if (!(canSling(mc, player) && player.onGround() && !lineOut)) return false;
        Vec3[] a = anchors(player);
        slingStart = now;
        SLINGS.put(player.getId(), a);
        PacketDistributor.sendToServer(new WebSlingPayload(0, WebSlingPayload.START, a[0].x, a[0].y, a[0].z,
                a[1].x, a[1].y, a[1].z));
        return true;
    }

    /** One tick of the slingshot: charges while the use key stays down, launches along the view when let go. */
    static void tickSling(Minecraft mc, LocalPlayer player, long now, boolean useDown, boolean lineOut) {
        WebConfig.Sling cfg = ClientWebConfig.get().sling();
        if (slingStart == Long.MIN_VALUE) {
            glide(player, now, lineOut);
            return;
        }
        if (!canSling(mc, player)) {
            cancelSling(player);
            return;
        }
        double charge = WebRules.slingPower(now - slingStart, cfg.chargeSeconds() * 20, cfg.minPowerPercent());
        if (useDown) {
            int filled = (int) Math.round(charge * 10);
            player.displayClientMessage(Component.literal("§f" + "■".repeat(filled) + "§8" + "■".repeat(10 - filled)),
                    true);
            return;
        }
        Vec3 dir = player.getViewVector(1.0f).add(0, 0.15, 0).normalize();
        player.setDeltaMovement(dir.scale(cfg.power() / 20.0 * charge));
        glideFrom = now;
        glideUntil = now + Math.round(cfg.glideSeconds() * 20);
        slingStart = Long.MIN_VALUE;
        SLINGS.remove(player.getId());
        PacketDistributor.sendToServer(WebSlingPayload.end(0, WebSlingPayload.LAUNCH));
    }

    /**
     * 발사 직후: 정해진 시간 동안 공기 저항과 중력을 되돌려 처음 속도 그대로 곧게 날아간다. 땅이나 벽에 닿거나
     * 줄을 걸면 거기서 끝난다.
     */
    private static void glide(LocalPlayer player, long now, boolean lineOut) {
        if (glideUntil == Long.MIN_VALUE) return;
        boolean landed = now > glideFrom + 1 && (player.onGround() || player.horizontalCollision);
        if (now >= glideUntil || landed || lineOut || player.isInWater() || player.isFallFlying()) {
            glideUntil = Long.MIN_VALUE;
            return;
        }
        if (now == glideFrom) return; // the launch velocity itself has not been moved with yet
        Vec3 v = player.getDeltaMovement();
        double[] kept = WebRules.undoAirTick(new double[]{v.x, v.y, v.z});
        player.setDeltaMovement(kept[0], kept[1], kept[2]);
    }

    // ---- bound entities ----

    /** 묶인 대상: 몸을 감은 흰 줄 고리 몇 개. */
    @SubscribeEvent
    public static void onRenderLiving(RenderLivingEvent.Post<?, ?> event) {
        LivingEntity entity = event.getEntity();
        Long until = BOUND.get(entity.getId());
        if (until == null || entity.level().getGameTime() >= until) return;
        PoseStack.Pose pose = event.getPoseStack().last();
        VertexConsumer out = event.getMultiBufferSource().getBuffer(RenderType.lines());
        float radius = entity.getBbWidth() * 0.62f;
        float height = entity.getBbHeight();
        int segments = 12;
        for (int ring = 0; ring < 5; ring++) {
            float y = height * (0.15f + ring * 0.17f);
            float tilt = (ring % 2 == 0 ? 1 : -1) * height * 0.07f;
            float phase = ring * 0.9f;
            for (int s = 0; s < segments; s++) {
                float a0 = phase + Mth.TWO_PI * s / segments;
                float a1 = phase + Mth.TWO_PI * (s + 1) / segments;
                float x0 = Mth.cos(a0) * radius, z0 = Mth.sin(a0) * radius, y0 = y + Mth.sin(a0) * tilt;
                float x1 = Mth.cos(a1) * radius, z1 = Mth.sin(a1) * radius, y1 = y + Mth.sin(a1) * tilt;
                float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
                float len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 1.0e-4f) continue;
                out.addVertex(pose, x0, y0, z0).setColor(245, 245, 245, 255).setNormal(pose, dx / len, dy / len, dz / len);
                out.addVertex(pose, x1, y1, z1).setColor(245, 245, 245, 255).setNormal(pose, dx / len, dy / len, dz / len);
            }
        }
    }
}
