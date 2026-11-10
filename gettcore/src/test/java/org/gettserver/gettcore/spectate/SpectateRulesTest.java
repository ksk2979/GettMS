package org.gettserver.gettcore.spectate;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.Test;

class SpectateRulesTest {
    @Test
    void restoresSavedMode() {
        assertEquals(GameType.CREATIVE, SpectateRules.restoreMode("creative"));
        assertEquals(GameType.ADVENTURE, SpectateRules.restoreMode("adventure"));
    }

    @Test
    void missingOrSpectatorFallsBackToSurvival() {
        assertEquals(GameType.SURVIVAL, SpectateRules.restoreMode(""));
        assertEquals(GameType.SURVIVAL, SpectateRules.restoreMode("nonsense"));
        assertEquals(GameType.SURVIVAL, SpectateRules.restoreMode("spectator"));
    }
}
