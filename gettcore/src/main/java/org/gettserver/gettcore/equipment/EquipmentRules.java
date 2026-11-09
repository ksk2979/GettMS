package org.gettserver.gettcore.equipment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import org.gettserver.gettcore.equipment.EquipmentConfig.Values;

/**
 * Pure rules for equipment.json. For every overridden stat, the item's own modifiers of that stat (the ones the item
 * declares itself — its default modifiers — plus vanilla base ids and our {@code equip_*} ids) are removed and one
 * ADD_VALUE
 * modifier with the tooltip value minus the player's base is added in the slot group the item already used for that
 * stat (else the group of its other stats, else the caller's fallback). The result does not depend on whether the
 * reinforce modifiers were added before or after, and applying it twice changes nothing. Modifiers other mods add
 * through ItemAttributeModifierEvent (affixes, set bonuses, ...) and gettcore's own (reinforce) stay and stack on top.
 * Epic Fight's damage_bonus / speed_bonus are not item modifiers at all (Epic Fight adds them straight to the player
 * on equipment change, ids epicfight:attack_damage / attack_speed), so they always add on top as well.
 */
public final class EquipmentRules {
    private EquipmentRules() {}

    /** Attributes equipment.json can override, with the player's base value (tooltip value = base + modifiers). */
    public enum Stat {
        ATTACK_DAMAGE(1.0), ATTACK_SPEED(4.0), ARMOR(0), ARMOR_TOUGHNESS(0), KNOCKBACK_RESISTANCE(0);

        private final double playerBase;

        Stat(double playerBase) {
            this.playerBase = playerBase;
        }

        public double playerBase() {
            return playerBase;
        }
    }

    /** One item attribute modifier, without registry holders. */
    public record Mod(Stat stat, ResourceLocation id, double amount, Operation operation, EquipmentSlotGroup group) {}

    private static final ResourceLocation BASE_ATTACK_DAMAGE_ID =
            ResourceLocation.withDefaultNamespace("base_attack_damage");
    private static final ResourceLocation BASE_ATTACK_SPEED_ID =
            ResourceLocation.withDefaultNamespace("base_attack_speed");
    private static final String NAMESPACE = "gettcore";
    private static final String PREFIX = "equip_";

    public static Optional<Double> value(Values v, Stat stat) {
        if (v == null) return Optional.empty();
        return switch (stat) {
            case ATTACK_DAMAGE -> v.attackDamage();
            case ATTACK_SPEED -> v.attackSpeed();
            case ARMOR -> v.armor();
            case ARMOR_TOUGHNESS -> v.toughness();
            case KNOCKBACK_RESISTANCE -> v.knockbackResistance();
        };
    }

    /** Tooltip value → ADD_VALUE modifier amount (attack damage −1, attack speed −4, others as is). */
    public static double amount(Stat stat, double tooltipValue) {
        return tooltipValue - stat.playerBase();
    }

