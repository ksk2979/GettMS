package org.gettserver.gettcore.equipment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import org.gettserver.gettcore.equipment.EquipmentConfig.Values;

/**
 * Pure rules for equipment.json. For every overridden stat, every modifier of that stat that is not gettcore's own
 * (gettcore namespace, not {@code equip_*}, e.g. {@code gettcore:reinforce_melee}) is removed and one ADD_VALUE
 * modifier with the tooltip value minus the player's base is added in the slot group the item already used for that
 * stat (else the group of its other stats, else the caller's fallback). The result does not depend on whether the
 * reinforce modifiers were added before or after, and applying it twice changes nothing.
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

    /** {@code mods} after the overrides in {@code values} (null / empty = unchanged). */
    public static List<Mod> apply(List<Mod> mods, Values values, Function<Stat, EquipmentSlotGroup> fallback) {
        List<Mod> out = new ArrayList<>(mods);
        for (Stat stat : Stat.values()) {
            Optional<Double> v = value(values, stat);
            if (v.isEmpty()) continue;
            EquipmentSlotGroup group = groupFor(mods, stat, fallback);
            out.removeIf(m -> m.stat() == stat && !isKept(m.id()));
            out.add(new Mod(stat, overrideId(stat, group), amount(stat, v.get()), Operation.ADD_VALUE, group));
        }
        return out;
    }

    /** Group of the first replaceable modifier of {@code stat}, else of any stat, else the fallback. */
    static EquipmentSlotGroup groupFor(List<Mod> mods, Stat stat, Function<Stat, EquipmentSlotGroup> fallback) {
        for (Mod m : mods) if (m.stat() == stat && !isKept(m.id())) return m.group();
        for (Mod m : mods) if (!isKept(m.id())) return m.group();
        return fallback.apply(stat);
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
