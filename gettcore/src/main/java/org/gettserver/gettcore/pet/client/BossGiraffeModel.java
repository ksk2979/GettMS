package org.gettserver.gettcore.pet.client;

import com.crispytwig.naturalist.client.model.animal.giraffe.GiraffeModel;
import com.crispytwig.naturalist.world.entity.animal.giraffe.Giraffe;
import net.minecraft.client.model.geom.ModelPart;
import org.gettserver.gettcore.pet.boss.BossGiraffe;

/**
 * Naturalist's giraffe model plus the boss stomp and 람각. The leg IK in {@code super} only moves the foot/hand
 * groups' y and the body/head pitch, so the arm rotations layered on afterwards are not overwritten. Client only.
 */
public class BossGiraffeModel extends GiraffeModel {
    public BossGiraffeModel(ModelPart root) {
        super(root);
    }

    @Override
    protected void setupAnimations(Giraffe entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                                   float partialTick, float netHeadYaw, float headPitch) {
        super.setupAnimations(entity, limbSwing, limbSwingAmount, ageInTicks, partialTick, netHeadYaw, headPitch);
        if (entity instanceof BossGiraffe boss) {
            animate(boss.stompAnimationState, BossGiraffeAnimation.STOMP, ageInTicks);
            animate(boss.ramgakAnimationState, BossGiraffeAnimation.RAMGAK, ageInTicks);
        }
    }
}
