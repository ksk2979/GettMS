package org.gettserver.gettcore.enchant.effects;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.ArrowLooseEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.enchant.EnchantConfig;
import org.gettserver.gettcore.enchant.SpecialEnchants.Ability;

/** 활 특수 능력: 연발(같은 방향으로 화살을 연달아 더 쏨), 속사(시위 당기기 가속), 추적(화살이 적에게 휨). */
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

    /** 연발 추가 화살 표시(persistentData). 맞은 대상의 피격 무적을 풀어 연달아 맞아도 피해가 들어가게 한다. */
    static final String BURST_TAG = "gettcore_burst";

    /** 쏘는 중인 연발. 서버 스레드 전용. */
    private static final List<Burst> BURSTS = new ArrayList<>();

    private static final class Burst {
        final ServerPlayer player;
        final ItemStack bow;
        final ItemStack ammo;
        final float power;
        final int interval;
        final double damageScale;
        int remaining;
        long next;

        Burst(ServerPlayer player, ItemStack bow, ItemStack ammo, float power, int interval, double damageScale,
              int remaining, long next) {
            this.player = player;
            this.bow = bow;
            this.ammo = ammo;
            this.power = power;
            this.interval = interval;
            this.damageScale = damageScale;
            this.remaining = remaining;
            this.next = next;
        }
    }

    /**
     * 연발: 바닐라 화살이 나간 뒤, 같은 힘으로 추가 화살을 간격을 두고 연달아 쏜다(쏘는 순간 바라보는 방향).
     * 추가 화살은 소모하지 않고 주울 수 없으며, 피해는 설정한 %만큼.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onArrowLoose(ArrowLooseEvent event) {
        if (event.isCanceled()) return;
        Level level = event.getLevel();
        if (!(level instanceof ServerLevel)) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack bow = event.getBow();
        if (!(bow.getItem() instanceof BowItem)) return;
        int lvl = EffectContext.activeLevel(player, Ability.VOLLEY, bow);
        if (lvl <= 0) return;
        int charge = event.getCharge();
        if (charge < 0) return;
        float power = BowItem.getPowerForTime(charge);
        if (power < 0.1F) return;
        ItemStack ammo = player.getProjectile(bow);
        if (ammo.isEmpty()) return; // 바닐라도 쏘지 않는다

        EnchantConfig.Volley cfg = GettData.get().enchant().volley();
        int extra = burstArrows(cfg.extra(lvl));
        if (extra <= 0) return;
        int interval = burstInterval(cfg.intervalTicks());
        BURSTS.add(new Burst(player, bow.copy(), ammo.copyWithCount(1), power, interval,
                cfg.extraDamagePercent() / 100.0, extra, level.getGameTime() + interval));
    }

    /** 추가 화살 수: 0..{@link #MAX_BURST_ARROWS}. */
    static int burstArrows(int configured) {
        return Math.max(0, Math.min(MAX_BURST_ARROWS, configured));
    }

    /** 화살 간격(틱): 1 이상의 정수로. */
    static int burstInterval(double configured) {
        return (int) Math.max(1, Math.min(40, Math.round(configured)));
    }

    /** 설정 실수로 화살이 쏟아지지 않게 하는 상한. */
    static final int MAX_BURST_ARROWS = 16;

    @SubscribeEvent
    public static void onBurstTick(ServerTickEvent.Post event) {
        if (BURSTS.isEmpty()) return;
        Iterator<Burst> it = BURSTS.iterator();
        while (it.hasNext()) {
            Burst b = it.next();
            ServerPlayer player = b.player;
            if (player.isRemoved() || !player.isAlive() || player.hasDisconnected()
                    || !(player.level() instanceof ServerLevel serverLevel)) {
                it.remove();
                continue;
            }
            if (serverLevel.getGameTime() < b.next) continue;
            shootExtra(serverLevel, b);
            b.next += b.interval;
            if (--b.remaining <= 0) it.remove();
        }
    }

    private static void shootExtra(ServerLevel level, Burst b) {
        ServerPlayer player = b.player;
        ArrowItem arrowItem = b.ammo.getItem() instanceof ArrowItem a ? a : (ArrowItem) Items.ARROW;
        // createArrow(weapon)가 바닐라처럼 관통 수와 EnchantmentHelper.onProjectileSpawned(화염 등)를 적용한다
        AbstractArrow arrow = arrowItem.createArrow(level, b.ammo, player, b.bow);
        if (b.bow.getItem() instanceof BowItem bowItem) arrow = bowItem.customArrow(arrow, b.ammo, b.bow);
        arrow.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, b.power * 3.0F, 1.0F);
        arrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
        arrow.setCritArrow(b.power == 1.0F);
        arrow.setBaseDamage(arrow.getBaseDamage() * b.damageScale);
        arrow.getPersistentData().putBoolean(BURST_TAG, true);
        level.addFreshEntity(arrow);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ARROW_SHOOT,
                SoundSource.PLAYERS, 1.0F, 1.0F / (level.getRandom().nextFloat() * 0.4F + 1.2F) + b.power * 0.5F);
    }

    /** 연발 추가 화살이 생물에 맞으면 피격 무적을 풀어, 앞 화살 직후에도 피해가 들어가게 한다. */
    @SubscribeEvent
    public static void onBurstImpact(ProjectileImpactEvent event) {
        if (event.getProjectile().level().isClientSide()) return;
        if (!(event.getProjectile() instanceof AbstractArrow arrow)) return;
        if (!arrow.getPersistentData().getBoolean(BURST_TAG)) return;
        if (event.getRayTraceResult() instanceof EntityHitResult hit && hit.getEntity() instanceof LivingEntity target) {
            target.invulnerableTime = 0;
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
        if (event.getEntity().level().isClientSide()) return; // 통합 서버: 클라이언트 스레드에서 서버 맵을 건드리지 않는다
        QUICKDRAW.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onUseFinish(LivingEntityUseItemEvent.Finish event) {
        if (event.getEntity().level().isClientSide()) return;
        QUICKDRAW.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        QUICKDRAW.remove(event.getEntity().getUUID());
        BURSTS.removeIf(b -> b.player == event.getEntity());
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
