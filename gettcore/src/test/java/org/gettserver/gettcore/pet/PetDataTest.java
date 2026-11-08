package org.gettserver.gettcore.pet;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class PetDataTest {
    private static final PetKey A = new PetKey("cat", "tabby");
    private static final PetKey B = new PetKey("dog", "");
    private static final PetKey C = new PetKey("cat", "black");

    @Test
    void addRejectsDuplicate() {
        PetData data = new PetData();
        UUID p = UUID.randomUUID();
        assertTrue(data.add(p, A));
        assertFalse(data.add(p, A));
        assertEquals(List.of(A), data.owned(p));
    }

    @Test
    void ownedPreservesOrder() {
        PetData data = new PetData();
        UUID p = UUID.randomUUID();
        data.add(p, B);
        data.add(p, A);
        data.add(p, C);
        assertEquals(List.of(B, A, C), data.owned(p));
    }

    @Test
    void ownedIsUnmodifiable() {
        PetData data = new PetData();
        UUID p = UUID.randomUUID();
        data.add(p, A);
        assertThrows(UnsupportedOperationException.class, () -> data.owned(p).add(B));
        assertTrue(data.owned(UUID.randomUUID()).isEmpty());
    }

    @Test
    void activeEmptyForUnknownPlayer() {
        assertEquals(Optional.empty(), new PetData().active(UUID.randomUUID()));
    }

    @Test
    void saveLoadRoundTrip() {
        PetData data = new PetData();
        UUID p = UUID.randomUUID();
        UUID q = UUID.randomUUID();
        data.add(p, B);
        data.add(p, A);
        data.setActive(p, A);
        data.add(q, C);
        PetData loaded = PetData.load(data.save(new CompoundTag(), null), null);
        assertEquals(List.of(B, A), loaded.owned(p));
        assertEquals(Optional.of(A), loaded.active(p));
        assertEquals(List.of(C), loaded.owned(q));
        assertEquals(Optional.empty(), loaded.active(q));
        loaded.setActive(p, null);
        assertEquals(Optional.empty(), loaded.active(p));
    }

    @Test
    void removeDeletesAndClearsActive() {
        PetData data = new PetData();
        UUID p = UUID.randomUUID();
        data.add(p, A);
        data.add(p, B);
        data.setActive(p, A);
        assertTrue(data.remove(p, A));
        assertEquals(List.of(B), data.owned(p));
        assertEquals(Optional.empty(), data.active(p));
        assertTrue(data.isDirty());
    }

    @Test
    void removeKeepsOtherActive() {
        PetData data = new PetData();
        UUID p = UUID.randomUUID();
        data.add(p, A);
        data.add(p, B);
        data.setActive(p, B);
        assertTrue(data.remove(p, A));
        assertEquals(Optional.of(B), data.active(p));
    }

    @Test
    void removeUnknownReturnsFalse() {
        PetData data = new PetData();
        UUID p = UUID.randomUUID();
        assertFalse(data.remove(p, A));
        data.add(p, B);
        assertFalse(data.remove(p, A));
        assertEquals(List.of(B), data.owned(p));
    }
}
