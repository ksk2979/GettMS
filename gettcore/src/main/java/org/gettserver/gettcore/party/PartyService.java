package org.gettserver.gettcore.party;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.network.PartyActionPayload;
import org.gettserver.gettcore.network.PartyInvitePayload;
import org.gettserver.gettcore.network.PartySyncPayload;
import org.gettserver.gettcore.party.PartyBook.Member;
import org.gettserver.gettcore.party.PartyBook.Party;
import org.gettserver.gettcore.party.PartyBook.Result;

/**
 * Parties on the server: the actions of the party screens ({@link PartyActionPayload}), invitations that time out,
 * keeping every member's client up to date (members, who is online, health), no damage between members, and the
 * {@code [party name]} tag in front of the chat name. Members stay in their party while offline and over restarts
 * ({@link PartyData}). /파티 수락|거절|탈퇴 (/party accept|decline|leave) exist for the clickable chat invitation.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class PartyService {
    private static final int SYNC_INTERVAL = 10;

    private static final PartyInvites INVITES = new PartyInvites();
    /** Party name -> what its members were last sent, so the periodic check only sends changes. */
    private static final Map<String, String> LAST_SENT = new HashMap<>();

    private PartyService() {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        INVITES.clear();
        LAST_SENT.clear();
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(root("파티", "수락", "거절", "탈퇴"));
        event.getDispatcher().register(root("party", "accept", "decline", "leave"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String name, String accept, String decline,
                                                                  String leave) {
        return Commands.literal(name)
                .then(Commands.literal(accept).executes(c -> act(c.getSource(), PartyActionPayload.Action.ACCEPT)))
                .then(Commands.literal(decline).executes(c -> act(c.getSource(), PartyActionPayload.Action.DECLINE)))
                .then(Commands.literal(leave).executes(c -> act(c.getSource(), PartyActionPayload.Action.LEAVE)));
    }

    private static int act(CommandSourceStack source, PartyActionPayload.Action action)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        handle(source.getPlayerOrException(), PartyActionPayload.of(action));
        return 1;
    }

    public static void handle(ServerPlayer player, PartyActionPayload payload) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        PartyData data = PartyData.get(server);
        PartyBook book = data.book();
        switch (payload.action()) {
            case CREATE -> {
                Result r = book.create(member(player), payload.text());
                if (r != Result.OK) {
                    fail(player, r);
                    return;
                }
                Party party = book.of(player.getUUID());
                tell(player, "gettcore.party.created", party.name());
                changed(server, data, party);
            }
            case INVITE -> invite(server, book, player, payload.target());
            case ACCEPT -> accept(server, data, player);
            case DECLINE -> {
                PartyInvites.Invite inv = INVITES.take(player.getUUID());
                if (inv == null) {
                    tell(player, "gettcore.party.no_invite");
                    return;
                }
                PacketDistributor.sendToPlayer(player, PartyInvitePayload.clear());
                tell(player, "gettcore.party.declined_self");
                ServerPlayer inviter = server.getPlayerList().getPlayer(inv.inviter());
                if (inviter != null) tell(inviter, "gettcore.party.declined", player.getGameProfile().getName());
            }
            case LEAVE -> {
                Party party = book.of(player.getUUID());
                if (party == null) {
                    fail(player, Result.NOT_IN_PARTY);
                    return;
                }
                removed(server, data, party, player.getUUID(), player.getGameProfile().getName(), false);
            }
            case KICK -> {
                Party party = book.of(player.getUUID());
                String name = party == null ? "" : party.members().stream()
                        .filter(m -> m.id().equals(payload.target())).map(Member::name).findFirst().orElse("");
                Result r = book.kick(player.getUUID(), payload.target());
                if (r != Result.OK) {
                    fail(player, r);
                    return;
                }
                afterRemoval(server, data, party, payload.target(), name, true, false);
            }
        }
    }

    private static void invite(MinecraftServer server, PartyBook book, ServerPlayer player, UUID targetId) {
        ServerPlayer target = server.getPlayerList().getPlayer(targetId);
        if (target == null) {
            tell(player, "gettcore.party.offline");
            return;
        }
        Result r = book.checkInvite(player.getUUID(), targetId);
        if (r != Result.OK) {
            fail(player, r);
            return;
        }
        Party party = book.of(player.getUUID());
        String targetName = target.getGameProfile().getName();
        long expires = (long) server.getTickCount() + PartyInvites.DURATION_TICKS;
        if (!INVITES.offer(new PartyInvites.Invite(targetId, party.name(), player.getUUID(), expires))) {
            tell(player, "gettcore.party.invite_pending", targetName);
            return;
        }
        String inviterName = player.getGameProfile().getName();
        PacketDistributor.sendToPlayer(target,
                new PartyInvitePayload(party.name(), inviterName, player.getUUID(), PartyInvites.DURATION_TICKS));
        MutableComponent msg = Component.translatable("gettcore.party.invite_received", inviterName, party.name())
                .withStyle(ChatFormatting.YELLOW)
                .append(" ")
                .append(Component.translatable("gettcore.party.btn.accept").withStyle(s -> s
                        .withColor(ChatFormatting.GREEN).withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/party accept"))))
                .append(" ")
                .append(Component.translatable("gettcore.party.btn.decline").withStyle(s -> s
                        .withColor(ChatFormatting.RED).withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/party decline"))));
        target.sendSystemMessage(msg);
        tell(player, "gettcore.party.invite_sent", targetName);
    }

    private static void accept(MinecraftServer server, PartyData data, ServerPlayer player) {
        PartyInvites.Invite inv = INVITES.take(player.getUUID());
        if (inv == null) {
            tell(player, "gettcore.party.no_invite");
            return;
        }
        PacketDistributor.sendToPlayer(player, PartyInvitePayload.clear());
        Result r = data.book().join(inv.party(), member(player));
        if (r != Result.OK) {
            fail(player, r);
            return;
        }
        Party party = data.book().of(player.getUUID());
        tellParty(server, party, "gettcore.party.joined", player.getGameProfile().getName());
        changed(server, data, party);
    }

    /** {@code party} still held the member: take them out, then tell everyone. */
    private static void removed(MinecraftServer server, PartyData data, Party party, UUID who, String name,
                                boolean kicked) {
        boolean wasLeader = party.isLeader(who);
        data.book().leave(who);
        afterRemoval(server, data, party, who, name, kicked, wasLeader);
    }

    private static void afterRemoval(MinecraftServer server, PartyData data, Party party, UUID who, String name,
                                     boolean kicked, boolean wasLeader) {
        data.setDirty();
        ServerPlayer gone = server.getPlayerList().getPlayer(who);
        if (gone != null) {
            PacketDistributor.sendToPlayer(gone, PartySyncPayload.none());
            gone.refreshDisplayName();
            tell(gone, kicked ? "gettcore.party.kicked_self" : "gettcore.party.left_self", party.name());
        }
        if (party.size() == 0) {
            LAST_SENT.remove(key(party));
            for (PartyInvites.Invite inv : INVITES.dropParty(party.name())) {
                ServerPlayer target = server.getPlayerList().getPlayer(inv.target());
                if (target != null) PacketDistributor.sendToPlayer(target, PartyInvitePayload.clear());
            }
            return;
        }
        tellParty(server, party, kicked ? "gettcore.party.kicked" : "gettcore.party.left", name);
        if (wasLeader) tellParty(server, party, "gettcore.party.new_leader", party.members().get(0).name());
        changed(server, data, party);
    }

    /** Saves, refreshes the chat names and sends the party to every online member. */
    private static void changed(MinecraftServer server, PartyData data, Party party) {
        data.setDirty();
        for (Member m : party.members()) {
            ServerPlayer p = server.getPlayerList().getPlayer(m.id());
            if (p != null) p.refreshDisplayName();
        }
        send(server, party);
    }

    private static void send(MinecraftServer server, Party party) {
        PartySyncPayload payload = snapshot(server, party);
        LAST_SENT.put(key(party), signature(payload));
        for (Member m : party.members()) {
            ServerPlayer p = server.getPlayerList().getPlayer(m.id());
            if (p != null) PacketDistributor.sendToPlayer(p, payload);
        }
    }

    private static PartySyncPayload snapshot(MinecraftServer server, Party party) {
        List<PartySyncPayload.Entry> entries = new ArrayList<>();
        for (Member m : party.members()) {
            ServerPlayer p = server.getPlayerList().getPlayer(m.id());
            if (p == null) entries.add(new PartySyncPayload.Entry(m.id(), m.name(), false, 0, 0));
            else entries.add(new PartySyncPayload.Entry(m.id(), m.name(), true, p.getHealth(), p.getMaxHealth()));
        }
        return new PartySyncPayload(party.name(), entries);
    }

    /** Health in half points: smaller changes are not worth a packet. */
    private static String signature(PartySyncPayload payload) {
        StringBuilder sb = new StringBuilder();
        for (PartySyncPayload.Entry e : payload.members()) {
            sb.append(e.id()).append(e.name()).append(e.online() ? '1' : '0')
                    .append(Math.round(e.health() * 2)).append('/').append(Math.round(e.maxHealth() * 2)).append(';');
        }
        return sb.toString();
    }

    private static String key(Party party) {
        return party.name().toLowerCase(Locale.ROOT);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SYNC_INTERVAL != 0) return;
        for (PartyInvites.Invite inv : INVITES.expire(server.getTickCount())) {
            ServerPlayer target = server.getPlayerList().getPlayer(inv.target());
            if (target != null) {
                PacketDistributor.sendToPlayer(target, PartyInvitePayload.clear());
                tell(target, "gettcore.party.expired_self");
            }
            ServerPlayer inviter = server.getPlayerList().getPlayer(inv.inviter());
            if (inviter != null && target != null) {
                tell(inviter, "gettcore.party.expired", target.getGameProfile().getName());
            }
        }
        if (server.getPlayerList().getPlayerCount() == 0) return;
        for (Party party : PartyData.get(server).book().parties()) {
            PartySyncPayload payload = snapshot(server, party);
            String sig = signature(payload);
            if (sig.equals(LAST_SENT.get(key(party)))) continue;
            LAST_SENT.put(key(party), sig);
            for (Member m : party.members()) {
                ServerPlayer p = server.getPlayerList().getPlayer(m.id());
                if (p != null) PacketDistributor.sendToPlayer(p, payload);
            }
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.getServer() == null) return;
        PartyData data = PartyData.get(player.getServer());
        if (data.book().rename(player.getUUID(), player.getGameProfile().getName())) data.setDirty();
        Party party = data.book().of(player.getUUID());
        if (party == null) {
            PacketDistributor.sendToPlayer(player, PartySyncPayload.none());
        } else {
            player.refreshDisplayName();
            send(player.getServer(), party);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        // An invitation cannot be answered by someone who left; the periodic check tells the party they went offline.
        INVITES.take(event.getEntity().getUUID());
    }

    /** No damage between members of the same party (melee, arrows, spells: anything with the member as its source). */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer attacker) || attacker == victim) return;
        if (victim.getServer() == null) return;
        if (PartyData.get(victim.getServer()).book().sameParty(attacker.getUUID(), victim.getUUID())) {
            event.setCanceled(true);
        }
    }

    /** {@code [party name]} in front of the name used in chat; after other mods have decorated the name. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onNameFormat(PlayerEvent.NameFormat event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.getServer() == null) return;
        Party party = PartyData.get(player.getServer()).book().of(player.getUUID());
        if (party == null) return;
        event.setDisplayname(Component.literal("[" + party.name() + "] ").withStyle(ChatFormatting.GOLD)
                .append(Component.empty().withStyle(ChatFormatting.RESET).append(event.getDisplayname())));
    }

    private static Member member(ServerPlayer player) {
        return new Member(player.getUUID(), player.getGameProfile().getName());
    }

    private static void fail(ServerPlayer player, Result r) {
        player.sendSystemMessage(Component.translatable("gettcore.party.err." + r.name().toLowerCase(Locale.ROOT))
                .withStyle(ChatFormatting.RED));
    }

    private static void tell(ServerPlayer player, String key, Object... args) {
        player.sendSystemMessage(Component.translatable(key, args).withStyle(ChatFormatting.YELLOW));
    }

    private static void tellParty(MinecraftServer server, Party party, String key, Object... args) {
        for (Member m : party.members()) {
            ServerPlayer p = server.getPlayerList().getPlayer(m.id());
            if (p != null) tell(p, key, args);
        }
    }
}
