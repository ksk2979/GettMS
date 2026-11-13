package org.gettserver.gettcore.enchant;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToIntFunction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.neoforge.common.Tags;
import org.gettserver.gettcore.GettCore;

/** 특수 능력 정의, 도구별 풀, 뽑기, 도구에 붙은 특수 능력 조회/교체. */
public final class SpecialEnchants {
    public static final TagKey<Enchantment> SPECIAL = TagKey.create(Registries.ENCHANTMENT, GettCore.id("special"));

    public enum Tool {
        HOE, PICKAXE, AXE, SWORD, BOW, FISHING_ROD, HELMET, CHESTPLATE, LEGGINGS, BOOTS;

        public boolean isArmor() {
            return this == HELMET || this == CHESTPLATE || this == LEGGINGS || this == BOOTS;
        }

        /** 방어구가 차는 칸. 도구는 null. */
        public EquipmentSlot slot() {
            return switch (this) {
                case HELMET -> EquipmentSlot.HEAD;
                case CHESTPLATE -> EquipmentSlot.CHEST;
                case LEGGINGS -> EquipmentSlot.LEGS;
                case BOOTS -> EquipmentSlot.FEET;
                default -> null;
            };
        }

        /** 설정 파일에서 쓰는 이름. */
        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public enum Ability {
        HARVEST("harvest", Tool.HOE, 3),
        REPLANT("replant", Tool.HOE, 1),
        BOUNTY("bounty", Tool.HOE, 3),
        VEIN("vein", Tool.PICKAXE, 3),
        PROSPECT("prospect", Tool.PICKAXE, 3),
        AREA("area", Tool.PICKAXE, 1),
        SMELT("smelt", Tool.PICKAXE, 1),
        TIMBER("timber", Tool.AXE, 3),
        RESAPLING("resapling", Tool.AXE, 1),
        LUMBER("lumber", Tool.AXE, 3),
        SWEEP("sweep", Tool.SWORD, 3),
        LEECH("leech", Tool.SWORD, 3),
        WISDOM("wisdom", Tool.SWORD, 3),
        EXECUTE("execute", Tool.SWORD, 3),
        PLUNDER("plunder", Tool.SWORD, 3),
        VOLLEY("volley", Tool.BOW, 1),
        QUICKDRAW("quickdraw", Tool.BOW, 3),
        HOMING("homing", Tool.BOW, 1),
        LURE("lure", Tool.FISHING_ROD, 3),
        BIGCATCH("bigcatch", Tool.FISHING_ROD, 3),
        TREASURE("treasure", Tool.FISHING_ROD, 3),
        // 방어구. 이속 증가만 네 부위 공통이고 나머지는 부위 전용.
        SWIFT("swift", 3, Tool.HELMET, Tool.CHESTPLATE, Tool.LEGGINGS, Tool.BOOTS),
        GOGGLES("goggles", Tool.HELMET, 1),
        SENSE("sense", Tool.HELMET, 3),
        NIGHTVISION("nightvision", Tool.HELMET, 1),
        VITALITY("vitality", Tool.CHESTPLATE, 3),
        REGEN("regen", Tool.CHESTPLATE, 3),
        UNDYING("undying", Tool.CHESTPLATE, 3),
        LEAP("leap", Tool.LEGGINGS, 3),
        STEADFAST("steadfast", Tool.LEGGINGS, 3),
        DODGE("dodge", Tool.LEGGINGS, 3),
        FARMSTEP("farmstep", Tool.BOOTS, 1),
        CLIMB("climb", Tool.BOOTS, 1),
        LANDING("landing", Tool.BOOTS, 3);

        private final String id;
        private final Tool tool;
        private final Set<Tool> tools;
        private final int maxLevel;
        private final ResourceKey<Enchantment> key;

        Ability(String id, Tool tool, int maxLevel) {
            this(id, maxLevel, tool);
        }

        Ability(String id, int maxLevel, Tool first, Tool... more) {
            this.id = id;
            this.tool = first;
            this.tools = EnumSet.of(first, more);
            this.maxLevel = maxLevel;
            this.key = ResourceKey.create(Registries.ENCHANTMENT, GettCore.id(id));
        }

        public String id() { return id; }
        /** 첫 번째(대부분 유일한) 장비 종류. */
        public Tool tool() { return tool; }
        /** 이 능력이 붙는 장비 종류인가. */
        public boolean fits(Tool t) { return tools.contains(t); }
        public Set<Tool> tools() { return tools; }
        public int maxLevel() { return maxLevel; }
        public ResourceKey<Enchantment> key() { return key; }

        static Optional<Ability> byKey(ResourceKey<Enchantment> key) {
            for (Ability a : values()) {
                if (a.key.equals(key)) return Optional.of(a);
            }
            return Optional.empty();
        }
    }

    public record Rolled(Ability ability, int level) {}

    /** 레벨 I/II/III 가중치. 3레벨 능력에만 적용된다. */
    public record LevelWeights(int one, int two, int three) {
        public static final LevelWeights DEFAULT = new LevelWeights(70, 25, 5);
        public static final LevelWeights ARMOR_DEFAULT = new LevelWeights(75, 20, 5);

