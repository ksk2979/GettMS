package org.gettserver.gettcore.economy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.perm.Perms;

@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class CheckCommands {
    private static final RateLimiter LIMITER = new RateLimiter(1000);

    private CheckCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(root("수표발행"));
        d.register(root("check"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String name) {
        return Commands.literal(name)
                .requires(Perms.require(Perms.ECONOMY_CHECK))
                .then(Commands.argument("amount", LongArgumentType.longArg(1, Ledger.MAX_AMOUNT))
                        .executes(CheckCommands::issue));
    }

    private static int issue(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        long amount = LongArgumentType.getLong(ctx, "amount");
        if (!LIMITER.tryAcquire(player.getUUID(), System.currentTimeMillis())) {
            src.sendFailure(Component.translatable("gettcore.msg.too_fast"));
            return 0;
        }
        EconomyData data = EconomyData.get(src.getServer());
        Ledger.Result r = data.ledger().withdraw(player.getUUID(), amount);
        switch (r) {
            case OK -> {
                data.changed(player.getUUID());
                ItemStack stack = CheckItem.create(amount, player.getGameProfile().getName());
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }
                src.sendSuccess(() -> Component.translatable("gettcore.msg.check_issued", Gold.format(amount)), false);
                return 1;
            }
            case INSUFFICIENT -> src.sendFailure(Component.translatable("gettcore.msg.insufficient"));
            default -> src.sendFailure(Component.translatable("gettcore.msg.invalid_amount"));
        }
        return 0;
    }
}
