package org.gettserver.gettcore.web.client;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.network.WebClingPayload;
import org.gettserver.gettcore.web.ClientWebConfig;
import org.gettserver.gettcore.web.WebConfig;
import org.gettserver.gettcore.web.WebRules;

/**
 * 벽 붙기(발사기를 찬 동안): 공중에서 벽에 닿으면 그 자리에 붙는다. 앞·뒤 키로 오르내리고 좌·우 키로 옆으로 가며,
 * 웅크리기 키로 놓고, 점프 키로 벽을 차고 뛴다. 꼭대기에 닿으면 위로 넘어간다. {@link WebClient} 의 틱에서 불린다.
 */
final class WallCling {
    /** 벽을 찾을 때 몸을 이만큼 밀어 본다(칸). */
    private static final double PROBE = 0.12;
    /** 벽을 차고 뛴 뒤 다시 붙을 수 있을 때까지(틱). */
    private static final int REGRAB_TICKS = 8;

    /** Players (entity ids) shown with the clinging pose, the local player included. */
    private static final Set<Integer> CLINGERS = new HashSet<>();
    private static boolean clinging;
    /** Unit vector from the player into the wall (x, z). */
    private static int wallX, wallZ;
    private static boolean sneakMustRelease;
    private static long noClingUntil = Long.MIN_VALUE;

    private WallCling() {}

    static boolean isClinging(int entityId) {
        return CLINGERS.contains(entityId);
    }

    static void onSync(int entityId, boolean on) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getId() == entityId) return; // mine is set locally
        if (on) CLINGERS.add(entityId);
        else CLINGERS.remove(entityId);
    }

    static void clear() {
        CLINGERS.clear();
        clinging = false;
        sneakMustRelease = false;
        noClingUntil = Long.MIN_VALUE;
    }

    /** Ends the local cling without a jump (shooter put away, line attached, death, ...). */
    static void stop(LocalPlayer player) {
        if (!clinging) return;
        clinging = false;
        CLINGERS.remove(player.getId());
        PacketDistributor.sendToServer(new WebClingPayload(false));
    }

    /** The upper half of the body pushed towards (dx, dz): a wall counts only if it reaches above the waist. */
    private static boolean wallAt(LocalPlayer player, int dx, int dz) {
        AABB box = player.getBoundingBox();
        AABB upper = new AABB(box.minX, box.minY + box.getYsize() * 0.55, box.minZ, box.maxX, box.maxY, box.maxZ)
                .move(dx * PROBE, 0, dz * PROBE);
        return !player.level().noCollision(player, upper);
    }

    /** One tick; {@code allowed} is false while this cannot apply (no shooter, line attached, riding, ...). */
    static void tick(Minecraft mc, LocalPlayer player, long now, boolean allowed, boolean jumpPressed) {
        WebConfig cfg = ClientWebConfig.get();
        boolean sneak = mc.options.keyShift.isDown();
        if (!sneak) sneakMustRelease = false;
        if (!allowed || !cfg.wallCling() || player.isInWater() || player.isFallFlying()
                || player.getAbilities().flying) {
            stop(player);
            return;
        }
        if (!clinging) {
            if (player.onGround() || !player.horizontalCollision || sneak || sneakMustRelease || now < noClingUntil) {
                return;
            }
            // The wall the player is looking at most directly among the four sides.
            Vec3 look = player.getViewVector(1.0f);
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            double best = -2;
            boolean found = false;
            for (int[] d : dirs) {
                double dot = look.x * d[0] + look.z * d[1];
                if (dot > best && wallAt(player, d[0], d[1])) {
                    best = dot;
                    wallX = d[0];
                    wallZ = d[1];
                    found = true;
                }
            }
            if (!found) return;
            clinging = true;
            CLINGERS.add(player.getId());
            PacketDistributor.sendToServer(new WebClingPayload(true));
        }

        if (player.onGround()) {
            stop(player);
            return;
        }
        if (sneak) {
            sneakMustRelease = true;
            stop(player);
            return;
        }
        if (jumpPressed) {
            double[] v = WebRules.wallJump(wallX, wallZ);
            player.setDeltaMovement(v[0], v[1], v[2]);
            noClingUntil = now + REGRAB_TICKS;
            stop(player);
            return;
        }
        float forward = player.input.forwardImpulse;
        float left = player.input.leftImpulse;
        if (!wallAt(player, wallX, wallZ)) {
            // Past the top edge (or the wall ended): going up, hop over it; otherwise just let go.
            if (forward > 0) {
                double[] v = WebRules.mantle(wallX, wallZ);
                player.setDeltaMovement(v[0], v[1], v[2]);
            }
            noClingUntil = now + REGRAB_TICKS;
            stop(player);
            return;
        }
        double[] v = WebRules.climb(wallX, wallZ, Math.signum(forward), Math.signum(left), cfg.climbSpeed() / 20.0);
        player.setDeltaMovement(v[0], v[1], v[2]);
    }
}
