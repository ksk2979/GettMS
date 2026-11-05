package org.gettserver.gettcore.economy;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.perm.Perms;

@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class EconomyCommands {
    private EconomyCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(root("돈"));
        d.register(root("money"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String name) {
        return Commands.literal(name)
                .requires(Perms.require(Perms.ECONOMY_CHECK))
                .executes(EconomyCommands::self)
                .then(amountCmd("지급", Ledger::deposit, "gettcore.msg.given"))
                .then(amountCmd("give", Ledger::deposit, "gettcore.msg.given"))
                .then(amountCmd("차감", Ledger::withdraw, "gettcore.msg.taken"))
                .then(amountCmd("take", Ledger::withdraw, "gettcore.msg.taken"))
                .then(amountCmd("설정", Ledger::set, "gettcore.msg.set"))
                .then(amountCmd("set", Ledger::set, "gettcore.msg.set"))
                .then(getCmd("조회"))
                .then(getCmd("get"))
                .then(topCmd("순위"))
                .then(topCmd("top"));
    }

    private interface Op {
        Ledger.Result apply(Ledger ledger, UUID id, long amount);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> amountCmd(String literal, Op op, String okKey) {
        return Commands.literal(literal)
                .requires(Perms.require(Perms.ECONOMY_ADMIN))
                .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("amount", LongArgumentType.longArg(1, Ledger.MAX_AMOUNT))
                                .executes(ctx -> modify(ctx, op, okKey))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> getCmd(String literal) {
        return Commands.literal(literal)
                .requires(Perms.require(Perms.ECONOMY_ADMIN))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(EconomyCommands::lookup));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> topCmd(String literal) {
        return Commands.literal(literal)
                .requires(Perms.require(Perms.ECONOMY_ADMIN))
                .executes(EconomyCommands::top);
    }

    private static String gold(long amount) {
        return String.format("%,d", amount);
    }

    private static int self(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer p)) {
            src.sendFailure(Component.translatable("gettcore.msg.no_player", "-"));
            return 0;
        }
        long bal = EconomyData.get(src.getServer()).ledger().get(p.getUUID());
        src.sendSuccess(() -> Component.translatable("gettcore.msg.balance", gold(bal)), false);
        return 1;
    }

    private static int modify(CommandContext<CommandSourceStack> ctx, Op op, String okKey) {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();
        String name = StringArgumentType.getString(ctx, "player");
        long amount = LongArgumentType.getLong(ctx, "amount");
        Optional<GameProfile> profile = OfflinePlayers.resolve(server, name);
        if (profile.isEmpty()) {
            src.sendFailure(Component.translatable("gettcore.msg.no_player", name));
            return 0;
        }
        EconomyData data = EconomyData.get(server);
        UUID id = profile.get().getId();
        Ledger.Result r = op.apply(data.ledger(), id, amount);
        switch (r) {
            case OK -> {
                data.changed(id);
                String shown = profile.get().getName();
                long bal = data.ledger().get(id);
                src.sendSuccess(() -> Component.translatable(okKey, shown, gold(amount), gold(bal)), true);
                return 1;
            }
            case INSUFFICIENT -> src.sendFailure(Component.translatable("gettcore.msg.insufficient"));
            default -> src.sendFailure(Component.translatable("gettcore.msg.invalid_amount"));
        }
        return 0;
    }

    private static int lookup(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "player");
        Optional<GameProfile> profile = OfflinePlayers.resolve(src.getServer(), name);
        if (profile.isEmpty()) {
            src.sendFailure(Component.translatable("gettcore.msg.no_player", name));
            return 0;
        }
        long bal = EconomyData.get(src.getServer()).ledger().get(profile.get().getId());
        String shown = profile.get().getName();
        src.sendSuccess(() -> Component.translatable("gettcore.msg.balance_of", shown, gold(bal)), false);
        return 1;
    }

    private static int top(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();
        List<Map.Entry<UUID, Long>> entries = EconomyData.get(server).ledger().top(10).stream()
                .filter(e -> e.getValue() > 0).toList();
        src.sendSuccess(() -> Component.translatable("gettcore.msg.top_header"), false);
        int rank = 1;
        for (Map.Entry<UUID, Long> e : entries) {
            int n = rank++;
            String who = OfflinePlayers.name(server, e.getKey());
            src.sendSuccess(() -> Component.translatable("gettcore.msg.top_entry", n, who, gold(e.getValue())), false);
        }
        return entries.size();
    }
}
