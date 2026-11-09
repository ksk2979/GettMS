package org.gettserver.gettcore.invsee;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.MenuConstructor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.perm.Perms;

/** /인벤 &lt;플레이어&gt; (/invsee) and /엔더 &lt;플레이어&gt; (/endersee): view and edit an online player's items (Perms.ADMIN). */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class InvSeeCommands {
    private InvSeeCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        for (String name : new String[] {"인벤", "invsee"}) d.register(root(name, false));
        for (String name : new String[] {"엔더", "endersee"}) d.register(root(name, true));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String name, boolean ender) {
        return Commands.literal(name)
                .requires(Perms.require(Perms.ADMIN))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> open(ctx, ender)));
    }

    private static int open(CommandContext<CommandSourceStack> ctx, boolean ender) throws CommandSyntaxException {
        ServerPlayer viewer = ctx.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        String what = ender ? "ender chest" : "inventory";
        MenuConstructor menu = ender
                ? (id, inv, p) -> InvSeeMenu.ender(id, inv, target)
                : (id, inv, p) -> new InvSeeMenu(id, inv, target);
        Component title = Component.translatable(
                ender ? "gettcore.container.endersee" : "gettcore.container.invsee", target.getName());
        viewer.openMenu(new SimpleMenuProvider(menu, title));
        GettCore.LOGGER.info("gettcore: {} opened {} of {}", viewer.getGameProfile().getName(), what,
                target.getGameProfile().getName());
        return 1;
    }
}
