package org.gettserver.gettcore.web;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.network.WebBoundSyncPayload;
import org.gettserver.gettcore.network.WebShotSyncPayload;
import org.gettserver.gettcore.network.WebSlingPayload;
import org.gettserver.gettcore.pet.boss.GettBoss;

/**
 * 발사기를 오른손(주 손)에 찼을 때: 거미줄 쏘기(원거리 피해), 거미줄 묶기(대상을 그 자리에 묶음), 슬링샷(이동은
 * 클라이언트가 하고 서버는 주변에 알리고 착지 판정만 이어 준다). 양손에 하나씩 차면 이동속도도 오른다.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class WebCombat {
    private static final ResourceLocation SPEED_ID = GettCore.id("web_dual_speed");
    private static final ResourceLocation BOUND_ID = GettCore.id("web_bound");
    /** 탄이 스쳐도 맞도록 대상 판정 상자를 이만큼 키운다(칸). */
    private static final float AIM_ASSIST = 0.3f;

    private record Bound(ResourceKey<Level> dimension, long until) {}

    /** Bound entities by uuid; {@code until} is in server ticks. */
    private static final Map<UUID, Bound> BOUND = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_SHOT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_BIND = new ConcurrentHashMap<>();

    private WebCombat() {}

    /** A web shooter in each hand. */
    public static boolean dual(Player player) {
        return WebHands.dual(player);
    }

    public static boolean isBound(Entity entity) {
        return BOUND.containsKey(entity.getUUID());
    }

    private static boolean isBoss(Entity entity) {
        return entity instanceof GettBoss || entity.getType().is(Tags.EntityTypes.BOSSES);
    }

    private static boolean ready(Map<UUID, Long> last, UUID id, long now, double seconds) {
        Long at = last.get(id);
        return at == null || now - at >= Math.round(seconds * 20);
    }

    // ---- attacks ----

    public static void attack(ServerPlayer player, boolean bind, boolean offhand) {
        if (!player.isAlive() || player.isSpectator() || !WebHands.main(player)) return;
        offhand = offhand && dual(player);
        WebConfig cfg = GettData.get().web();
        WebConfig.Combat combat = cfg.combat();
        long now = player.server.getTickCount();
        UUID id = player.getUUID();
        if (bind ? !ready(LAST_BIND, id, now, combat.bindCooldownSeconds())
                : !ready(LAST_SHOT, id, now, combat.shotCooldownSeconds())) {
            return;
        }
        ServerLevel level = player.serverLevel();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0f);
        Vec3 end = eye.add(look.scale(cfg.range()));
        BlockHitResult block = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player));
        if (block.getType() != HitResult.Type.MISS) end = block.getLocation();
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(level, player, eye, end,
                player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1.0),
                e -> e instanceof LivingEntity living && living.isAlive() && !e.isSpectator() && e.isPickable()
                        && e != player.getVehicle(), AIM_ASSIST);
        LivingEntity target = hit == null ? null : (LivingEntity) hit.getEntity();
        if (target != null) end = hit.getLocation();

        if (bind) {
            if (target != null && isBoss(target)) {
                player.displayClientMessage(Component.translatable("gettcore.web.bind_boss"), true);
                target = null;
            }
            LAST_BIND.put(id, now);
        } else {
            LAST_SHOT.put(id, now);
        }
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new WebShotSyncPayload(player.getId(), offhand,
                bind, end.x, end.y, end.z, WebRules.flightTicks(eye.distanceTo(end), cfg.shotSpeed())));
        player.swing(offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, true);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SNOWBALL_THROW,
                SoundSource.PLAYERS, 0.7f, bind ? 0.7f : 1.5f);
        level.sendParticles(ParticleTypes.ITEM_COBWEB, end.x, end.y, end.z, bind ? 24 : 8, 0.25, 0.25, 0.25, 0.05);
        if (target == null) return;
        if (bind) {
            bind(player, target, combat);
        } else if (combat.shotDamage() > 0) {
            // An enderman dodges a flying web like any projectile, unless it is bound.
            DamageSource source = target instanceof EnderMan && !isBound(target)
                    ? player.damageSources().thrown(player, player)
                    : player.damageSources().playerAttack(player);
            // Shots come faster than the usual half second of hurt immunity; each one must land.
            target.invulnerableTime = 0;
            target.hurt(source, (float) combat.shotDamage());
        }
    }

    private static void bind(ServerPlayer player, LivingEntity target, WebConfig.Combat combat) {
        double seconds = combat.bindSeconds();
        if (target instanceof Player other) {
            if (!combat.bindPlayers() || other.isCreative() || !player.canHarmPlayer(other)) return;
            seconds = combat.bindPlayerSeconds();
        }
        int ticks = (int) Math.round(seconds * 20);
        if (ticks <= 0) return;
        root(target, true);
        BOUND.put(target.getUUID(), new Bound(target.level().dimension(), player.server.getTickCount() + ticks));
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(target, new WebBoundSyncPayload(target.getId(), ticks));
        target.level().playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.SLIME_BLOCK_PLACE,
                SoundSource.PLAYERS, 0.8f, 0.6f);
    }

    /** Adds or removes the modifiers that keep a bound entity from walking, jumping and flying. */
    private static void root(LivingEntity entity, boolean on) {
        for (Holder<Attribute> attribute : java.util.List.of(Attributes.MOVEMENT_SPEED, Attributes.JUMP_STRENGTH,
                Attributes.FLYING_SPEED)) {
            AttributeInstance instance = entity.getAttribute(attribute);
            if (instance == null) continue;
            instance.removeModifier(BOUND_ID);
            if (on) {
                instance.addTransientModifier(new AttributeModifier(BOUND_ID, -1.0,
                        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
        }
    }

    private static void unbind(LivingEntity entity) {
        root(entity, false);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, new WebBoundSyncPayload(entity.getId(), 0));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (BOUND.isEmpty()) return;
        MinecraftServer server = event.getServer();
        long now = server.getTickCount();
        for (Iterator<Map.Entry<UUID, Bound>> it = BOUND.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Bound> e = it.next();
            ServerLevel level = server.getLevel(e.getValue().dimension());
            Entity entity = level == null ? null : level.getEntity(e.getKey());
            boolean over = now >= e.getValue().until();
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
                // Unloaded or gone: the modifiers are transient, so nothing is left behind.
                if (over || entity != null) it.remove();
                continue;
            }
            if (over) {
                it.remove();
                unbind(living);
                continue;
            }
            if (living instanceof Mob mob) {
                mob.getNavigation().stop();
                Vec3 v = mob.getDeltaMovement();
                mob.setDeltaMovement(0, Math.min(v.y, 0), 0);
            }
            if (now % 10 == 0) {
                level.sendParticles(ParticleTypes.ITEM_COBWEB, living.getX(), living.getY(0.5), living.getZ(), 4,
                        living.getBbWidth() * 0.4, living.getBbHeight() * 0.3, living.getBbWidth() * 0.4, 0.02);
            }
        }
    }

    /** 묶인 엔더맨은 순간이동하지 못한다. */
    @SubscribeEvent
    public static void onEnderTeleport(EntityTeleportEvent.EnderEntity event) {
        if (isBound(event.getEntity())) event.setCanceled(true);
    }

    /** 묶인 대상은 맞아도 밀려나지 않는다(묶어 놓고 계속 때릴 수 있게). */
    @SubscribeEvent
    public static void onKnockBack(net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent event) {
        if (isBound(event.getEntity())) event.setCanceled(true);
    }

    /** 묶인 몹은 공격하지 못한다(묶이기 전에 쏜 화살 포함). 묶인 플레이어는 공격할 수 있다. */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        Entity attacker = event.getSource().getEntity();
        if (attacker instanceof Mob && isBound(attacker)) event.setCanceled(true);
    }

    // ---- dual speed ----

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        double amount = dual(player) ? GettData.get().web().combat().dualSpeedPercent() / 100.0 : 0;
        AttributeModifier current = speed.getModifier(SPEED_ID);
        if (amount <= 0) {
            if (current != null) speed.removeModifier(SPEED_ID);
        } else if (current == null || current.amount() != amount) {
            speed.addOrUpdateTransientModifier(new AttributeModifier(SPEED_ID, amount,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        LAST_SHOT.remove(id);
        LAST_BIND.remove(id);
    }

    // ---- slingshot ----

    public static void sling(ServerPlayer player, WebSlingPayload p) {
        if (!player.isAlive() || player.isSpectator() || !WebHands.main(player)) return;
        PacketDistributor.sendToPlayersTrackingEntity(player, new WebSlingPayload(player.getId(), p.state(),
                p.ax(), p.ay(), p.az(), p.bx(), p.by(), p.bz()));
        if (p.state() == WebSlingPayload.LAUNCH) {
            // The flight that follows ends in a web landing (reduced fall damage, roll).
            WebService.markUse(player);
            player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.PLAYERS, 1.0f, 0.6f);
        } else if (p.state() == WebSlingPayload.START) {
            player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.FISHING_BOBBER_THROW, SoundSource.PLAYERS, 0.6f, 1.4f);
        }
    }
}
