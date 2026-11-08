package org.gettserver.gettcore.reinforce;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.util.thread.EffectiveSide;
import net.neoforged.neoforge.event.ItemAttributeModifierEvent;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.reinforce.ReinforceRules.Target;

/**
 * 강화 효과 중 속성 기반 부분(근접 공격력·방패 이동속도·방어구 최대 체력)과 방어구 체력 상한. 총 배율은 NTGL 이 있을 때만
 * {@code NtglBridge} 가 붙인다(이 클래스는 NTGL 타입을 모른다).
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class ReinforceEffects {
    /** 체력 상한 보정(음수 ADD_VALUE) 수정자. 플레이어 MAX_HEALTH 에 transient 로 붙는다. */
    public static final ResourceLocation HEALTH_CAP_ID = id("reinforce_health_cap");
    public static final ResourceLocation MELEE_ID = id("reinforce_melee");
    /** 방패 이동속도는 손마다 따로 붙인다(양손 방패일 때 한쪽 교체가 다른 쪽 수정자를 지우지 않게). */
    public static final ResourceLocation SHIELD_SPEED_MAINHAND_ID = id("reinforce_shield_speed_mainhand");
    public static final ResourceLocation SHIELD_SPEED_OFFHAND_ID = id("reinforce_shield_speed_offhand");
    private static final String HEALTH_PREFIX = "reinforce_health_";
    /** 체력 상한 합산 대상 네 부위(머리·몸통·다리·발). */
    static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private ReinforceEffects() {}

    /** 수정자가 붙는 속성(레지스트리 없이 테스트할 수 있게 Holder 대신 enum). */
    public enum Stat { MAX_HEALTH, ATTACK_DAMAGE, MOVEMENT_SPEED }

    /** 아이템 하나에 붙일 강화 수정자. */
    public record ModifierSpec(Stat stat, ResourceLocation id, double amount, AttributeModifier.Operation operation,
            EquipmentSlotGroup group) {
        AttributeModifier toModifier() {
            return new AttributeModifier(id, amount, operation);
        }
    }

    // ---- 순수 함수 ------------------------------------------------------------------------------------------

    /**
     * 분류·단계별 속성 수정자. 단계 ≤ 0, 총(속성 아님), 값 0(설정 0%) 이면 빈 목록. ARMOR 는 {@code armorSlot}(그 방어구의
     * 장착 부위) 이 필요하다. 방패는 주손·보조손 수정자 두 개.
     */
    public static List<ModifierSpec> modifiers(Target target, int level, EquipmentSlot armorSlot,
            ReinforceConfig cfg) {
        if (target == null || level <= 0) return List.of();
        List<ModifierSpec> specs = switch (target) {
            case GUN -> List.of();
            case MELEE -> List.of(new ModifierSpec(Stat.ATTACK_DAMAGE, MELEE_ID,
                    ReinforceRules.meleeFraction(level, cfg), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                    EquipmentSlotGroup.MAINHAND));
            case SHIELD -> {
                double speed = ReinforceRules.shieldSpeedFraction(level, cfg);
                yield List.of(
                        new ModifierSpec(Stat.MOVEMENT_SPEED, SHIELD_SPEED_MAINHAND_ID, speed,
                                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, EquipmentSlotGroup.MAINHAND),
                        new ModifierSpec(Stat.MOVEMENT_SPEED, SHIELD_SPEED_OFFHAND_ID, speed,
                                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, EquipmentSlotGroup.OFFHAND));
            }
            case ARMOR -> armorSlot == null ? List.of() : List.of(new ModifierSpec(Stat.MAX_HEALTH,
                    healthId(armorSlot), ReinforceRules.armorHealth(level, cfg),
                    AttributeModifier.Operation.ADD_VALUE, EquipmentSlotGroup.bySlot(armorSlot)));
        };
        return specs.stream().filter(spec -> spec.amount() != 0).toList();
    }

    /** 부위별 방어구 체력 수정자 id {@code gettcore:reinforce_health_<slot>}. */
    public static ResourceLocation healthId(EquipmentSlot slot) {
        return id(HEALTH_PREFIX + slot.getName());
    }

    /** 네 부위 강화 단계(장착 안 했거나 방어구 아님 = 0) → 체력 상한 보정(≤ 0). */
    public static double healthCapAdjust(int[] armorLevels, ReinforceConfig cfg) {
        double sum = 0;
        for (int level : armorLevels) sum += ReinforceRules.armorHealth(level, cfg);
        return ReinforceRules.healthCapAdjust(sum, cfg);
    }

    /** 툴팁 효과 줄 번역 키. */
    public static String tooltipKey(Target target) {
        return "gettcore.tooltip.reinforce." + target.name().toLowerCase(Locale.ROOT);
    }

    /** 툴팁 효과 줄 수치(현재 단계의 합계): 총·근접·방패는 %, 방어구는 체력. */
    public static double tooltipValue(Target target, int level, ReinforceConfig cfg) {
        int n = Math.max(0, level);
        return switch (target) {
            case GUN -> n * cfg.gunDamagePercentPerLevel();
            case MELEE -> n * cfg.meleeDamagePercentPerLevel();
            case SHIELD -> n * cfg.shieldSpeedPercentPerLevel();
            case ARMOR -> ReinforceRules.armorHealth(n, cfg);
        };
    }

    /** 10.0 → "10", 1.5 → "1.5", 2.25 → "2.25" (정수면 ".0" 없이). */
    public static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        String s = String.format(Locale.ROOT, "%.2f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    // ---- 설정 ---------------------------------------------------------------------------------------------

    /** 현재 스레드 쪽 설정: 클라이언트(렌더 스레드)는 동기화된 사본, 서버는 GettData. */
    public static ReinforceConfig config() {
        return config(FMLEnvironment.dist.isClient() && EffectiveSide.get().isClient());
    }

    public static ReinforceConfig config(boolean clientSide) {
        return clientSide ? ClientReinforceConfig.get() : GettData.get().reinforce();
    }

    // ---- 이벤트 -------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onItemAttributes(ItemAttributeModifierEvent event) {
        ItemStack stack = event.getItemStack();
        int level = ReinforceRules.level(stack);
        if (level <= 0) return;
        Optional<Target> target = ReinforceTargets.target(stack);
        if (target.isEmpty()) return;
        EquipmentSlot armorSlot = stack.getItem() instanceof ArmorItem armor ? armor.getEquipmentSlot() : null;
        for (ModifierSpec spec : modifiers(target.get(), level, armorSlot, config()))
            event.addModifier(holder(spec.stat()), spec.toModifier(), spec.group());
    }

    /**
     * 체력 상한 갱신. 접속·리스폰 직후에는 last 장비가 비어 있어 첫 틱의 detectEquipmentUpdates 가 착용 방어구마다 이
     * 이벤트를 쏘므로, 상한은 아이템 수정자와 같은 패스에서 계산된다(접속/리스폰 이벤트에서 미리 붙이면 아이템 수정자보다
     * 먼저 음수 보정만 붙어 최대 체력이 한 틱 동안 줄어든다).
     */
    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (event.getSlot().getType() != EquipmentSlot.Type.HUMANOID_ARMOR) return;
        if (event.getEntity() instanceof ServerPlayer player) updateHealthCap(player);
    }

    /** 리로드(설정 변경) 시 접속자 전원의 장착 수정자 값과 체력 상한을 다시 계산한다. */
    public static void registerReloadHook() {
        GettData.addReloadHook(() -> {
            try {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server == null) return;
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    refreshEquipped(p);
                    updateHealthCap(p);
                }
            } catch (RuntimeException e) {
                GettCore.LOGGER.error("gettcore: failed refreshing reinforce effects on reload", e);
            }
        });
    }

    /** 네 부위 강화 체력 합이 상한을 넘으면 넘는 만큼 빼는 transient 수정자를 갱신(0 이면 제거). 서버 전용. */
    public static void updateHealthCap(ServerPlayer player) {
        AttributeInstance health = player.getAttribute(Attributes.MAX_HEALTH);
        if (health == null) return;
        int[] levels = new int[ARMOR_SLOTS.length];
        for (int i = 0; i < ARMOR_SLOTS.length; i++) levels[i] = armorLevel(player.getItemBySlot(ARMOR_SLOTS[i]),
                ARMOR_SLOTS[i]);
        double adjust = healthCapAdjust(levels, GettData.get().reinforce());
        if (adjust == 0) {
            health.removeModifier(HEALTH_CAP_ID);
        } else {
            health.addOrUpdateTransientModifier(
                    new AttributeModifier(HEALTH_CAP_ID, adjust, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    /** {@code stack} 이 그 부위에 맞는 강화 대상 방어구인지(체력 상한·단계 능력 공용). */
    static boolean isWornArmor(ItemStack stack, EquipmentSlot slot) {
        return stack.getItem() instanceof ArmorItem armor && armor.getEquipmentSlot() == slot
                && ReinforceTargets.target(stack).orElse(null) == Target.ARMOR;
    }

    /** 그 부위에 맞게 착용한 강화 방어구의 단계(아니면 0). */
    private static int armorLevel(ItemStack stack, EquipmentSlot slot) {
        return isWornArmor(stack, slot) ? ReinforceRules.level(stack) : 0;
    }

    /**
     * 바닐라는 장비가 바뀔 때만 아이템 수정자를 다시 붙이므로, 리로드 후 이미 착용 중인 장비의 강화 수정자를 새 설정 값으로
     * 바꿔 끼운다(우리 id 만 건드림).
     */
    private static void refreshEquipped(ServerPlayer player) {
        ReinforceConfig cfg = GettData.get().reinforce();
        removeOurs(player, Attributes.ATTACK_DAMAGE, List.of(MELEE_ID));
        removeOurs(player, Attributes.MOVEMENT_SPEED, List.of(SHIELD_SPEED_MAINHAND_ID, SHIELD_SPEED_OFFHAND_ID));
        removeOurs(player, Attributes.MAX_HEALTH, List.of(ARMOR_SLOTS).stream().map(ReinforceEffects::healthId)
                .toList());
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = player.getItemBySlot(slot);
            int level = ReinforceRules.level(stack);
            if (level <= 0) continue;
            Optional<Target> target = ReinforceTargets.target(stack);
            if (target.isEmpty()) continue;
            EquipmentSlot armorSlot = stack.getItem() instanceof ArmorItem armor ? armor.getEquipmentSlot() : null;
            for (ModifierSpec spec : modifiers(target.get(), level, armorSlot, cfg)) {
                if (!spec.group().test(slot)) continue;
                AttributeInstance inst = player.getAttribute(holder(spec.stat()));
                if (inst != null) inst.addOrUpdateTransientModifier(spec.toModifier());
            }
        }
    }

    private static void removeOurs(ServerPlayer player, Holder<Attribute> attribute, List<ResourceLocation> ids) {
        AttributeInstance inst = player.getAttribute(attribute);
        if (inst == null) return;
        for (ResourceLocation id : ids) inst.removeModifier(id);
    }

    /** GettCore.id 와 같지만 GettCore 클래스 초기화(레지스트리)를 끌어오지 않는다(MOD_ID 는 상수). */
    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(GettCore.MOD_ID, path);
    }

    private static Holder<Attribute> holder(Stat stat) {
        return switch (stat) {
            case MAX_HEALTH -> Attributes.MAX_HEALTH;
            case ATTACK_DAMAGE -> Attributes.ATTACK_DAMAGE;
            case MOVEMENT_SPEED -> Attributes.MOVEMENT_SPEED;
        };
    }
}
