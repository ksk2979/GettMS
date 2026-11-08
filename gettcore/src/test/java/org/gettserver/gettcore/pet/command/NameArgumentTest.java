package org.gettserver.gettcore.pet.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.junit.jupiter.api.Test;

class NameArgumentTest {
    @Test
    void stopsAtSpace() throws Exception {
        StringReader r = new StringReader("마을 extra");
        assertEquals("마을", new NameArgument().parse(r));
        assertEquals(' ', r.peek());
    }

    @Test
    void plainWord() throws Exception {
        assertEquals("abc", new NameArgument().parse(new StringReader("abc")));
    }

    @Test
    void emptyRejected() {
        assertThrows(CommandSyntaxException.class, () -> new NameArgument().parse(new StringReader("")));
    }
}
