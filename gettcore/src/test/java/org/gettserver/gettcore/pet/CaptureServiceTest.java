package org.gettserver.gettcore.pet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.item.ItemStack;
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
}
