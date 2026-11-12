package org.gettserver.gettcore.pet.client;

import com.crispytwig.naturalist.client.model.animal.giraffe.GiraffeBabyModel;
import com.crispytwig.naturalist.client.model.animal.giraffe.GiraffeModel;
import com.crispytwig.naturalist.client.renderer.entity.NaturalistMobRenderer;
import com.crispytwig.naturalist.world.entity.animal.giraffe.Giraffe;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

/**
 * The boss giraffe: Naturalist's GiraffeRenderer setup (same model layers, 1.1 shadow) with {@link BossGiraffeModel} as
 * the adult model, scaled to the boss size. GiraffeRenderer keeps its models private, hence not a subclass of it;
 * its seated-rider layer is left out (the boss cannot be ridden). Client only.
 */
public class BossGiraffeRenderer extends NaturalistMobRenderer<Giraffe> {
    private static final float SHADOW_RADIUS = 1.1F;

    public BossGiraffeRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new BossGiraffeModel(ctx.bakeLayer(GiraffeModel.LAYER_LOCATION)),
                new GiraffeBabyModel(ctx.bakeLayer(GiraffeBabyModel.LAYER_LOCATION)), SHADOW_RADIUS);
    }

    @Override
    protected void scale(Giraffe entity, PoseStack poseStack, float partialTick) {
        PetRenderers.applyScale(entity, poseStack);
    }
}
