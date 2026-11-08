package org.gettserver.gettcore.quest;

import com.mojang.brigadier.CommandDispatcher;
import org.gettserver.gettcore.pet.command.NameArgument;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.perm.Perms;

/** /퀘스트 초기화|완료|이동 and /quest reset|complete|goto (online players, main line, Perms.ADMIN). */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class QuestCommands {
    private QuestCommands() {}

    private static final SuggestionProvider<CommandSourceStack> QUEST_IDS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(QuestService.mainLine()
                    .map(l -> l.quests().stream().map(Quest::id).toList())
                    .orElse(java.util.List.of()), builder);

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(root("퀘스트", "초기화", "완료", "이동"));
        d.register(root("quest", "reset", "complete", "goto"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String name, String reset, String complete,
                                                                   String jump) {
        return Commands.literal(name)
                .requires(Perms.require(Perms.ADMIN))
                .then(Commands.literal(reset)
                        .then(Commands.argument("player", EntityArgument.player()).executes(QuestCommands::reset)))
                .then(Commands.literal(complete)
                        .then(Commands.argument("player", EntityArgument.player()).executes(QuestCommands::complete)))
                .then(Commands.literal(jump)
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("quest", NameArgument.name()).suggests(QUEST_IDS)
                                        .executes(QuestCommands::jump))));
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        QuestService.adminReset(target);
        String name = target.getGameProfile().getName();
        ctx.getSource().sendSuccess(() -> Component.translatable("gettcore.quest.cmd.reset", name), true);
        return 1;
    }

    private static int complete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        String name = target.getGameProfile().getName();
        Optional<Quest> done = QuestService.adminComplete(target);
        if (done.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("gettcore.quest.cmd.nothing", name));
            return 0;
        }
        String title = done.get().title();
        ctx.getSource().sendSuccess(() -> Component.translatable("gettcore.quest.cmd.complete", name, title), true);
        return 1;
    }

    private static int jump(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        String name = target.getGameProfile().getName();
        String questId = NameArgument.getName(ctx, "quest");
        if (!QuestService.adminGoto(target, questId)) {
            ctx.getSource().sendFailure(Component.translatable("gettcore.quest.cmd.unknown", questId));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("gettcore.quest.cmd.goto", name, questId), true);
        return 1;
    }
}
