package org.gettserver.gettcore.display;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.network.DisplayLoopSyncPayload;

/**
 * Dream Displays helpers on the display the player looks at:
 * /화면 끄기 (/screen off), /화면 반복 켜기|끄기 (/screen loop on|off),
 * /화면 나만보기 <주소>|끄기 (/screen solo <url>|off). Only with Dream Displays installed.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class DisplayCommands {
    private DisplayCommands() {}

    private interface Action {
        void run(ServerPlayer player);
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        if (!ModList.get().isLoaded("dreamdisplays")) return;
        event.getDispatcher().register(root("화면", "끄기", "반복", "켜기", "끄기", "나만보기", "끄기", "주소"));
        event.getDispatcher().register(root("screen", "off", "loop", "on", "off", "solo", "off", "url"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String name, String clear, String loop, String on,
                                                                   String off, String solo, String soloOff, String url) {
        return Commands.literal(name)
                .then(Commands.literal(clear).executes(c -> run(c, p -> {
                    var d = DisplayService.target(p);
                    if (d != null) DisplayService.clear(p, d);
                })))
                .then(Commands.literal(loop)
                        .then(Commands.literal(on).executes(c -> run(c, p -> loop(p, true))))
                        .then(Commands.literal(off).executes(c -> run(c, p -> loop(p, false)))))
                .then(Commands.literal(solo)
                        .then(Commands.literal(soloOff).executes(c -> run(c, p -> solo(p, ""))))
                        .then(Commands.argument(url, StringArgumentType.greedyString())
                                .executes(c -> run(c, p -> solo(p, StringArgumentType.getString(c, url))))));
    }

    private static void loop(ServerPlayer player, boolean on) {
        var d = DisplayService.target(player);
        if (d != null) DisplayService.setLoop(player, d, on);
    }

    private static void solo(ServerPlayer player, String url) {
        var d = DisplayService.target(player);
        if (d != null) DisplayService.personal(player, d, url);
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Action action) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        try {
            action.run(player);
        } catch (LinkageError e) {
            DisplayService.unsupported(player, e);
        }
        return 1;
    }

    /** The repeat switches are plain data: a client without Dream Displays just never reads them. */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PacketDistributor.sendToPlayer(player,
                    new DisplayLoopSyncPayload(DisplayLoopData.get(player.server).all()));
        }
    }
}
