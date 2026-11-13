package org.gettserver.gettcore.enchant;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
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

    public enum Tool { HOE, PICKAXE, AXE, SWORD, BOW, FISHING_ROD }

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
        TREASURE("treasure", Tool.FISHING_ROD, 3);

        private final String id;
        private final Tool tool;
        private final int maxLevel;
        private final ResourceKey<Enchantment> key;

        Ability(String id, Tool tool, int maxLevel) {
            this.id = id;
            this.tool = tool;
            this.maxLevel = maxLevel;
            this.key = ResourceKey.create(Registries.ENCHANTMENT, GettCore.id(id));
        }

        public String id() { return id; }
        public Tool tool() { return tool; }
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
    }

    private SpecialEnchants() {}

    public static List<Ability> pool(Tool tool) {
        List<Ability> list = new ArrayList<>();
        for (Ability a : Ability.values()) {
            if (a.tool == tool) list.add(a);
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
        return Optional.empty();
    }

    /** 풀 안에서 능력은 균등, 3레벨 능력만 레벨 가중치로 레벨을 고른다. 가중치 합이 0 이하이면 레벨 1. */
    public static Rolled roll(Tool tool, LevelWeights weights, RandomSource random) {
        List<Ability> pool = pool(tool);
        Ability ability = pool.get(random.nextInt(pool.size()));
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
