package org.gettserver.gettcore.data;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.perm.Perms;

@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class DataCommands {
    private DataCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("gett")
                .then(Commands.literal("reload")
                        .requires(Perms.require(Perms.ADMIN))
                        .executes(DataCommands::reload)));
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();
        GettData.LoadReport r = GettData.get().load(DataEvents.configDir());
        r.errors().forEach(e -> GettCore.LOGGER.error("gettcore reload: {}", e));
        GettCore.LOGGER.info("gettcore: 상점 {}, 대화 {} 재적용, 오류 {}", r.shops(), r.dialogs(), r.errors().size());
        GettData.runReloadHooks();
        DataEvents.syncConfig(server);
        src.sendSuccess(() -> Component.translatable("gettcore.msg.reload_done",
                r.shops(), r.dialogs(), r.errors().size()), true);
        return 1;
    }
}
