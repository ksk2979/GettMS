package org.gettserver.gettcore.reinforce;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.reinforce.ReinforceConfig.Milestone;
import org.gettserver.gettcore.reinforce.ReinforceRules.Target;

/**
 * 단계 능력(milestones): 조건을 만족하는 동안 서버가 1초마다 포션 효과(3초, ambient, 파티클 없음, 아이콘 표시)를
 * 다시 건다. 조건이 깨지면 남은 지속시간이 끝나며 자연히 사라진다.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class ReinforceMilestones {
    /** 효과 지속(틱). 20틱마다 갱신하므로 한 번 놓쳐도 끊기지 않는다. */
    static final int DURATION = 60;
    static final int PERIOD = 20;

    private ReinforceMilestones() {}

    /** 손·방어구 칸 하나: 강화 분류(대상 아니면 null)와 단계. */
    public record Held(Target target, int level) {
        public static final Held NONE = new Held(null, 0);

        static Held of(ItemStack stack) {
            Optional<Target> t = ReinforceTargets.target(stack);
            return t.map(target -> new Held(target, ReinforceRules.level(stack))).orElse(NONE);
        }

        /** 방어구 칸: 그 부위에 맞는 강화 방어구일 때만 ARMOR (ReinforceEffects 체력 상한과 같은 판정). */
        static Held armor(ItemStack stack, EquipmentSlot slot) {
            return ReinforceEffects.isWornArmor(stack, slot) ? new Held(Target.ARMOR, ReinforceRules.level(stack)) : NONE;
        }
    }

    // ---- 순수 함수 ------------------------------------------------------------------------------------------

    /**
     * 분류별 유효 단계. GUN/MELEE: 주손 장비가 그 분류일 때 그 단계; SHIELD: {@link #shieldLevel}(주손이 총·원거리
     * 무기면 0); ARMOR: 네 부위의
     * 최소 단계(한 부위라도 비었거나 방어구가 아니면 0). {@code armor} 는 네 칸이어야 하며 모자라면 ARMOR 는 0.
     */
    public static Map<Target, Integer> levels(Held main, Held off, boolean shieldBlocked, List<Held> armor) {
        Map<Target, Integer> out = new EnumMap<>(Target.class);
        for (Target t : Target.values()) out.put(t, 0);
        if (main.target() == Target.GUN || main.target() == Target.MELEE) out.put(main.target(), main.level());
        out.put(Target.SHIELD, shieldLevel(main, off, shieldBlocked));
        int armorMin = armor.size() == ReinforceEffects.ARMOR_SLOTS.length ? Integer.MAX_VALUE : 0;
        for (Held h : armor) {
            armorMin = Math.min(armorMin, h.target() == Target.ARMOR ? h.level() : 0);
        }
        out.put(Target.ARMOR, armorMin);
        return out;
    }

    /**
     * 방패 유효 단계: 양손 중 방패인 쪽의 최대 단계. {@code blocked}(주손이 총·활·석궁·삼지창,
     * {@link ReinforceRules#blocksShield}) 이면 0 — 이동속도와 단계 능력이 함께 쓴다.
     */
    public static int shieldLevel(Held main, Held off, boolean blocked) {
        if (blocked) return 0;
        int shield = 0;
        if (main.target() == Target.SHIELD) shield = Math.max(shield, main.level());
        if (off.target() == Target.SHIELD) shield = Math.max(shield, off.level());
        return shield;
    }

    /** 단계 능력으로 걸어도 되는 효과인지: 즉시 효과는 1초마다 다시 걸면 매번 발동하므로 제외(설정 검사의 이중 안전장치). */
    static boolean applicable(boolean instantaneous) {
        return !instantaneous;
    }

    /** {@code levels} 의 분류 단계가 milestone 단계 이상인 것들(경계 포함), 설정 순서대로. */
    public static List<Milestone> active(List<Milestone> all, Map<Target, Integer> levels) {
        List<Milestone> out = new ArrayList<>();
        for (Milestone m : all) {
            if (m.target() == null) continue;
            if (levels.getOrDefault(m.target(), 0) >= m.level()) out.add(m);
        }
        return out;
    }

    // ---- 이벤트 ---------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.tickCount % PERIOD != 0 || !player.isAlive()) return;
        List<Milestone> all = GettData.get().reinforce().milestones();
        if (all.isEmpty()) return;
        List<Held> armor = new ArrayList<>(ReinforceEffects.ARMOR_SLOTS.length);
        for (EquipmentSlot slot : ReinforceEffects.ARMOR_SLOTS) armor.add(Held.armor(player.getItemBySlot(slot), slot));
        ItemStack main = player.getMainHandItem();
        Map<Target, Integer> levels = levels(Held.of(main), Held.of(player.getOffhandItem()),
                ReinforceTargets.blocksShield(main), armor);
        for (Milestone m : active(all, levels)) {
            BuiltInRegistries.MOB_EFFECT.getHolder(m.effect())
                    .filter(holder -> applicable(holder.value().isInstantenous()))
                    .ifPresent(holder ->
                            player.addEffect(new MobEffectInstance(holder, DURATION, m.amplifier(), true, false, true)));
        }
    }
}
