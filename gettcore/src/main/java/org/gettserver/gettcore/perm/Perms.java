package org.gettserver.gettcore.perm;

import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import org.gettserver.gettcore.GettCore;

@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class Perms {
    public static final PermissionNode<Boolean> ECONOMY_CHECK = node("economy.check");
    public static final PermissionNode<Boolean> ECONOMY_ADMIN = node("economy.admin");
    public static final PermissionNode<Boolean> SHOP_EDIT = node("shop.edit");
    public static final PermissionNode<Boolean> ADMIN = node("admin");
    public static final PermissionNode<Boolean> BOSS_ADMIN = node("boss.admin");

    private Perms() {}

    private static PermissionNode<Boolean> node(String name) {
        PermissionNode<Boolean>[] self = newHolder();
        self[0] = new PermissionNode<>(GettCore.MOD_ID, name, PermissionTypes.BOOLEAN,
                (player, uuid, ctx) -> fallback(player != null && player.hasPermissions(2), self[0]));
        return self[0];
    }

    @SuppressWarnings("unchecked")
    private static PermissionNode<Boolean>[] newHolder() {
        return new PermissionNode[1];
    }

    /** Default when no permission provider overrides: check node for everyone, the rest need OP. */
    public static boolean fallback(boolean isOp, PermissionNode<Boolean> node) {
        return node == ECONOMY_CHECK || isOp;
    }

    public static boolean has(ServerPlayer player, PermissionNode<Boolean> node) {
        try {
            return PermissionAPI.getPermission(player, node);
        } catch (RuntimeException e) {
            // LuckPerms는 접속 직후(명령어 목록 전송 시점)엔 아직 플레이어 데이터를 준비하지 못해
            // "Capability has not been initialised" 를 던진다. 그때는 OP 기준으로 판단하고,
            // LuckPerms 가 준비되면 명령어 목록을 다시 보내므로 이후 조회는 정상이다.
            return fallback(player.hasPermissions(2), node);
        }
    }

    public static Predicate<CommandSourceStack> require(PermissionNode<Boolean> node) {
        return src -> src.getEntity() instanceof ServerPlayer p ? has(p, node) : true;
    }

    @SubscribeEvent
    public static void onGather(PermissionGatherEvent.Nodes event) {
        event.addNodes(ECONOMY_CHECK, ECONOMY_ADMIN, SHOP_EDIT, ADMIN, BOSS_ADMIN);
    }
}
