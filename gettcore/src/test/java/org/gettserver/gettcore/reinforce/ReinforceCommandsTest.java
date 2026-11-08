package org.gettserver.gettcore.reinforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReinforceCommandsTest {
    @Test
    void messageKeys() {
        assertEquals("gettcore.msg.reinforce.not_target", ReinforceCommands.messageKey(Optional.empty()));
        assertEquals("gettcore.msg.reinforce.set", ReinforceCommands.messageKey(Optional.of(ReinforceRules.Target.ARMOR)));
    }

    @Test
    void commandRangeCoversConfigRange() {
        assertTrue(ReinforceCommands.MAX_COMMAND_LEVEL >= ReinforceConfig.MAX_LEVEL_LIMIT);
    }
}
