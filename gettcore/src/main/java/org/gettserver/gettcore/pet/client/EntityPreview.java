package org.gettserver.gettcore.pet.client;

import com.crispytwig.naturalist.world.entity.variant.DataDrivenVariantAnimal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.gettserver.gettcore.GettCore;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Client-only: a throwaway entity (never added to the level) for GUI previews, and its rendering. */
public final class EntityPreview {
    private EntityPreview() {}

    /** A fresh client entity of {@code wildEntity} with {@code variant} applied; null if it cannot be made. */
    public static @Nullable LivingEntity create(ResourceLocation wildEntity, String variant) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        try {
            EntityType<?> type = EntityType.byString(wildEntity.toString()).orElse(null);
            if (type == null) return null;
            Entity entity = type.create(mc.level);
            if (!(entity instanceof LivingEntity living)) {
                if (entity != null) entity.discard();
                return null;
            }
            if (living instanceof DataDrivenVariantAnimal v && !variant.isEmpty() && !"default".equals(variant)) {
                v.setVariantString(variant);
            }
            return living;
        } catch (Exception e) {
            GettCore.LOGGER.warn("gettcore: cannot create preview entity {} [{}]", wildEntity, variant, e);
            return null;
        }
    }

    /**
     * Draws {@code e} centred in the box, {@code scale} GUI pixels per block, turned to {@code yawDegrees}
     * (180 faces the viewer). Null draws a "?" placeholder.
     */
    public static void render(GuiGraphics g, int x1, int y1, int x2, int y2, float scale, @Nullable LivingEntity e,
                              float yawDegrees) {
        if (e == null) {
            Font font = Minecraft.getInstance().font;
            g.pose().pushPose();
            g.pose().translate((x1 + x2) / 2.0F, (y1 + y2) / 2.0F, 0);
            g.pose().scale(4, 4, 1);
            g.drawString(font, "?", -font.width("?") / 2, -font.lineHeight / 2, 0xFFAAAAAA, false);
            g.pose().popPose();
            return;
        }
        float bodyRot = e.yBodyRot, bodyRotO = e.yBodyRotO, yRot = e.getYRot(), yRotO = e.yRotO, xRot = e.getXRot();
        float headRot = e.yHeadRot, headRotO = e.yHeadRotO;
        e.yBodyRot = e.yBodyRotO = yawDegrees;
        e.setYRot(yawDegrees);
        e.yRotO = yawDegrees;
        e.setXRot(0);
        e.yHeadRot = e.yHeadRotO = yawDegrees;

        g.enableScissor(x1, y1, x2, y2);
        float entityScale = e.getScale() > 0 ? e.getScale() : 1.0F;
        Vector3f translate = new Vector3f(0.0F, e.getBbHeight() / 2.0F, 0.0F);
        Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
        InventoryScreen.renderEntityInInventory(g, (x1 + x2) / 2.0F, (y1 + y2) / 2.0F, scale / entityScale,
                translate, pose, null, e);
        g.disableScissor();

        e.yBodyRot = bodyRot;
        e.yBodyRotO = bodyRotO;
        e.setYRot(yRot);
        e.yRotO = yRotO;
        e.setXRot(xRot);
        e.yHeadRot = headRot;
        e.yHeadRotO = headRotO;
    }
}
