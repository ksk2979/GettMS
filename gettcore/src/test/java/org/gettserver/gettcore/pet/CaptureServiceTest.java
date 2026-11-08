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
        ItemStack stack = CaptureService.capturedStack(key, true, CaptureTier.DIAMOND);
        assertTrue(stack.is(PetRegistry.CAPTURED_MOB.get()));
        assertEquals(1, stack.getCount());
        assertEquals(key, stack.get(PetRegistry.PET_KEY.get()));
        assertEquals(Boolean.TRUE, stack.get(PetRegistry.PET_BOSS.get()));
        assertEquals(CaptureTier.DIAMOND, stack.get(PetRegistry.PET_TIER.get()));
        assertEquals(CaptureTier.DIAMOND, CaptureService.tierOf(stack));

        ItemStack normal = CaptureService.capturedStack(new PetKey("minecraft:pig", "default"), false,
                CaptureTier.NETHERITE);
        assertEquals(Boolean.FALSE, normal.get(PetRegistry.PET_BOSS.get()));
        assertEquals(CaptureTier.NETHERITE, CaptureService.tierOf(normal));
    }

    @Test
    void stackWithoutTierCountsAsIron() {
        ItemStack old = new ItemStack(PetRegistry.CAPTURED_MOB.get());
        old.set(PetRegistry.PET_KEY.get(), new PetKey("naturalist:bear", "brown"));
        assertFalse(old.has(PetRegistry.PET_TIER.get()));
        assertEquals(CaptureTier.IRON, CaptureService.tierOf(old));
    }

    @Test
    void confirmIgnoresWrongItem() {
        PetKey key = new PetKey("naturalist:bear", "brown");
        assertFalse(CaptureService.canConfirm(new ItemStack(Items.STONE)));
        assertFalse(CaptureService.canConfirm(ItemStack.EMPTY));
        assertFalse(CaptureService.canConfirm(new ItemStack(PetRegistry.CAPTURED_MOB.get())), "no pet key");
        assertTrue(CaptureService.canConfirm(CaptureService.capturedStack(key, false, CaptureTier.IRON)));
    }

    @Test
    void obtainOutcomeMessageKeys() {
        assertEquals("gettcore.msg.obtain.success", CaptureService.obtainMessageKey(true, false));
        assertEquals("gettcore.msg.obtain.duplicate", CaptureService.obtainMessageKey(true, true));
        assertEquals("gettcore.msg.obtain.escaped", CaptureService.obtainMessageKey(false, false));
    }

    @Test
    void canThrowOnlyBallOffCooldown() {
        ItemStack ball = new ItemStack(PetRegistry.CAPTURE_BALL_IRON.get());
        assertTrue(CaptureService.canThrow(ball, false));
        assertFalse(CaptureService.canThrow(ball, true), "on cooldown");
        assertTrue(CaptureService.canThrow(new ItemStack(PetRegistry.CAPTURE_BALL_NETHERITE.get()), false));
        assertFalse(CaptureService.canThrow(new ItemStack(Items.STONE), false), "not a ball");
        assertFalse(CaptureService.canThrow(ItemStack.EMPTY, false), "empty hand");
    }

    @Test
    void ballItemsListsAllFourTiers() {
        assertEquals(4, CaptureService.ballItems().size());
    }
}
