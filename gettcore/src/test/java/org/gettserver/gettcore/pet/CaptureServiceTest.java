package org.gettserver.gettcore.pet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.gettserver.gettcore.pet.CaptureRules.Verdict;
import org.junit.jupiter.api.Test;

class CaptureServiceTest {

    @Test
    void messageKeyPerVerdict() {
        assertEquals("gettcore.msg.capture.not_capturable", CaptureService.messageKey(Verdict.NOT_CAPTURABLE));
        assertEquals("gettcore.msg.capture.already_owned", CaptureService.messageKey(Verdict.ALREADY_OWNED));
        assertEquals("gettcore.msg.capture.health_out_of_range", CaptureService.messageKey(Verdict.HEALTH_OUT_OF_RANGE));
        assertEquals("gettcore.msg.capture.failed", CaptureService.messageKey(Verdict.FAILED));
        assertEquals("gettcore.msg.capture.captured", CaptureService.messageKey(Verdict.CAPTURED));
    }

    @Test
    void capturedStackCarriesKeyAndBoss() {
        PetKey key = new PetKey("naturalist:bear", "brown");
        ItemStack stack = CaptureService.capturedStack(key, true);
        assertTrue(stack.is(PetRegistry.CAPTURED_MOB.get()));
        assertEquals(1, stack.getCount());
        assertEquals(key, stack.get(PetRegistry.PET_KEY.get()));
        assertEquals(Boolean.TRUE, stack.get(PetRegistry.PET_BOSS.get()));

        ItemStack normal = CaptureService.capturedStack(new PetKey("minecraft:pig", "default"), false);
        assertEquals(Boolean.FALSE, normal.get(PetRegistry.PET_BOSS.get()));
    }

    @Test
    void confirmIgnoresWrongItem() {
        PetKey key = new PetKey("naturalist:bear", "brown");
        assertFalse(CaptureService.canConfirm(new ItemStack(Items.STONE)));
        assertFalse(CaptureService.canConfirm(ItemStack.EMPTY));
        assertFalse(CaptureService.canConfirm(new ItemStack(PetRegistry.CAPTURED_MOB.get())), "no pet key");
        assertTrue(CaptureService.canConfirm(CaptureService.capturedStack(key, false)));
    }

    @Test
    void obtainOutcomeMessageKeys() {
        assertEquals("gettcore.msg.obtain.success", CaptureService.obtainMessageKey(true, false));
        assertEquals("gettcore.msg.obtain.duplicate", CaptureService.obtainMessageKey(true, true));
        assertEquals("gettcore.msg.obtain.escaped", CaptureService.obtainMessageKey(false, false));
    }
}
