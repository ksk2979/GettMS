package org.gettserver.gettcore.enchant.effects;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.enchant.EnchantConfig;
import org.gettserver.gettcore.enchant.SpecialEnchants.Ability;

/** 검 특수 능력: 연격(주변 적 추가 피해), 흡혈, 경험(경험치 증가), 처형(빈사 대상 피해 증가), 전리품(드롭 추가). */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class SwordEffects {
    /** 연격 추가 피해 중이면 true. 서버 스레드 전용. */
    private static boolean SWEEPING = false;

    private SwordEffects() {}

    /** 플레이어가 직접 휘두른 근접 공격이면 그 플레이어, 아니면(화살 등) null. */
    private static Player meleeAttacker(DamageSource source) {
        if (source.getEntity() instanceof Player player && source.getDirectEntity() == player) return player;
        return null;
    }

    /** 처형: 체력이 기준 이하인 대상에게 피해 증가. */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide()) return;
        Player player = meleeAttacker(event.getSource());
        if (player == null) return;
        int lvl = EffectContext.activeLevel(player, Ability.EXECUTE);
        if (lvl <= 0) return;
        EnchantConfig cfg = GettData.get().enchant();
        if (target.getHealth() > target.getMaxHealth() * cfg.executeHealthPercent() / 100.0) return;
        event.setAmount((float) (event.getAmount() * (1 + cfg.executeBonus().get(lvl) / 100.0)));
    }

    /** 흡혈·연격: 실제로 들어간 피해 기준. 연격이 준 추가 피해에는 다시 적용하지 않는다. */
    @SubscribeEvent
    public static void onDamagePost(LivingDamageEvent.Post event) {
        if (SWEEPING) return;
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;
        Player player = meleeAttacker(event.getSource());
        if (player == null) return;
        float damage = event.getNewDamage();
        if (!(damage > 0)) return;
        EnchantConfig cfg = GettData.get().enchant();

        int leech = EffectContext.activeLevel(player, Ability.LEECH);
        if (leech > 0) player.heal((float) (damage * cfg.leech().get(leech) / 100.0));

        int sweep = EffectContext.activeLevel(player, Ability.SWEEP);
        if (sweep > 0) sweep(player, victim, damage, cfg.sweepRadius().get(sweep), cfg.sweepDamagePercent());
    }

    private static void sweep(Player player, LivingEntity victim, float damage, double radius, double percent) {
        float dmg = (float) (damage * percent / 100.0);
        if (!(dmg > 0) || !(radius > 0)) return;
        List<LivingEntity> targets = victim.level().getEntitiesOfClass(LivingEntity.class,
                victim.getBoundingBox().inflate(radius),
                e -> e != victim && e instanceof Enemy && !(e instanceof Player) && e.isAlive()
                        && e.distanceTo(victim) <= radius);
        SWEEPING = true;
        try {
            for (LivingEntity target : targets) {
                target.hurt(player.damageSources().playerAttack(player), dmg);
            }
        } finally {
            SWEEPING = false;
        }
    }

    /** 경험: 검으로 처치한 몹의 경험치 증가(플레이어 제외). */
    @SubscribeEvent
    public static void onExperience(LivingExperienceDropEvent event) {
        if (event.getEntity() instanceof Player) return;
        Player player = event.getAttackingPlayer();
        if (player == null) return;
        int lvl = EffectContext.activeLevel(player, Ability.WISDOM);
        if (lvl <= 0) return;
        double mult = 1 + GettData.get().enchant().wisdom().get(lvl) / 100.0;
        event.setDroppedExperience((int) Math.round(event.getDroppedExperience() * mult));
    }

    /** 전리품: 드롭마다 확률로 1개 추가. 장비(손·갑옷 슬롯) 아이템과 같은 종류는 제외. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onDrops(LivingDropsEvent event) {
        LivingEntity dead = event.getEntity();
        Level level = dead.level();
        if (level.isClientSide()) return;
        if (dead instanceof Player) return;
        if (dead instanceof TamableAnimal tame && tame.isTame()) return;
        if (!(event.getSource().getEntity() instanceof Player player)) return;
        int lvl = EffectContext.activeLevel(player, Ability.PLUNDER);
        if (lvl <= 0) return;
        double percent = GettData.get().enchant().plunder().get(lvl);

        Set<Item> equipped = new HashSet<>();
        for (ItemStack stack : dead.getAllSlots()) {
            if (!stack.isEmpty()) equipped.add(stack.getItem());
        }
        List<ItemEntity> extras = new ArrayList<>();
        for (ItemEntity drop : event.getDrops()) {
            ItemStack stack = drop.getItem();
            if (stack.isEmpty() || equipped.contains(stack.getItem())) continue;
            if (!EffectContext.chance(percent, level.getRandom())) continue;
            ItemEntity extra = new ItemEntity(level, drop.getX(), drop.getY(), drop.getZ(), stack.copyWithCount(1));
            extra.setDefaultPickUpDelay();
            extras.add(extra);
        }
        event.getDrops().addAll(extras);
    }
}