    /**
     * Id of the replacement modifier. Main hand attack damage/speed use vanilla's base ids, so the tooltip shows the
     * green "7 Attack Damage" line (with reinforce merged into it); everything else gets
     * {@code gettcore:equip_<stat>_<group>} so two worn pieces never share an id on the player.
     */
    public static ResourceLocation overrideId(Stat stat, EquipmentSlotGroup group) {
        if (group == EquipmentSlotGroup.MAINHAND) {
            if (stat == Stat.ATTACK_DAMAGE) return BASE_ATTACK_DAMAGE_ID;
            if (stat == Stat.ATTACK_SPEED) return BASE_ATTACK_SPEED_ID;
        }
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE,
                PREFIX + stat.name().toLowerCase(Locale.ROOT) + "_" + group.getSerializedName());
    }

    /** gettcore's other modifiers (reinforce etc.) stay; equip_* and every other mod's modifiers are replaceable. */
    public static boolean isKept(ResourceLocation id) {
        return id.getNamespace().equals(NAMESPACE) && !id.getPath().startsWith(PREFIX);
    }

    /** Ids that are always the item's base value: vanilla's base / armor ids and our override ids. */
    public static boolean isBaseId(ResourceLocation id) {
        if (id.equals(BASE_ATTACK_DAMAGE_ID) || id.equals(BASE_ATTACK_SPEED_ID)) return true;
        if (id.getNamespace().equals("minecraft") && id.getPath().startsWith("armor.")) return true;
        return id.getNamespace().equals(NAMESPACE) && id.getPath().startsWith(PREFIX);
    }

    /** {@link #apply(List, Predicate, Values, Function)} with every modifier counted as the item's own. */
    public static List<Mod> apply(List<Mod> mods, Values values, Function<Stat, EquipmentSlotGroup> fallback) {
        return apply(mods, m -> true, values, fallback);
    }

    /**
     * {@code mods} after the overrides in {@code values} (null / empty = unchanged). {@code own}: whether a modifier is
     * one the item declares itself (replaced); the others (added by other mods through the event) are kept.
     */
    public static List<Mod> apply(List<Mod> mods, Predicate<Mod> own, Values values,
                                  Function<Stat, EquipmentSlotGroup> fallback) {
        Predicate<Mod> replaceable = m -> !isKept(m.id()) && (isBaseId(m.id()) || own.test(m));
        List<Mod> out = new ArrayList<>(mods);
        for (Stat stat : Stat.values()) {
            Optional<Double> v = value(values, stat);
            if (v.isEmpty()) continue;
            EquipmentSlotGroup group = groupFor(mods, stat, replaceable, fallback);
            out.removeIf(m -> m.stat() == stat && replaceable.test(m));
            out.add(new Mod(stat, overrideId(stat, group), amount(stat, v.get()), Operation.ADD_VALUE, group));
        }
        return out;
    }

    /** Group of the first replaceable modifier of {@code stat}, else of any stat, else the fallback. */
    static EquipmentSlotGroup groupFor(List<Mod> mods, Stat stat, Predicate<Mod> replaceable,
                                       Function<Stat, EquipmentSlotGroup> fallback) {
        for (Mod m : mods) if (m.stat() == stat && replaceable.test(m)) return m.group();
        for (Mod m : mods) if (replaceable.test(m)) return m.group();
        return fallback.apply(stat);
    }

    /** What to remove from and add to the event (removals first: a replacement may reuse a removed id). */
    public record Diff(List<Mod> removed, List<Mod> added) {}

    public static Diff diff(List<Mod> before, List<Mod> after) {
        return new Diff(before.stream().filter(m -> !after.contains(m)).toList(),
                after.stream().filter(m -> !before.contains(m)).toList());
    }

    /**
     * Group for a stat the item has no modifier for at all: armor = its slot, shield = off hand, else main hand.
     * {@code armorSlot} is null for non-armor items.
     */
    public static EquipmentSlotGroup fallbackGroup(EquipmentSlot armorSlot, boolean shield) {
        if (armorSlot != null) return EquipmentSlotGroup.bySlot(armorSlot);
        return shield ? EquipmentSlotGroup.OFFHAND : EquipmentSlotGroup.MAINHAND;
    }

    /** Every id {@link #overrideId} can return for {@code stat} (to clear stale ones from players on reload). */
    public static List<ResourceLocation> possibleOverrideIds(Stat stat) {
        List<ResourceLocation> out = new ArrayList<>();
        for (EquipmentSlotGroup g : EquipmentSlotGroup.values()) {
            ResourceLocation id = overrideId(stat, g);
            if (!out.contains(id)) out.add(id);
        }
        return out;
    }

    /**
     * Editor catalog row: kind "melee" / "armor" / "shield", the slot group name it is evaluated in and the tooltip
     * value of each stat it has a modifier for (absent = none).
     */
    public record Gear(String kind, String slot, Map<Stat, Double> values) {}

    private static final Set<Stat> DEFENSE = EnumSet.of(Stat.ARMOR, Stat.ARMOR_TOUGHNESS, Stat.KNOCKBACK_RESISTANCE);

    /**
     * Classifies an item from its unmodified modifiers: shield first, then armor (ArmorItem slot, or no attack but
     * defense modifiers), then melee (has attack damage). Null = no attack or defense at all (not equipment).
     */
    public static Gear describe(List<Mod> mods, EquipmentSlot armorSlot, boolean shield) {
        String kind;
        EquipmentSlotGroup group;
        boolean attack = mods.stream().anyMatch(m -> m.stat() == Stat.ATTACK_DAMAGE);
        Optional<Mod> defense = mods.stream().filter(m -> DEFENSE.contains(m.stat())).findFirst();
        if (shield) {
            kind = "shield";
            group = EquipmentSlotGroup.OFFHAND;
        } else if (armorSlot != null) {
            kind = "armor";
            group = EquipmentSlotGroup.bySlot(armorSlot);
        } else if (attack) {
            kind = "melee";
            group = EquipmentSlotGroup.MAINHAND;
        } else if (defense.isPresent()) {
            kind = "armor";
            group = defense.get().group();
        } else {
            return null;
        }
        EquipmentSlot slot = representative(group);
        Map<Stat, Double> values = new EnumMap<>(Stat.class);
        for (Stat stat : Stat.values()) {
            if (mods.stream().anyMatch(m -> m.stat() == stat && m.group().test(slot))) {
                values.put(stat, finalValue(stat, mods, slot));
            }
        }
        return new Gear(kind, group.getSerializedName(), values);
    }

    private static EquipmentSlot representative(EquipmentSlotGroup group) {
        for (EquipmentSlot s : new EquipmentSlot[] {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.CHEST,
                EquipmentSlot.HEAD, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.BODY}) {
            if (group.test(s)) return s;
        }
        return EquipmentSlot.MAINHAND;
    }

    /** The attribute value a player wearing these modifiers in {@code slot} gets (vanilla formula). */
    public static double finalValue(Stat stat, Collection<Mod> mods, EquipmentSlot slot) {
        double base = stat.playerBase();
        for (Mod m : mods) {
            if (m.stat() == stat && m.group().test(slot) && m.operation() == Operation.ADD_VALUE) base += m.amount();
        }
        double value = base;
        for (Mod m : mods) {
            if (m.stat() == stat && m.group().test(slot) && m.operation() == Operation.ADD_MULTIPLIED_BASE) {
                value += base * m.amount();
            }
        }
        for (Mod m : mods) {
            if (m.stat() == stat && m.group().test(slot) && m.operation() == Operation.ADD_MULTIPLIED_TOTAL) {
                value *= 1 + m.amount();
            }
        }
        return value;
    }
}
