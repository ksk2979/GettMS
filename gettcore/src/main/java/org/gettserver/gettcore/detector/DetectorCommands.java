package org.gettserver.gettcore.detector;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.perm.Perms;

/** /탐지기 초기화 [플레이어] 와 /detector reset [player] (Perms.ADMIN): 탐지 쿨타임을 모두 지운다(테스트용). */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class DetectorCommands {
    private DetectorCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(root("탐지기", "초기화"));
        d.register(root("detector", "reset"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String name, String sub) {
        return Commands.literal(name)
                .requires(Perms.require(Perms.ADMIN))
                .then(Commands.literal(sub)
                        .executes(c -> reset(c, c.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(c -> reset(c, EntityArgument.getPlayer(c, "player")))));
    }

    private static int reset(CommandContext<CommandSourceStack> ctx, ServerPlayer target) throws CommandSyntaxException {
        DetectorService.resetCooldowns(target);
        ctx.getSource().sendSuccess(() -> Component.translatable("gettcore.detector.reset",
                target.getGameProfile().getName()), true);
        return 1;
    }
}
