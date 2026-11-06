package org.gettserver.gettcore.enchant.effects;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.ArrowLooseEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.enchant.EnchantConfig;
import org.gettserver.gettcore.enchant.SpecialEnchants.Ability;

/** 활 특수 능력: 다발(좌우 화살 2발 추가), 속사(시위 당기기 가속), 추적(화살이 적에게 휨). */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class BowEffects {
    /** 추적 화살 유지 시간(틱). */
    private static final int HOMING_TICKS = 100;

    /** 속사 누적기: 남은 사용 시간을 1틱씩 더 줄일 몫. 서버 스레드 전용. */
    private static final Map<UUID, Double> QUICKDRAW = new HashMap<>();
    /** 추적 중인 화살. 서버 스레드 전용. */
    private static final List<Homing> HOMING = new ArrayList<>();

    private record Homing(AbstractArrow arrow, ServerPlayer owner, long expiry) {}

    private BowEffects() {}

    /** 다발: 바닐라 화살과 같은 힘으로 좌우 ±각도에 화살 2발을 더 쏜다. 추가 화살은 소모하지 않고 주울 수 없다. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onArrowLoose(ArrowLooseEvent event) {
        if (event.isCanceled()) return;
        Level level = event.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return;
        Player player = event.getEntity();
        ItemStack bow = event.getBow();
        if (!(bow.getItem() instanceof BowItem bowItem)) return;
        if (EffectContext.activeLevel(player, Ability.VOLLEY, bow) <= 0) return;
        int charge = event.getCharge();
        if (charge < 0) return;
        float power = BowItem.getPowerForTime(charge);
        if (power < 0.1F) return;
        ItemStack ammo = player.getProjectile(bow);
        if (ammo.isEmpty()) return; // 바닐라도 쏘지 않는다

        ArrowItem arrowItem = ammo.getItem() instanceof ArrowItem a ? a : (ArrowItem) Items.ARROW;
        double angle = GettData.get().enchant().volleyAngle();
        for (int side = -1; side <= 1; side += 2) {
            // createArrow(weapon)가 바닐라처럼 관통 수와 EnchantmentHelper.onProjectileSpawned(화염 등)를 적용한다
            AbstractArrow arrow = arrowItem.createArrow(serverLevel, ammo, player, bow);
            arrow = bowItem.customArrow(arrow, ammo, bow);
            arrow.shootFromRotation(player, player.getXRot(), (float) (player.getYRot() + side * angle), 0.0F,
                    power * 3.0F, 1.0F);
            arrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
            arrow.setCritArrow(power == 1.0F);
            serverLevel.addFreshEntity(arrow);
        }
    }

    /** 속사: 매 틱 percent/(100-percent)씩 쌓아 1이 넘을 때마다 남은 사용 시간을 1틱 더 줄인다. */
    @SubscribeEvent
    public static void onUseTick(LivingEntityUseItemEvent.Tick event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack stack = event.getItem();
        if (!(stack.getItem() instanceof BowItem)) return;
        int lvl = EffectContext.activeLevel(player, Ability.QUICKDRAW, stack);
        if (lvl <= 0) {
            QUICKDRAW.remove(player.getUUID());
            return;
        }
        double pct = GettData.get().enchant().quickdraw().get(lvl);
        if (!(pct > 0)) return;
        double add = pct >= 100 ? 1.0 : pct / (100.0 - pct);
        double acc = QUICKDRAW.getOrDefault(player.getUUID(), 0.0) + add;
        int duration = event.getDuration();
        while (acc >= 1.0 && duration > 1) {
            duration--;
            acc -= 1.0;
        }
        if (acc >= 1.0) acc = 0.0; // 더 줄일 수 없으면 쌓아 두지 않는다
        event.setDuration(duration);
        QUICKDRAW.put(player.getUUID(), acc);
    }

    @SubscribeEvent
    public static void onUseStop(LivingEntityUseItemEvent.Stop event) {
        QUICKDRAW.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onUseFinish(LivingEntityUseItemEvent.Finish event) {
        QUICKDRAW.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        QUICKDRAW.remove(event.getEntity().getUUID());
    }

    /** 추적: 메인핸드 활에 추적이 있는 플레이어가 쏜 화살을 등록한다. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || event.loadedFromDisk()) return;
        if (!(event.getEntity() instanceof AbstractArrow arrow)) return;
        if (!(arrow.getOwner() instanceof ServerPlayer owner)) return;
        if (EffectContext.activeLevel(owner, Ability.HOMING) <= 0) return;
        long now = event.getLevel().getGameTime();
        HOMING.add(new Homing(arrow, owner, now + HOMING_TICKS));
    }

    /** 추적: 등록된 화살마다 전방 원뿔 안 가장 가까운 대상 쪽으로 속도 방향을 조금씩 돌린다(속력 유지). */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (HOMING.isEmpty()) return;
        EnchantConfig cfg = GettData.get().enchant();
        double range = cfg.homingRange();
        double cosCone = Math.cos(Math.toRadians(cfg.homingCone()));
        double steer = cfg.homingSteer();
        Iterator<Homing> it = HOMING.iterator();
        while (it.hasNext()) {
            Homing h = it.next();
            AbstractArrow arrow = h.arrow();
            if (arrow.isRemoved() || arrow.inGround || arrow.level().getGameTime() > h.expiry()) {
                it.remove();
                continue;
            }
            Vec3 v = arrow.getDeltaMovement();
            double speed = v.length();
            if (speed < 1.0E-4) continue;
            Vec3 dir = v.scale(1.0 / speed);
            LivingEntity target = findTarget(arrow, h.owner(), dir, range, cosCone, cfg.homingTargetPlayers());
            if (target == null) continue;
            Vec3 to = target.getEyePosition().subtract(arrow.position()).normalize();
            Vec3 newDir = dir.add(to.subtract(dir).scale(steer)).normalize();
            arrow.setDeltaMovement(newDir.scale(speed));
            arrow.hasImpulse = true;
        }
    }

    private static LivingEntity findTarget(AbstractArrow arrow, ServerPlayer owner, Vec3 dir, double range,
                                           double cosCone, boolean players) {
        if (!(range > 0)) return null;
        Vec3 pos = arrow.position();
        LivingEntity best = null;
        double bestDist = range * range;
        for (LivingEntity e : arrow.level().getEntitiesOfClass(LivingEntity.class,
                arrow.getBoundingBox().inflate(range))) {
            if (!e.isAlive() || e == owner) continue;
            boolean valid = e instanceof Player p
                    ? players && !p.isSpectator() && !p.isCreative()
                    : e instanceof Enemy;
            if (!valid) continue;
            Vec3 to = e.getEyePosition().subtract(pos);
            double d2 = to.lengthSqr();
            if (d2 > bestDist || d2 < 1.0E-6) continue;
            if (dir.dot(to.scale(1.0 / Math.sqrt(d2))) < cosCone) continue;
            best = e;
            bestDist = d2;
        }
        return best;
    }
}
