package org.gettserver.gettcore.pet.client;

import com.crispytwig.naturalist.client.renderer.entity.BearRenderer;
import com.crispytwig.naturalist.client.renderer.entity.BlackBearRenderer;
import com.crispytwig.naturalist.client.renderer.entity.ElephantRenderer;
import com.crispytwig.naturalist.client.renderer.entity.GiraffeRenderer;
import com.crispytwig.naturalist.client.renderer.entity.HippoRenderer;
import com.crispytwig.naturalist.client.renderer.entity.LionRenderer;
import com.crispytwig.naturalist.client.renderer.entity.MammothRenderer;
import com.crispytwig.naturalist.client.renderer.entity.OstrichRenderer;
import com.crispytwig.naturalist.client.renderer.entity.TigerRenderer;
import com.crispytwig.naturalist.client.renderer.entity.VultureRenderer;
import com.crispytwig.naturalist.world.entity.animal.bear.Bear;
import com.crispytwig.naturalist.world.entity.animal.bear.BlackBear;
import com.crispytwig.naturalist.world.entity.animal.elephant.Elephant;
import com.crispytwig.naturalist.world.entity.animal.giraffe.Giraffe;
import com.crispytwig.naturalist.world.entity.animal.hippo.Hippo;
import com.crispytwig.naturalist.world.entity.animal.lion.Lion;
import com.crispytwig.naturalist.world.entity.animal.ostrich.Ostrich;
import com.crispytwig.naturalist.world.entity.animal.tiger.Tiger;
import com.crispytwig.naturalist.world.entity.animal.vulture.Vulture;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.gettserver.gettcore.pet.PetRegistry;
import org.gettserver.gettcore.pet.entity.GettPet;

/**
 * Pet renderers: the Naturalist renderer of each original, subclassed only to scale boss pets by their sizeScale
 * (the pet classes scale the hitbox to match). Client only.
 */
public final class PetRenderers {
    private PetRenderers() {}

    public static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(PetRegistry.PET_LION.get(), ctx -> new LionRenderer(ctx) {
            @Override
            protected void scale(Lion entity, PoseStack poseStack, float partialTick) {
                applyScale(entity, poseStack);
            }
        });
        event.registerEntityRenderer(PetRegistry.PET_TIGER.get(), ctx -> new TigerRenderer(ctx) {
            @Override
            protected void scale(Tiger entity, PoseStack poseStack, float partialTick) {
                applyScale(entity, poseStack);
            }
        });
        event.registerEntityRenderer(PetRegistry.PET_BEAR.get(), ctx -> new BearRenderer(ctx) {
            @Override
            protected void scale(Bear entity, PoseStack poseStack, float partialTick) {
                applyScale(entity, poseStack);
            }
        });
        event.registerEntityRenderer(PetRegistry.PET_BLACK_BEAR.get(), ctx -> new BlackBearRenderer(ctx) {
            @Override
            protected void scale(BlackBear entity, PoseStack poseStack, float partialTick) {
                applyScale(entity, poseStack);
            }
        });
        event.registerEntityRenderer(PetRegistry.PET_HIPPO.get(), ctx -> new HippoRenderer(ctx) {
            @Override
            protected void scale(Hippo entity, PoseStack poseStack, float partialTick) {
                applyScale(entity, poseStack);
            }
        });
        event.registerEntityRenderer(PetRegistry.PET_ELEPHANT.get(), ctx -> new ElephantRenderer(ctx) {
            @Override
            protected void scale(Elephant entity, PoseStack poseStack, float partialTick) {
                applyScale(entity, poseStack);
            }
        });
        // MammothRenderer is declared for Elephant (Mammoth's superclass).
        event.registerEntityRenderer(PetRegistry.PET_MAMMOTH.get(), ctx -> new MammothRenderer(ctx) {
            @Override
            protected void scale(Elephant entity, PoseStack poseStack, float partialTick) {
                applyScale(entity, poseStack);
            }
        });
        event.registerEntityRenderer(PetRegistry.PET_GIRAFFE.get(), ctx -> new GiraffeRenderer(ctx) {
            @Override
            protected void scale(Giraffe entity, PoseStack poseStack, float partialTick) {
                applyScale(entity, poseStack);
            }
        });
        event.registerEntityRenderer(PetRegistry.PET_OSTRICH.get(), ctx -> new OstrichRenderer(ctx) {
            @Override
            protected void scale(Ostrich entity, PoseStack poseStack, float partialTick) {
                applyScale(entity, poseStack);
            }
        });
        event.registerEntityRenderer(PetRegistry.PET_VULTURE.get(), ctx -> new VultureRenderer(ctx) {
            @Override
            protected void scale(Vulture entity, PoseStack poseStack, float partialTick) {
                applyScale(entity, poseStack);
            }
        });
    }

    /** Boss pets render at their species' sizeScale; everything else is left at 1. */
    private static void applyScale(Entity entity, PoseStack poseStack) {
        if (entity instanceof GettPet pet && pet.isBossPet()) {
            float s = pet.petState().scale();
            poseStack.scale(s, s, s);
        }
    }
}