        public boolean valid() {
            return one >= 0 && two >= 0 && three >= 0 && (long) one + two + three > 0;
        }
    }

    private SpecialEnchants() {}

    public static List<Ability> pool(Tool tool) {
        List<Ability> list = new ArrayList<>();
        for (Ability a : Ability.values()) {
            if (a.fits(tool)) list.add(a);
        }
        return List.copyOf(list);
    }

    public static Optional<Tool> toolOf(ItemStack stack) {
        if (stack.isEmpty()) return Optional.empty();
        if (stack.is(ItemTags.HOES)) return Optional.of(Tool.HOE);
        if (stack.is(ItemTags.PICKAXES)) return Optional.of(Tool.PICKAXE);
        if (stack.is(ItemTags.AXES)) return Optional.of(Tool.AXE);
        if (stack.is(ItemTags.SWORDS)) return Optional.of(Tool.SWORD);
        if (stack.is(Items.BOW)) return Optional.of(Tool.BOW);
        if (stack.is(Items.FISHING_ROD) || stack.is(Tags.Items.TOOLS_FISHING_ROD)) return Optional.of(Tool.FISHING_ROD);
        if (stack.is(ItemTags.HEAD_ARMOR)) return Optional.of(Tool.HELMET);
        if (stack.is(ItemTags.CHEST_ARMOR)) return Optional.of(Tool.CHESTPLATE);
        if (stack.is(ItemTags.LEG_ARMOR)) return Optional.of(Tool.LEGGINGS);
        if (stack.is(ItemTags.FOOT_ARMOR)) return Optional.of(Tool.BOOTS);
        return Optional.empty();
    }

    /** 풀 안에서 능력은 균등, 3레벨 능력만 레벨 가중치로 레벨을 고른다. 가중치 합이 0 이하이면 레벨 1. */
    public static Rolled roll(Tool tool, LevelWeights weights, RandomSource random) {
        return roll(tool, weights, a -> 1, random);
    }

    /** 능력마다 가중치가 다른 뽑기(방어구). 음수는 0으로 보고, 합이 0 이하이면 균등. */
    public static Rolled roll(Tool tool, LevelWeights weights, ToIntFunction<Ability> abilityWeight, RandomSource random) {
        List<Ability> pool = pool(tool);
        Ability ability = pool.get(pickIndex(pool.stream().mapToInt(abilityWeight).toArray(), random));
        if (ability.maxLevel < 3) return new Rolled(ability, 1);
        int w1 = Math.max(0, weights.one());
        int w2 = Math.max(0, weights.two());
        int w3 = Math.max(0, weights.three());
        int total = w1 + w2 + w3;
        if (total <= 0) return new Rolled(ability, 1);
        int pick = random.nextInt(total);
        int level = pick < w1 ? 1 : pick < w1 + w2 ? 2 : 3;
        return new Rolled(ability, level);
    }

    /** 가중치에 비례해 고른 위치. 음수는 0, 합이 0 이하이면 균등. */
    static int pickIndex(int[] weights, RandomSource random) {
        long total = 0;
        for (int w : weights) total += Math.max(0, w);
        if (total <= 0) return random.nextInt(weights.length);
        long pick = (long) (random.nextDouble() * total);
        for (int i = 0; i < weights.length; i++) {
            pick -= Math.max(0, weights[i]);
            if (pick < 0) return i;
        }
        return weights.length - 1;
    }

    /** 도구에 붙은 {@code #gettcore:special} 인챈트 중 첫 번째. */
    public static Optional<Rolled> current(ItemStack stack, RegistryAccess registries) {
        ItemEnchantments enchantments = EnchantmentHelper.getEnchantmentsForCrafting(stack);
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments.entrySet()) {
            Holder<Enchantment> holder = entry.getKey();
            if (!holder.is(SPECIAL)) continue;
            Optional<Ability> ability = holder.unwrapKey().flatMap(Ability::byKey);
            if (ability.isPresent()) return Optional.of(new Rolled(ability.get(), entry.getIntValue()));
        }
        return Optional.empty();
    }

    /** 특수 능력을 전부 지우고 {@code rolled}를 붙인다. 바닐라 인챈트는 유지. */
    public static void set(ItemStack stack, Rolled rolled, RegistryAccess registries) {
        Holder<Enchantment> holder = holder(rolled.ability(), registries);
        EnchantmentHelper.updateEnchantments(stack, mutable -> {
            mutable.removeIf(h -> h.is(SPECIAL));
            mutable.set(holder, rolled.level());
        });
    }

    /** 도구에 붙은 해당 능력의 레벨. 없으면 0. */
    public static int level(ItemStack stack, Ability ability, RegistryAccess registries) {
        return EnchantmentHelper.getItemEnchantmentLevel(holder(ability, registries), stack);
    }

    private static Holder<Enchantment> holder(Ability ability, RegistryAccess registries) {
        return registries.registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(ability.key());
    }
}
