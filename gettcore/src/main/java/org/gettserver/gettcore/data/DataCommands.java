package org.gettserver.gettcore.data;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.gettserver.gettcore.editor.EditorServerService;
import org.gettserver.gettcore.item.ReturnPointData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.perm.Perms;
import org.gettserver.gettcore.quest.QuestService;

@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class DataCommands {
    private DataCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("gett")
                .requires(Perms.require(Perms.ADMIN))
                .then(Commands.literal("reload")
                        .requires(Perms.require(Perms.ADMIN))
                        .executes(DataCommands::reload))
                .then(Commands.literal("편집")
                        .requires(Perms.require(Perms.ADMIN))
                        .executes(DataCommands::openEditor))
                .then(Commands.literal("editor")
                        .requires(Perms.require(Perms.ADMIN))
                        .executes(DataCommands::openEditor)));
        event.getDispatcher().register(Commands.literal("귀환지점")
                .requires(Perms.require(Perms.ADMIN))
                .then(Commands.literal("설정")
                        .requires(Perms.require(Perms.ADMIN))
                        .executes(DataCommands::setReturnPoint)));
        event.getDispatcher().register(Commands.literal("returnpoint")
                .requires(Perms.require(Perms.ADMIN))
                .then(Commands.literal("set")
                        .requires(Perms.require(Perms.ADMIN))
                        .executes(DataCommands::setReturnPoint)));
    }

    private static int setReturnPoint(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ReturnPointData.get(player.server).set(player.level().dimension(), player.blockPosition(), player.getYRot());
        ctx.getSource().sendSuccess(() -> Component.translatable("gettcore.msg.return_point_set"), true);
        return 1;
    }

    private static int openEditor(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.translatable("gettcore.msg.editor_players_only"));
            return 0;
        }
        if (!EditorServerService.open(player)) return 0;
        ctx.getSource().sendSuccess(() -> Component.translatable("gettcore.msg.editor_opening"), false);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();
        GettData.LoadReport r = GettData.get().load(DataEvents.configDir());
        r.errors().forEach(e -> GettCore.LOGGER.error("gettcore reload: {}", e));
        GettCore.LOGGER.info("gettcore: 상점 {}, 대화 {} 재적용, 오류 {}, 경고 {}", r.shops(), r.dialogs(),
                r.errors().size(), r.warnings().size());
        GettData.runReloadHooks();
        DataEvents.syncConfig(server);
        QuestService.refreshAll(server);
        src.sendSuccess(() -> Component.translatable("gettcore.msg.reload_done",
                r.shops(), r.dialogs(), r.errors().size(), r.warnings().size()), true);
        return 1;
    }
}
