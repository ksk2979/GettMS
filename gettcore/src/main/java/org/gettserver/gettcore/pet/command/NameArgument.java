package org.gettserver.gettcore.pet.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.Collection;
import java.util.List;
import net.minecraft.network.chat.Component;

/** Single-token id/name argument that accepts any non-space character (Korean included). */
public final class NameArgument implements ArgumentType<String> {
    private static final SimpleCommandExceptionType EMPTY =
            new SimpleCommandExceptionType(Component.translatable("gettcore.cmd.id_empty"));

    public static NameArgument name() {
        return new NameArgument();
    }

    public static String getName(CommandContext<?> ctx, String arg) {
        return ctx.getArgument(arg, String.class);
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        while (reader.canRead() && reader.peek() != ' ') reader.skip();
        String value = reader.getString().substring(start, reader.getCursor());
        if (value.isEmpty()) throw EMPTY.createWithContext(reader);
        return value;
    }

    @Override
    public Collection<String> getExamples() {
        return List.of("town", "마을");
    }
}
