package org.gettserver.gettcore.web.client;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.network.WebFirePayload;
import org.gettserver.gettcore.network.WebReleasePayload;
import org.gettserver.gettcore.network.WebSyncPayload;
import org.gettserver.gettcore.web.ClientWebConfig;
import org.gettserver.gettcore.web.WebConfig;
import org.gettserver.gettcore.web.WebHands;
import org.gettserver.gettcore.web.WebRules;

/**
 * 거미줄 발사기의 클라이언트 쪽: 우클릭(눌렀을 때 한 번)으로 쏘기·떼기, 내 캐릭터의 진자 이동, 모든 플레이어의
 * 줄 상태 보관(그리기는 {@link WebRenderer}, 팔 자세는 {@link WebPose}). 오른손에 찬 발사기는 우클릭을 먼저
 * 가져가고, 왼손에 찬 발사기는 오른손 아이템이 우클릭을 쓰지 않았을 때만 줄을 쏜다({@link WebHands}).
 */
@EventBusSubscriber(modid = GettCore.MOD_ID, value = Dist.CLIENT)
public final class WebClient {
    /** 착지 구르기 길이(틱). */
    public static final int ROLL_TICKS = 10;

    /** One player's web as this client knows it. {@code length} is used for the local player only. */
    public static final class Web {
        public final Vec3 anchor;
        public final boolean hit;
        public final boolean offhand;
        public final long start;
        public final int flight;
        double length = -1;

        Web(Vec3 anchor, boolean hit, boolean offhand, long start, int flight) {
            this.anchor = anchor;
            this.hit = hit;
            this.offhand = offhand;
            this.start = start;
            this.flight = flight;
        }

        public boolean attached(long now) {
            return hit && now >= start + flight;
        }
    }

    private static final Map<Integer, Web> WEBS = new HashMap<>();
    private static final Map<Integer, Long> ROLLS = new HashMap<>();
    /** False from a handled press until the use key is let go (holding it repeats the click). */
    private static boolean useArmed = true;
    private static boolean jumpWasDown;
    private static boolean tautLastTick;
    private static long lastShot = Long.MIN_VALUE;

    private WebClient() {}

    public static Web web(int entityId) {
        return WEBS.get(entityId);
    }

    static Map<Integer, Web> webs() {
        return WEBS;
    }

    /** Game time the roll of {@code entityId} started, or null. */
    static Long rollStart(int entityId) {
        return ROLLS.get(entityId);
    }

    /** The hand holding a web shooter (main hand first), or null. */
    static InteractionHand shooterHand(Player player) {
        if (player.getMainHandItem().is(GettCore.WEB_SHOOTER.get())) return InteractionHand.MAIN_HAND;
        if (player.getOffhandItem().is(GettCore.WEB_SHOOTER.get())) return InteractionHand.OFF_HAND;
        return null;
    }

    /**
     * 오른손 발사기가 우클릭을 양보하는 경우: 손이 닿는 곳의 적이 아닌 대상(NPC·동물 등, 말 걸기·상호작용), 왼손의
     * 지금 먹을 수 있는 음식·마실 것, 손이 닿는 블록을 보고 있을 때 왼손의 설치용 블록. 이때는 바닐라 동작에 맡긴다.
     */
    static boolean mainYields(Minecraft mc, Player player) {
        if (mc.hitResult instanceof EntityHitResult hit && !(hit.getEntity() instanceof Enemy)) return true;
        ItemStack other = player.getOffhandItem();
        if (other.isEmpty()) return false;
        FoodProperties food = other.getFoodProperties(player);
        if (food != null) return player.canEat(food.canAlwaysEat());
        if (other.getUseAnimation() == UseAnim.DRINK) return true;
        return other.getItem() instanceof BlockItem && mc.hitResult != null
                && mc.hitResult.getType() == HitResult.Type.BLOCK;
    }

    // ---- server -> client ----

