package org.gettserver.gettcore.pet.client;

import com.crispytwig.naturalist.client.model.animal.bear.BearBabyModel;
import com.crispytwig.naturalist.client.model.animal.bear.BearModel;
import com.crispytwig.naturalist.client.renderer.entity.NaturalistMobRenderer;
import com.crispytwig.naturalist.world.entity.animal.bear.Bear;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

/**
 * The boss bear: Naturalist's BearRenderer setup (same model layers, 0.9 shadow) with {@link BossBearModel} as the
 * adult model, scaled to the boss size. BearRenderer keeps its models private, hence not a subclass of it; its
 * held-item and dye layers are left out (the boss holds nothing and is never dyed). Client only.
 */
public class BossBearRenderer extends NaturalistMobRenderer<Bear> {
    private static final float SHADOW_RADIUS = 0.9F;

    public BossBearRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new BossBearModel(ctx.bakeLayer(BearModel.LAYER_LOCATION)),
                new BearBabyModel(ctx.bakeLayer(BearBabyModel.LAYER_LOCATION)), SHADOW_RADIUS);
    }

    @Override
    protected void scale(Bear entity, PoseStack poseStack, float partialTick) {
        PetRenderers.applyScale(entity, poseStack);
    }
}
