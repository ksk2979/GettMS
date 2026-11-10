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
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.perm.Perms;

/**
 * /퀘스트 초기화|완료|이동 and /quest reset|complete|goto (online players, Perms.ADMIN), each with an optional line id:
 * {@code 초기화 <player> [line]} (no line = every line), {@code 완료 <player> [line]},
 * {@code 이동 <player> <quest> [line]} (no line = main).
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class QuestCommands {
    private QuestCommands() {}

    private static final SuggestionProvider<CommandSourceStack> LINE_IDS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(GettData.get().allQuestLines().stream().map(QuestLine::id).toList(),
                    builder);

    /** Quest ids of every line (the line argument comes after the quest). */
    private static final SuggestionProvider<CommandSourceStack> QUEST_IDS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(GettData.get().allQuestLines().stream()
                    .flatMap(l -> l.quests().stream().map(Quest::id)).distinct().toList(), builder);

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
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> reset(ctx, Optional.empty()))
                                .then(Commands.argument("line", NameArgument.name()).suggests(LINE_IDS)
                                        .executes(ctx -> reset(ctx, Optional.of(NameArgument.getName(ctx, "line")))))))
                .then(Commands.literal(complete)
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> complete(ctx, QuestService.MAIN_LINE))
                                .then(Commands.argument("line", NameArgument.name()).suggests(LINE_IDS)
                                        .executes(ctx -> complete(ctx, NameArgument.getName(ctx, "line"))))))
                .then(Commands.literal(jump)
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("quest", NameArgument.name()).suggests(QUEST_IDS)
                                        .executes(ctx -> jump(ctx, QuestService.MAIN_LINE))
                                        .then(Commands.argument("line", NameArgument.name()).suggests(LINE_IDS)
                                                .executes(ctx -> jump(ctx, NameArgument.getName(ctx, "line")))))));
    }

    /** Sends the no_line failure and returns false when the line does not exist. */
    private static boolean lineExists(CommandContext<CommandSourceStack> ctx, String lineId) {
        if (GettData.get().questLine(lineId).isPresent()) return true;
        ctx.getSource().sendFailure(Component.translatable("gettcore.quest.no_line", lineId));
        return false;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx, Optional<String> line)
            throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        if (line.isPresent() && !lineExists(ctx, line.get())) return 0;
        QuestService.adminReset(target, line);
        String name = target.getGameProfile().getName();
        if (line.isPresent()) {
            String l = line.get();
            ctx.getSource().sendSuccess(() -> Component.translatable("gettcore.quest.cmd.reset_line", name, l), true);
        } else {
            ctx.getSource().sendSuccess(() -> Component.translatable("gettcore.quest.cmd.reset", name), true);
        }
        return 1;
    }

    private static int complete(CommandContext<CommandSourceStack> ctx, String line) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        if (!lineExists(ctx, line)) return 0;
        String name = target.getGameProfile().getName();
        Optional<Quest> done = QuestService.adminComplete(target, line);
        if (done.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("gettcore.quest.cmd.nothing", name, line));
            return 0;
        }
        String title = done.get().title();
        ctx.getSource().sendSuccess(() -> Component.translatable("gettcore.quest.cmd.complete", name, title), true);
        return 1;
    }

    private static int jump(CommandContext<CommandSourceStack> ctx, String line) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        if (!lineExists(ctx, line)) return 0;
        String name = target.getGameProfile().getName();
        String questId = NameArgument.getName(ctx, "quest");
        if (!QuestService.adminGoto(target, line, questId)) {
            ctx.getSource().sendFailure(Component.translatable("gettcore.quest.cmd.unknown", line, questId));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("gettcore.quest.cmd.goto", name, line, questId),
                true);
        return 1;
    }
}