    public static void onSync(WebSyncPayload p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        boolean mine = mc.player != null && mc.player.getId() == p.entityId();
        if (!p.active()) {
            WEBS.remove(p.entityId());
            return;
        }
        // My own shot is already shown from the moment I clicked.
        if (mine && WEBS.containsKey(p.entityId())) return;
        WEBS.put(p.entityId(), new Web(new Vec3(p.x(), p.y(), p.z()), p.hit(), p.offhand(),
                mc.level.getGameTime(), p.flightTicks()));
    }

    public static void onClingSync(int entityId, boolean on) {
        WallCling.onSync(entityId, on);
    }

    public static void onRoll(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) ROLLS.put(entityId, mc.level.getGameTime());
    }

    // ---- input ----

    /**
     * 우클릭은 손마다 한 번씩(주 손 먼저) 이 이벤트를 지난다. 오른손 발사기는 여기서 바로 가져간다(상자를 열면서
     * 줄이 나가지 않게). 왼손 발사기는 주 손 차례를 바닐라에 맡기므로, 주 손이 무언가 했으면(NPC·상자·활·음식·총)
     * 왼손 차례가 오지 않고, 아무 일도 없었을 때만 여기까지 와서 줄을 쏜다.
     */
    @SubscribeEvent
    public static void onUseKey(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (!event.isUseItem() || player == null || mc.level == null) return;
        InteractionHand hand = shooterHand(player);
        if (hand == null || event.getHand() != hand) return;
        if (hand == InteractionHand.MAIN_HAND ? mainYields(mc, player) : WebHands.mainBlocksOffhand(player)) return;
        event.setSwingHand(false);
        event.setCanceled(true);
        if (!useArmed || player.isSpectator() || !player.isAlive()) return;
        useArmed = false;
        long now = mc.level.getGameTime();
        Web mine = WEBS.get(player.getId());
        boolean lineOut = mine != null && mine.hit;
        // 슬링샷(오른손, 땅에서 앉아 우클릭)이 우클릭을 가져가면 줄은 쏘지 않는다.
        if (WebCombatClient.startSling(mc, player, now, lineOut)) return;
        if (lineOut) releaseMine(player);
        else fire(mc, player, WebHands.dual(player) ? WebCombatClient.nextHand() : hand, now);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            clear();
            return;
        }
        long now = mc.level.getGameTime();
        expire(mc, now);
        WebCombatClient.expire(mc, now);

        boolean down = mc.options.keyUse.isDown();
        if (!down) useArmed = true;
        InteractionHand hand = shooterHand(player);
        // 왼손에만 찼는데 주 손에 원거리·양손 무기를 들었으면 발사기는 없는 셈 친다(줄·벽 붙기 모두 쉼).
        if (hand == InteractionHand.OFF_HAND && WebHands.mainBlocksOffhand(player)) hand = null;
        Web mine = WEBS.get(player.getId());

        if (hand == null || player.isSpectator() || !player.isAlive()) {
            if (mine != null) releaseMine(player);
            WallCling.stop(player);
            WebCombatClient.cancelSling(player);
            jumpWasDown = mc.options.keyJump.isDown();
            return;
        }
        // 점프 키: 공중에 매달려 있을 때 누르면 줄을 끊는다(땅에서는 평소처럼 점프).
        boolean jumpDown = mc.options.keyJump.isDown();
        boolean jumped = jumpDown && !jumpWasDown && mc.screen == null;
        jumpWasDown = jumpDown;
        if (jumped && mine != null && mine.attached(now) && !player.onGround()) {
            releaseMine(player);
            mine = null;
        }
        WebCombatClient.tickSling(mc, player, now, down, mine != null && mine.hit);
        if (mine != null && mine.attached(now)) {
            swing(mc, player, mine);
        } else {
            tautLastTick = false;
        }
        // 벽 붙기: 줄이 붙어 있지 않을 때만.
        WallCling.tick(mc, player, now, (mine == null || !mine.hit) && !player.isPassenger(), jumped);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    private static void clear() {
        WEBS.clear();
        ROLLS.clear();
        WallCling.clear();
        WebCombatClient.clear();
        useArmed = true;
        tautLastTick = false;
        lastShot = Long.MIN_VALUE;
    }

    /** Drops shots that hit nothing once they reached the end, webs of players no longer here, finished rolls. */
    private static void expire(Minecraft mc, long now) {
        for (Iterator<Map.Entry<Integer, Web>> it = WEBS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Web> e = it.next();
            Web w = e.getValue();
            boolean missDone = !w.hit && now > w.start + w.flight + 1;
            if (missDone || !(mc.level.getEntity(e.getKey()) instanceof Player)) it.remove();
        }
        ROLLS.values().removeIf(start -> now - start >= ROLL_TICKS);
    }

    private static void releaseMine(LocalPlayer player) {
        WEBS.remove(player.getId());
        tautLastTick = false;
        PacketDistributor.sendToServer(new WebReleasePayload());
    }

    private static Web fire(Minecraft mc, LocalPlayer player, InteractionHand hand, long now) {
        WebConfig cfg = ClientWebConfig.get();
        if (lastShot != Long.MIN_VALUE && now - lastShot < Math.round(cfg.cooldownSeconds() * 20)) {
            return WEBS.get(player.getId());
        }
        Vec3 eye = player.getEyePosition();
        HitResult result = player.pick(cfg.range(), 1.0f, false);
        boolean hit = result.getType() == HitResult.Type.BLOCK;
        Vec3 target = hit ? result.getLocation() : eye.add(player.getViewVector(1.0f).scale(cfg.range()));
        BlockPos block = hit ? ((BlockHitResult) result).getBlockPos() : BlockPos.containing(target);
        boolean offhand = hand == InteractionHand.OFF_HAND;
        Web web = new Web(target, hit, offhand, now, WebRules.flightTicks(eye.distanceTo(target), cfg.shotSpeed()));
        WEBS.put(player.getId(), web);
        lastShot = now;
        PacketDistributor.sendToServer(new WebFirePayload(hit, offhand, target.x, target.y, target.z, block));
        player.swing(hand);
        return web;
    }

    /** 한 틱의 진자 이동: 줄 길이를 정하고, 다음 이동이 줄 길이를 넘지 않게 속도를 고친다. */
    private static void swing(Minecraft mc, LocalPlayer player, Web web) {
        if (player.isPassenger() || player.isFallFlying() || player.getAbilities().flying) {
            tautLastTick = false;
            return;
        }
        WebConfig cfg = ClientWebConfig.get();
        Vec3 chest = player.position().add(0, player.getBbHeight() * 0.6, 0);
        double[] rel = {chest.x - web.anchor.x, chest.y - web.anchor.y, chest.z - web.anchor.z};
        double distance = Math.sqrt(rel[0] * rel[0] + rel[1] * rel[1] + rel[2] * rel[2]);
        boolean airborne = !player.onGround();
        if (web.length < 0) web.length = Math.max(cfg.minLength(), distance);
        web.length = WebRules.length(web.length, distance, airborne, mc.options.keySprint.isDown(),
                mc.options.keyShift.isDown(), cfg.reelSpeed() / 20.0, cfg.minLength(), cfg.range());

        Vec3 v = player.getDeltaMovement();
        double[] vel = {v.x, v.y, v.z};
        if (airborne && tautLastTick) {
            // Undo the air drag of the last tick so the swing does not die down.
            vel[0] /= WebRules.AIR_DRAG_H;
            vel[1] /= WebRules.AIR_DRAG_V;
            vel[2] /= WebRules.AIR_DRAG_H;
            double speed = Math.sqrt(vel[0] * vel[0] + vel[1] * vel[1] + vel[2] * vel[2]);
            if (player.input.forwardImpulse > 0 && speed < cfg.maxSpeed() / 20.0) {
                Vec3 look = player.getViewVector(1.0f);
                double h = Math.sqrt(look.x * look.x + look.z * look.z);
                if (h > 1.0e-4) {
                    vel[0] += look.x / h * cfg.swingBoost();
                    vel[2] += look.z / h * cfg.swingBoost();
                }
            }
        }
        double[] out = WebRules.constrain(rel, vel, web.length);
        tautLastTick = out != vel;
        if (tautLastTick || vel[0] != v.x || vel[1] != v.y || vel[2] != v.z) {
            player.setDeltaMovement(out[0], out[1], out[2]);
        }
    }
}
