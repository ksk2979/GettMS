package org.gettserver.gettcore.mixin;

import com.mojang.brigadier.StringReader;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import org.gettserver.gettcore.nick.NicknameBook;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Player arguments of commands (/tp, /give, ...) take a nickname. The game only reads plain ASCII words there, so a
 * Korean nickname would need quotes; this reads such a word as a player name as well. Whether it is anyone's
 * nickname is decided when the command runs ({@link PlayerListMixin}).
 */
@Mixin(EntitySelectorParser.class)
public abstract class EntitySelectorParserMixin {
    @Shadow @Final private StringReader reader;
    @Shadow private int maxResults;
    @Shadow private boolean includesEntities;
    @Shadow private String playerName;

    @Inject(method = "parseNameOrUUID", at = @At("HEAD"), cancellable = true)
    private void gettcore$readNickname(CallbackInfo ci) {
        if (!reader.canRead() || StringReader.isQuotedStringStart(reader.peek())) return;
        String rest = reader.getRemaining();
        int space = rest.indexOf(' ');
        String token = space < 0 ? rest : rest.substring(0, space);
        boolean plain = true;
        for (int i = 0; i < token.length(); i++) {
            if (!StringReader.isAllowedInUnquotedString(token.charAt(i))) {
                plain = false;
                break;
            }
        }
        // Plain words go the normal way; anything that could not be a nickname is left for the usual error.
        if (plain || NicknameBook.clean(token) == null || !token.equals(NicknameBook.clean(token))) return;
        reader.setCursor(reader.getCursor() + token.length());
        includesEntities = false;
        playerName = token;
        maxResults = 1;
        ci.cancel();
    }
}
