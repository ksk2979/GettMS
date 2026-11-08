package org.gettserver.gettcore.reinforce;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals(50, ReinforceCommands.MAX_COMMAND_LEVEL);
    }
}
