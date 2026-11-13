package org.gettserver.gettcore.pet.client;

import com.crispytwig.naturalist.client.model.animal.bear.BearModel;
import com.crispytwig.naturalist.world.entity.animal.bear.Bear;
import net.minecraft.client.model.geom.ModelPart;
import org.gettserver.gettcore.pet.boss.BossBear;

/**
 * Naturalist's bear model plus the boss bear's head pose for the roar (곰라텐세) and rush (돌격) wind-ups. The pitch is
 * added to the neck's rotation after {@code super} has posed the whole bear, so Naturalist's walk, run and attack
 * animations stay intact. Client only.
 */
public class BossBearModel extends BearModel {
    private final ModelPart neckPart;

    public BossBearModel(ModelPart root) {
        super(root);
        this.neckPart = getAnyDescendantWithName("neck").orElse(null);
    }

    @Override
    protected void setupAnimations(Bear entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                                   float partialTick, float netHeadYaw, float headPitch) {
        super.setupAnimations(entity, limbSwing, limbSwingAmount, ageInTicks, partialTick, netHeadYaw, headPitch);
        if (neckPart != null && entity instanceof BossBear boss) {
            float pitch = boss.headPitchOffset(partialTick);
            if (pitch != 0.0F) rotatePart(neckPart, pitch, 0.0F, 0.0F);
        }
    }
}
