package org.gettserver.gettcore.editor.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.ClientHooks;
import org.gettserver.gettcore.GettCore;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * Renders item icons to PNG off-screen, the way the inventory draws them. Client only.
 *
 * <p>Any thread may call {@link #png}; the request is queued and {@link #processQueue()} renders up to
 * {@link #MAX_PER_TICK} of them per client tick on the render thread into a 32x32 {@link TextureTarget}.
 * Results (including failures, as empty) are cached in memory until {@link #clear()}.
 */
public final class IconRenderer {
    static final int SIZE = 32;
    static final int MAX_PER_TICK = 64;

    private record Request(ResourceLocation id, CompletableFuture<Optional<byte[]>> future) {}

    private static final Map<ResourceLocation, CompletableFuture<Optional<byte[]>>> CACHE = new ConcurrentHashMap<>();
    private static final Queue<Request> QUEUE = new ConcurrentLinkedQueue<>();

    private IconRenderer() {}

    public static CompletableFuture<Optional<byte[]>> png(ResourceLocation item) {
        return CACHE.computeIfAbsent(item, id -> {
            CompletableFuture<Optional<byte[]>> f = new CompletableFuture<>();
            if (!BuiltInRegistries.ITEM.containsKey(id) || BuiltInRegistries.ITEM.get(id) == Items.AIR) {
                f.complete(Optional.empty());
            } else {
                QUEUE.add(new Request(id, f));
            }
            return f;
        });
    }

    /** Drops cached icons. Already-queued requests still get rendered and answered. */
    public static void clear() {
        CACHE.clear();
    }

    /** Render thread only (client tick). Restores framebuffer, viewport, projection and model-view afterwards. */
    static void processQueue() {
        if (QUEUE.isEmpty()) return;
        RenderSystem.assertOnRenderThread();
        Minecraft mc = Minecraft.getInstance();
        Matrix4f savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        TextureTarget target = null;
        try {
            target = new TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX);
            target.setClearColor(0f, 0f, 0f, 0f);
            // Same setup as the GUI pass in GameRenderer.render, but 16 GUI units span the 32 pixel target.
            float far = ClientHooks.getGuiFarPlane();
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0f, 16f, 16f, 0f, 1000f, far),
                    VertexSorting.ORTHOGRAPHIC_Z);
            modelView.translation(0f, 0f, 10000f - far);
            RenderSystem.applyModelViewMatrix();
            Lighting.setupFor3DItems();
            for (int i = 0; i < MAX_PER_TICK; i++) {
                Request r = QUEUE.poll();
                if (r == null) break;
                r.future().complete(renderOne(mc, target, r.id()));
            }
        } catch (RuntimeException e) {
            // Setup failed (e.g. framebuffer creation): answer everything queued as "no icon" instead of
            // retrying and logging every tick.
            GettCore.LOGGER.warn("editor icon batch failed", e);
            for (Request r; (r = QUEUE.poll()) != null; ) r.future().complete(Optional.empty());
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
            if (target != null) target.destroyBuffers();
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    private static Optional<byte[]> renderOne(Minecraft mc, TextureTarget target, ResourceLocation id) {
        try {
            Item item = BuiltInRegistries.ITEM.get(id);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            g.renderFakeItem(new ItemStack(item), 0, 0);
            g.flush();
            try (NativeImage img = new NativeImage(SIZE, SIZE, false)) {
                RenderSystem.bindTexture(target.getColorTextureId());
                img.downloadTexture(0, false);
                img.flipY();
                return Optional.of(img.asByteArray());
            } finally {
                RenderSystem.bindTexture(0);
            }
        } catch (Exception e) {
            GettCore.LOGGER.warn("editor icon for {} could not be rendered: {}", id, e.toString());
            return Optional.empty();
        }
    }
}
