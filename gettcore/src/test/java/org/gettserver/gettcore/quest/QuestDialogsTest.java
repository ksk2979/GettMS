package org.gettserver.gettcore.quest;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Several injected quest choices on a dialog node: index mapping and labels. */
class QuestDialogsTest {
    @Test
    void resolveWithoutInjection() {
        QuestDialogs.Choice c = QuestDialogs.resolve(0, 1, 3);
        assertFalse(c.quest());
        assertEquals(1, c.nodeIndex());
        assertTrue(c.valid());
    }

    @Test
    void resolveWithTwoInjected() {
        QuestDialogs.Choice first = QuestDialogs.resolve(2, 0, 2);
        assertTrue(first.quest());
        assertEquals(0, first.questIndex());
        QuestDialogs.Choice second = QuestDialogs.resolve(2, 1, 2);
        assertTrue(second.quest());
        assertEquals(1, second.questIndex());
        QuestDialogs.Choice node = QuestDialogs.resolve(2, 2, 2);
        assertFalse(node.quest());
        assertEquals(0, node.nodeIndex());
        assertFalse(QuestDialogs.resolve(2, 5, 2).valid());
        assertFalse(QuestDialogs.resolve(2, -1, 2).valid());
    }

    @Test
    void subLabelUsesSubPrefix() {
        assertEquals("§a[서브] 사냥 (완료)", QuestDialogs.choiceLabel("사냥", true, true));
        assertEquals("§e[메인] 사냥", QuestDialogs.choiceLabel("사냥", false, false));
    }
}
