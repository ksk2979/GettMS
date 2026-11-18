package org.gettserver.gettcore.nick;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.HashMap;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.network.NicknameSyncPayload;
import org.gettserver.gettcore.nick.NicknameBook.Result;
import org.gettserver.gettcore.perm.Perms;

/**
 * Server nicknames. A player without one is asked for it on login (free); changing it later takes a nickname ticket
 * (use it, type the name; the ticket is taken only when the name was accepted). The nickname replaces the account name in chat, the tab list, above the head and in the
 * party screens, and player arguments of commands take it too (mixins EntitySelectorParserMixin, PlayerListMixin). Admins: /닉네임 설정 <플레이어> <이름>, 초기화 <플레이어>,
 * 확인 <닉네임> (and /nickname set|reset|whois).
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class NicknameService {
    private NicknameService() {}

    /** The name to show for a player: nickname, else account name. */
    public static String nameOf(ServerPlayer player) {
        return nameOf(player.getServer(), player.getUUID(), player.getGameProfile().getName());
    }

    public static String nameOf(MinecraftServer server, UUID id, String fallback) {
        if (server == null) return fallback;
        String nick = NicknameData.get(server).book().of(id);
        return nick != null ? nick : fallback;
    }

    /** The answer to the nickname screen of a ticket. */
    public static void onSet(ServerPlayer player, String raw) {
        // The first nickname is free; changing it afterwards takes a ticket.
        boolean first = NicknameData.get(player.getServer()).book().of(player.getUUID()) == null;
        InteractionHand hand = ticketHand(player);
        if (hand == null && !first) {
            fail(player, "gettcore.nickname.err.no_ticket");
            return;
        }
        Result r = apply(player, raw);
        if (r != Result.OK) {
            fail(player, "gettcore.nickname.err." + r.name().toLowerCase(Locale.ROOT));
            return;
        }
        if (!first) player.getItemInHand(hand).shrink(1);
        player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.4f);
        player.sendSystemMessage(Component.translatable("gettcore.nickname.changed", nameOf(player))
                .withStyle(ChatFormatting.YELLOW));
    }

    private static InteractionHand ticketHand(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.is(GettCore.NICKNAME_TICKET.get())) return hand;
        }
        return null;
    }

    private static Result apply(ServerPlayer player, String raw) {
        MinecraftServer server = player.getServer();
        NicknameData data = NicknameData.get(server);
        UUID id = player.getUUID();
        Result r = data.book().set(id, raw, key -> accountOfSomeoneElse(server, key, id));
        if (r == Result.OK) changed(server, data, player);
        return r;
    }

    /** Whether a lower-case name is the account name of another player who is online or has played here. */
    private static boolean accountOfSomeoneElse(MinecraftServer server, String key, UUID self) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.getUUID().equals(self) && p.getGameProfile().getName().toLowerCase(Locale.ROOT).equals(key)) return true;
        }
        return server.getProfileCache() != null && server.getProfileCache().get(key)
                .filter(profile -> !profile.getId().equals(self)).isPresent();
    }

    private static void changed(MinecraftServer server, NicknameData data, ServerPlayer player) {
        data.setDirty();
        if (player != null) {
            player.refreshDisplayName();
            player.refreshTabListName();
        }
        PacketDistributor.sendToAllPlayers(new NicknameSyncPayload(new HashMap<>(data.book().all())));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.getServer() == null) return;
        NicknameBook book = NicknameData.get(player.getServer()).book();
        PacketDistributor.sendToPlayer(player, new NicknameSyncPayload(new HashMap<>(book.all())));
        if (book.of(player.getUUID()) == null) {
            PacketDistributor.sendToPlayer(player, new org.gettserver.gettcore.network.NicknamePromptPayload());
        }
    }

    /** First of all listeners: the nickname is the base name that other mods and the party tag then decorate. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onNameFormat(PlayerEvent.NameFormat event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.getServer() == null) return;
        String nick = NicknameData.get(player.getServer()).book().of(player.getUUID());
        if (nick != null) event.setDisplayname(Component.literal(nick));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onTabListName(PlayerEvent.TabListNameFormat event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.getServer() == null) return;
        String nick = NicknameData.get(player.getServer()).book().of(player.getUUID());
        if (nick != null) event.setDisplayName(Component.literal(nick));
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(root("닉네임", "설정", "초기화", "확인"));
        event.getDispatcher().register(root("nickname", "set", "reset", "whois"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String name, String set, String reset, String whois) {
        return Commands.literal(name)
                .requires(Perms.require(Perms.ADMIN))
                .then(Commands.literal(set).then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(NicknameService::cmdSet))))
                .then(Commands.literal(reset).then(Commands.argument("player", EntityArgument.player())
                        .executes(NicknameService::cmdReset)))
                .then(Commands.literal(whois).then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(NicknameService::cmdWhois)));
    }

    private static int cmdSet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        Result r = apply(target, StringArgumentType.getString(ctx, "name"));
        if (r != Result.OK) {
            ctx.getSource().sendFailure(Component.translatable("gettcore.nickname.err." + r.name().toLowerCase(Locale.ROOT)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("gettcore.nickname.admin_set",
                target.getGameProfile().getName(), nameOf(target)), true);
        return 1;
    }

    private static int cmdReset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        NicknameData data = NicknameData.get(target.getServer());
        if (!data.book().clear(target.getUUID())) {
            ctx.getSource().sendFailure(Component.translatable("gettcore.nickname.err.none"));
            return 0;
        }
        changed(target.getServer(), data, target);
        ctx.getSource().sendSuccess(() -> Component.translatable("gettcore.nickname.admin_reset",
                target.getGameProfile().getName()), true);
        return 1;
    }

    private static int cmdWhois(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        String nick = StringArgumentType.getString(ctx, "name");
        UUID id = NicknameData.get(server).book().owner(nick);
        if (id == null) {
            ctx.getSource().sendFailure(Component.translatable("gettcore.nickname.err.unknown", nick));
            return 0;
        }
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        String account = online != null ? online.getGameProfile().getName()
                : server.getProfileCache() == null ? id.toString()
                : server.getProfileCache().get(id).map(p -> p.getName()).orElse(id.toString());
        ctx.getSource().sendSuccess(() -> Component.translatable("gettcore.nickname.whois", nick, account), false);
        return 1;
    }

    private static void fail(ServerPlayer player, String key) {
        player.sendSystemMessage(Component.translatable(key).withStyle(ChatFormatting.RED));
    }
}
