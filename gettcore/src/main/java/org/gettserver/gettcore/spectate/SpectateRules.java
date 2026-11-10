package org.gettserver.gettcore.spectate;

import net.minecraft.world.level.GameType;

/** /관전 을 끌 때 돌아갈 게임 모드. 저장값이 없거나 이상하면 서바이벌. */
final class SpectateRules {
    private SpectateRules() {}

    static GameType restoreMode(String saved) {
        GameType mode = GameType.byName(saved, GameType.SURVIVAL);
        return mode == GameType.SPECTATOR ? GameType.SURVIVAL : mode;
    }
}
