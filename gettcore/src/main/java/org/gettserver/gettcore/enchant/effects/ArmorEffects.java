package org.gettserver.gettcore.enchant.effects;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.enchant.ArmorConfig;
import org.gettserver.gettcore.enchant.SpecialEnchants;
import org.gettserver.gettcore.enchant.SpecialEnchants.Ability;
import org.gettserver.gettcore.enchant.SpecialEnchants.Tool;

/**
 * 방어구 특수 능력. 입고 있는 동안 적용되고, 같은 능력이 여러 부위에 있으면(이속 증가) 가장 높은 레벨 하나만 센다.
 * 수치형(이속·활력·도약·버티기·등산)은 속성 수정자로, 야간 투시는 효과로, 나머지는 피해 이벤트에서 처리한다.
 * 감지는 클라이언트에서만 그린다({@code enchant.SenseClient}).
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class ArmorEffects {
    /** 속성·야간 투시·재생을 다시 맞추는 간격. */
    static final int REFRESH_TICKS = 10;
    /** 점프 강화 1단계의 바닐라 값. */
    private static final double JUMP_PER_LEVEL = 0.1;
    private static final double CLIMB_STEP = 0.5;

    private static final ResourceLocation SWIFT_ID = GettCore.id("armor_swift");
    private static final ResourceLocation VITALITY_ID = GettCore.id("armor_vitality");
    private static final ResourceLocation STEADFAST_ID = GettCore.id("armor_steadfast");
    private static final ResourceLocation LEAP_ID = GettCore.id("armor_leap");
    private static final ResourceLocation CLIMB_ID = GettCore.id("armor_climb");

    // 서버 틱 수 기준. 서버 스레드 전용이고 재시작하면 비워진다.
    private static final Map<UUID, Long> LAST_HURT = new HashMap<>();
    private static final Map<UUID, Long> NEXT_REGEN = new HashMap<>();
    private static final Map<UUID, Long> UNDYING_READY = new HashMap<>();
    private static final Map<UUID, Long> INVULNERABLE_UNTIL = new HashMap<>();

    private ArmorEffects() {}

    /** 입고 있는 방어구 중 이 능력의 가장 높은 레벨. 진짜 서버 플레이어가 아니면 0. */
    public static int level(Player player, Ability ability) {
        if (!(player instanceof ServerPlayer sp) || player instanceof FakePlayer) return 0;
        int best = 0;
        for (Tool tool : ability.tools()) {
            EquipmentSlot slot = tool.slot();
            if (slot == null) continue;
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty() || !SpecialEnchants.toolOf(stack).filter(tool::equals).isPresent()) continue;
            best = Math.max(best, SpecialEnchants.level(stack, ability, sp.registryAccess()));
        }
        return best;
    }

    private static long now(ServerPlayer player) {
        return player.server.getTickCount();
    }

    static long ticks(double seconds) {
        return Math.max(0, Math.round(seconds * 20));
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer) return;
        if (player.tickCount % REFRESH_TICKS != 0) return;
        ArmorConfig cfg = GettData.get().enchant().armor();

        int swift = level(player, Ability.SWIFT);
        modifier(player, Attributes.MOVEMENT_SPEED, SWIFT_ID, swift > 0 ? cfg.swift().get(swift) / 100.0 : 0,
                Operation.ADD_MULTIPLIED_TOTAL, false);
        // 최대 체력은 저장되는 수정자로: 임시 수정자면 재접속 때 저장된 체력이 기본 최대치로 깎인다
        int vitality = level(player, Ability.VITALITY);
        modifier(player, Attributes.MAX_HEALTH, VITALITY_ID, vitality > 0 ? cfg.vitality().get(vitality) : 0,
                Operation.ADD_VALUE, true);
        int steadfast = level(player, Ability.STEADFAST);
        modifier(player, Attributes.KNOCKBACK_RESISTANCE, STEADFAST_ID,
                steadfast > 0 ? Math.min(1, cfg.steadfast().get(steadfast) / 100.0) : 0, Operation.ADD_VALUE, false);
        int leap = level(player, Ability.LEAP);
        double leapLevels = leap > 0 ? cfg.leap().get(leap) : 0;
        modifier(player, Attributes.JUMP_STRENGTH, LEAP_ID, leapLevels * JUMP_PER_LEVEL, Operation.ADD_VALUE, false);
        modifier(player, Attributes.SAFE_FALL_DISTANCE, LEAP_ID, leapLevels, Operation.ADD_VALUE, false);
        modifier(player, Attributes.STEP_HEIGHT, CLIMB_ID, level(player, Ability.CLIMB) > 0 ? CLIMB_STEP : 0,
                Operation.ADD_VALUE, false);

        nightVision(player, cfg);
        regen(player, cfg);
        undyingReady(player);
    }

    /** {@code amount}가 0 이하이면 수정자를 지우고, 아니면 그 값으로 맞춘다. */
    private static void modifier(ServerPlayer player, Holder<Attribute> attribute, ResourceLocation id, double amount,
                                 Operation operation, boolean permanent) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) return;
        AttributeModifier current = instance.getModifier(id);
        if (!(amount > 0)) {
            if (current != null) instance.removeModifier(id);
            return;
        }
        if (current != null && current.amount() == amount && current.operation() == operation) return;
        AttributeModifier modifier = new AttributeModifier(id, amount, operation);
        if (permanent) {
            instance.addOrReplacePermanentModifier(modifier);
        } else {
            instance.addOrUpdateTransientModifier(modifier);
        }
    }

    /**
     * 야간 투시(항상)와 물안경(물에 닿아 있는 동안)은 끝없는 주변 효과로 걸어 둔다: 남은 시간이 10초 아래로
     * 내려가면 화면이 깜빡이기 때문. 조건이 풀리면 그 효과를 지우고, 물안경이면 설정한 초만큼 남긴다.
     * 끝없는 주변 야간 투시는 이 능력만 만든다고 본다(물약은 끝이 있고, 신호기는 야간 투시를 주지 않는다).
     */
    private static void nightVision(ServerPlayer player, ArmorConfig cfg) {
        boolean goggles = level(player, Ability.GOGGLES) > 0;
        boolean want = level(player, Ability.NIGHTVISION) > 0 || (goggles && player.isInWater());
        MobEffectInstance current = player.getEffect(MobEffects.NIGHT_VISION);
        if (want) {
            if (current == null || !current.isInfiniteDuration()) {
                player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION,
                        0, true, false, true));
            }
        } else if (current != null && current.isAmbient() && current.isInfiniteDuration()) {
            player.removeEffect(MobEffects.NIGHT_VISION);
            int linger = (int) Math.min(Integer.MAX_VALUE, ticks(cfg.gogglesSeconds()));
            if (goggles && linger > 0) {
                player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, linger, 0, true, false, true));
            }
        }
    }

    private static void regen(ServerPlayer player, ArmorConfig cfg) {
        int lvl = level(player, Ability.REGEN);
        if (lvl <= 0 || !player.isAlive() || player.getHealth() >= player.getMaxHealth()) return;
        UUID id = player.getUUID();
        long now = now(player);
        Long lastHurt = LAST_HURT.get(id);
        if (lastHurt != null && now < lastHurt + ticks(cfg.regen().idleSeconds())) return;
        Long next = NEXT_REGEN.get(id);
        if (next != null && now < next) return;
        player.heal(1);
        NEXT_REGEN.put(id, now + Math.max(REFRESH_TICKS, ticks(cfg.regen().intervalSeconds().get(lvl))));
    }

    private static void undyingReady(ServerPlayer player) {
        Long ready = UNDYING_READY.get(player.getUUID());
        if (ready == null || now(player) < ready) return;
        UNDYING_READY.remove(player.getUUID());
        if (level(player, Ability.UNDYING) > 0) {
            player.displayClientMessage(Component.translatable("gettcore.msg.undying_ready"), true);
        }
    }

    /** 불굴의 무적 시간, 회피, 착지. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer) return;
        DamageSource source = event.getSource();
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return; // 공허, /kill
        Long until = INVULNERABLE_UNTIL.get(player.getUUID());
        if (until != null) {
            if (now(player) < until) {
                event.setCanceled(true);
                return;
            }
            INVULNERABLE_UNTIL.remove(player.getUUID());
        }
        ArmorConfig cfg = GettData.get().enchant().armor();

        // 회피: 몹·플레이어가 직접 때린 근접 공격만(화살, 폭발 제외)
        if (source.getDirectEntity() instanceof LivingEntity attacker && attacker != player
                && !source.is(DamageTypeTags.IS_EXPLOSION)) {
            int dodge = level(player, Ability.DODGE);
            if (dodge > 0 && EffectContext.chance(cfg.dodge().get(dodge), player.getRandom())) {
                event.setCanceled(true);
                sound(player, SoundEvents.PLAYER_ATTACK_SWEEP, 0.7f, 1.6f);
                player.displayClientMessage(Component.translatable("gettcore.msg.dodge"), true);
                return;
            }
        }

        // 착지: 낙하 피해(경감 전)의 일부를 주변 적에게. 직접 때린 것이 아니므로 검 능력은 붙지 않는다.
        if (source.is(DamageTypes.FALL) && event.getAmount() > 0) {
            int landing = level(player, Ability.LANDING);
            if (landing > 0) land(player, event.getAmount(), cfg.landing().percent().get(landing), cfg.landing().radius());
        }
    }

    private static void land(ServerPlayer player, float fallDamage, double percent, double radius) {
        float damage = (float) (fallDamage * percent / 100.0);
        if (!(damage > 0) || !(radius > 0)) return;
        List<LivingEntity> targets = player.level().getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(radius),
                e -> e != player && e instanceof Enemy && !(e instanceof Player) && e.isAlive()
                        && e.distanceTo(player) <= radius);
        if (targets.isEmpty()) return;
        DamageSource source = player.damageSources().explosion(null, player);
        for (LivingEntity target : targets) {
            target.hurt(source, damage);
        }
        sound(player, SoundEvents.GENERIC_EXPLODE.value(), 0.5f, 1.4f);
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.1, player.getZ(), 24,
                    radius / 2, 0.1, radius / 2, 0.02);
        }
    }

    /** 불굴: 죽을 피해를 체력 1로 버틴다. 경감이 모두 끝난 뒤의 실제 피해로 판단한다. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDamagePre(LivingDamageEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer) return;
        if (event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return;
        if (event.getNewDamage() < player.getHealth()) return;
        int lvl = level(player, Ability.UNDYING);
        if (lvl <= 0) return;
        UUID id = player.getUUID();
        long now = now(player);
        Long ready = UNDYING_READY.get(id);
        if (ready != null && now < ready) return;
        ArmorConfig.Undying cfg = GettData.get().enchant().armor().undying();
        double seconds = cfg.invulnerableSeconds().get(lvl);

        event.setNewDamage(Math.max(0, player.getHealth() - 1));
        UNDYING_READY.put(id, now + ticks(cfg.cooldownSeconds().get(lvl)));
        INVULNERABLE_UNTIL.put(id, now + ticks(seconds));
        sound(player, SoundEvents.TOTEM_USE, 0.8f, 1.2f);
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, player.getX(), player.getY() + 1, player.getZ(), 40,
                    0.4, 0.6, 0.4, 0.3);
        }
        player.displayClientMessage(Component.translatable("gettcore.msg.undying",
                seconds == Math.rint(seconds) ? Long.toString((long) seconds) : Double.toString(seconds)), true);
    }

    /** 재생이 쉬는 시간을 재려면 마지막으로 실제 피해를 받은 때가 필요하다. */
    @SubscribeEvent
    public static void onDamagePost(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer) return;
        if (event.getNewDamage() > 0) LAST_HURT.put(player.getUUID(), now(player));
    }

    /** 농부의 걸음. */
    @SubscribeEvent
    public static void onTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (event.getEntity() instanceof Player player && level(player, Ability.FARMSTEP) > 0) {
            event.setCanceled(true);
        }
    }

    /** 불굴 쿨타임은 남긴다(재접속으로 초기화되지 않게). */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        LAST_HURT.remove(id);
        NEXT_REGEN.remove(id);
        INVULNERABLE_UNTIL.remove(id);
    }

    private static void sound(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.PLAYERS,
                volume, pitch);
    }
}
