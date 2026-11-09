package org.gettserver.gettcore.invsee;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.entity.EquipmentSlot;
import org.gettserver.gettcore.invsee.InvSeeLayout.Kind;
import org.junit.jupiter.api.Test;

class InvSeeLayoutTest {
    @Test
    void sizeMatchesVanillaNineByFive() {
        assertEquals(45, InvSeeLayout.SIZE);
    }

    @Test
    void topThreeRowsAreMainStorage() {
        for (int s = 0; s < 27; s++) {
            assertEquals(Kind.MAIN, InvSeeLayout.kind(s));
            assertEquals(9 + s, InvSeeLayout.inventoryIndex(s));
        }
    }

    @Test
    void fourthRowIsHotbar() {
        for (int s = 27; s < 36; s++) {
            assertEquals(Kind.MAIN, InvSeeLayout.kind(s));
            assertEquals(s - 27, InvSeeLayout.inventoryIndex(s));
        }
    }

    @Test
    void lastRowArmorHeadToFeetThenOffhand() {
        assertEquals(Kind.ARMOR, InvSeeLayout.kind(36));
        assertEquals(39, InvSeeLayout.inventoryIndex(36));
        assertEquals(EquipmentSlot.HEAD, InvSeeLayout.armorSlot(36));
        assertEquals(38, InvSeeLayout.inventoryIndex(37));
        assertEquals(EquipmentSlot.CHEST, InvSeeLayout.armorSlot(37));
        assertEquals(37, InvSeeLayout.inventoryIndex(38));
        assertEquals(EquipmentSlot.LEGS, InvSeeLayout.armorSlot(38));
        assertEquals(36, InvSeeLayout.inventoryIndex(39));
        assertEquals(EquipmentSlot.FEET, InvSeeLayout.armorSlot(39));
        assertEquals(Kind.OFFHAND, InvSeeLayout.kind(40));
        assertEquals(40, InvSeeLayout.inventoryIndex(40));
    }

    @Test
    void restIsFiller() {
        for (int s = 41; s < 45; s++) {
            assertEquals(Kind.FILLER, InvSeeLayout.kind(s));
            assertEquals(-1, InvSeeLayout.inventoryIndex(s));
            assertNull(InvSeeLayout.armorSlot(s));
        }
    }

    @Test
    void nonArmorSlotsHaveNoEquipmentSlot() {
        assertNull(InvSeeLayout.armorSlot(0));
        assertNull(InvSeeLayout.armorSlot(40));
    }

    @Test
    void everyTargetIndexAppearsExactlyOnce() {
        Set<Integer> seen = new HashSet<>();
        for (int s = 0; s < 45; s++) {
            int i = InvSeeLayout.inventoryIndex(s);
            if (i >= 0) assertTrue(seen.add(i), "duplicate index " + i);
        }
        assertEquals(41, seen.size()); // 36 items + 4 armor + offhand
        for (int i = 0; i <= 40; i++) assertTrue(seen.contains(i));
    }

    @Test
    void outOfRangeRejected() {
        assertThrows(IllegalArgumentException.class, () -> InvSeeLayout.kind(-1));
        assertThrows(IllegalArgumentException.class, () -> InvSeeLayout.kind(45));
    }

    @Test
    void quickMoveIntoTargetTriesArmorBeforeMain() {
        // [start, end) ranges of container slots, in the order quickMoveStack tries them
        assertArrayEquals(new int[][] {{36, 40}, {27, 36}, {0, 27}}, InvSeeLayout.QUICK_MOVE_INTO_TARGET);
    }
}
