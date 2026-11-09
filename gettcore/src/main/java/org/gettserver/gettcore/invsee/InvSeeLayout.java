package org.gettserver.gettcore.invsee;

import net.minecraft.world.entity.EquipmentSlot;

/**
 * Which part of the target's Inventory each of the 45 slots of the /인벤 (9x5) screen shows.
 * Indices are Inventory container indices: 0-8 hotbar, 9-35 storage, 36-39 armor (36 = feet .. 39 = head),
 * 40 offhand. Rows: 0-2 storage, 3 hotbar, 4 = head, chest, legs, feet, offhand, then 4 inert fillers.
 */
public final class InvSeeLayout {
    public static final int SIZE = 45;
    private static final int OFFHAND_SLOT = 40;
    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** [start, end) container slot ranges, tried in order when shift-clicking from the viewer's own inventory. */
    public static final int[][] QUICK_MOVE_INTO_TARGET = {{36, 40}, {27, 36}, {0, 27}};

    public enum Kind { MAIN, ARMOR, OFFHAND, FILLER }

    private InvSeeLayout() {}

    public static Kind kind(int slot) {
        if (slot < 0 || slot >= SIZE) throw new IllegalArgumentException("slot " + slot);
        if (slot < 36) return Kind.MAIN;
        if (slot < 40) return Kind.ARMOR;
        if (slot == OFFHAND_SLOT) return Kind.OFFHAND;
        return Kind.FILLER;
    }

    /** Target Inventory container index, or -1 for a filler slot. */
    public static int inventoryIndex(int slot) {
        return switch (kind(slot)) {
            case MAIN -> slot < 27 ? slot + 9 : slot - 27;
            case ARMOR -> 39 - (slot - 36);
            case OFFHAND -> 40;
            case FILLER -> -1;
        };
    }

    /** Equipment slot an ARMOR slot accepts, otherwise null. */
    public static EquipmentSlot armorSlot(int slot) {
        return kind(slot) == Kind.ARMOR ? ARMOR[slot - 36] : null;
    }
}
