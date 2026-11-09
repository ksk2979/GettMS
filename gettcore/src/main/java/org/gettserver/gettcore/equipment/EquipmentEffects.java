package org.gettserver.gettcore.equipment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.util.thread.EffectiveSide;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.event.ItemAttributeModifierEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.equipment.EquipmentConfig.Values;
import org.gettserver.gettcore.equipment.EquipmentRules.Mod;
import org.gettserver.gettcore.equipment.EquipmentRules.Stat;

/**
 * Applies equipment.json in {@link ItemAttributeModifierEvent} (any mod's item, both sides: the client uses the synced
 * copy so tooltips match). Runs at LOWEST priority; replaces only the item's own (default) modifiers of an overridden
 * stat — modifiers other mods add in this event and gettcore's own (reinforce) are kept, see {@link EquipmentRules}.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class EquipmentEffects {
    /** Set while collecting a stack's modifiers without the overrides (reload refresh). */
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);

    private EquipmentEffects() {}

    /** Current side's config: the client (render thread) uses the synced copy, the server GettData. */
    public static EquipmentConfig config() {
        boolean client = FMLEnvironment.dist.isClient() && EffectiveSide.get().isClient();
        return client ? ClientEquipmentConfig.get() : GettData.get().equipment();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onItemAttributes(ItemAttributeModifierEvent event) {
        if (BYPASS.get()) return;
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        EquipmentConfig cfg = config();
        if (cfg.items().isEmpty()) return;
        Optional<Values> values = cfg.get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        if (values.isEmpty()) return;
        List<Mod> before = toMods(event.getModifiers());
        // The item's own modifiers (its component, before any event handler): only these are replaced, so bonuses
        // other mods add through this event stay on top of the override.
        java.util.Set<String> own = new java.util.HashSet<>();
        for (Mod m : toMods(event.getDefaultModifiers().modifiers())) own.add(m.stat() + "|" + m.id());
        List<Mod> after = EquipmentRules.apply(before, m -> own.contains(m.stat() + "|" + m.id()), values.get(),
                stat -> fallbackGroup(stack));
        EquipmentRules.Diff diff = EquipmentRules.diff(before, after);
        for (Mod m : diff.removed()) event.removeModifier(holder(m.stat()), m.id());
        for (Mod m : diff.added()) {
            event.replaceModifier(holder(m.stat()), new AttributeModifier(m.id(), m.amount(), m.operation()),
                    m.group());
        }
    }

    /** Editor catalog row for {@code stack} from its modifiers without equipment.json (null = not equipment). */
    public static EquipmentRules.Gear describe(ItemStack stack) {
        List<Mod> mods;
        BYPASS.set(true);
        try {
            mods = toMods(stack.getAttributeModifiers().modifiers());
        } finally {
            BYPASS.set(false);
        }
        EquipmentSlot armorSlot = stack.getItem() instanceof ArmorItem armor ? armor.getEquipmentSlot() : null;
        return EquipmentRules.describe(mods, armorSlot, isShield(stack));
    }

    private static boolean isShield(ItemStack stack) {
        return stack.getItem() instanceof ShieldItem || stack.canPerformAction(ItemAbilities.SHIELD_BLOCK);
    }

    private static net.minecraft.world.entity.EquipmentSlotGroup fallbackGroup(ItemStack stack) {
        EquipmentSlot armorSlot = stack.getItem() instanceof ArmorItem armor ? armor.getEquipmentSlot() : null;
        return EquipmentRules.fallbackGroup(armorSlot, isShield(stack));
    }

    private static List<Mod> toMods(List<ItemAttributeModifiers.Entry> entries) {
        List<Mod> out = new ArrayList<>();
        for (ItemAttributeModifiers.Entry e : entries) {
            Stat stat = stat(e.attribute());
            if (stat == null) continue;
            AttributeModifier m = e.modifier();
            out.add(new Mod(stat, m.id(), m.amount(), m.operation(), e.slot()));
        }
        return out;
    }

    /** On reload (/gett reload, editor save) re-apply worn equipment so players get the new values at once. */
    public static void registerReloadHook() {
        GettData.addReloadHook(() -> {
            try {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server == null) return;
                for (ServerPlayer p : server.getPlayerList().getPlayers()) refreshEquipped(p);
            } catch (RuntimeException e) {
                GettCore.LOGGER.error("gettcore: failed refreshing equipment overrides on reload", e);
            }
        });
    }

    /**
     * Vanilla only swaps item modifiers when the stack changes, so after a config change the player still carries the
     * old ones. Removes, for every worn stack, whatever ids it could have given (its modifiers without overrides plus
     * every override id) from the five attributes, then adds its current modifiers back. Two passes, so an id shared
     * by two slots is not lost.
     */
    static void refreshEquipped(ServerPlayer player) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            BYPASS.set(true);
            try {
                stack.forEachModifier(slot, (attr, mod) -> {
                    if (stat(attr) == null) return;
                    AttributeInstance inst = player.getAttribute(attr);
                    if (inst != null) inst.removeModifier(mod.id());
                });
            } finally {
                BYPASS.set(false);
            }
        }
        for (Stat stat : Stat.values()) {
            AttributeInstance inst = player.getAttribute(holder(stat));
            if (inst == null) continue;
            for (ResourceLocation id : EquipmentRules.possibleOverrideIds(stat)) inst.removeModifier(id);
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            stack.forEachModifier(slot, (attr, mod) -> {
                if (stat(attr) == null) return;
                AttributeInstance inst = player.getAttribute(attr);
                if (inst != null) inst.addOrUpdateTransientModifier(mod);
            });
        }
    }

    private static Stat stat(Holder<Attribute> attr) {
        Attribute a = attr.value();
        if (a == Attributes.ATTACK_DAMAGE.value()) return Stat.ATTACK_DAMAGE;
        if (a == Attributes.ATTACK_SPEED.value()) return Stat.ATTACK_SPEED;
        if (a == Attributes.ARMOR.value()) return Stat.ARMOR;
        if (a == Attributes.ARMOR_TOUGHNESS.value()) return Stat.ARMOR_TOUGHNESS;
        if (a == Attributes.KNOCKBACK_RESISTANCE.value()) return Stat.KNOCKBACK_RESISTANCE;
        return null;
    }

    private static Holder<Attribute> holder(Stat stat) {
        return switch (stat) {
            case ATTACK_DAMAGE -> Attributes.ATTACK_DAMAGE;
            case ATTACK_SPEED -> Attributes.ATTACK_SPEED;
            case ARMOR -> Attributes.ARMOR;
            case ARMOR_TOUGHNESS -> Attributes.ARMOR_TOUGHNESS;
            case KNOCKBACK_RESISTANCE -> Attributes.KNOCKBACK_RESISTANCE;
        };
    }
}
