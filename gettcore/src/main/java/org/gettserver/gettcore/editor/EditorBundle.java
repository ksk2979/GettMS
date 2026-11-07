package org.gettserver.gettcore.editor;

import java.util.Map;
import org.gettserver.gettcore.data.DialogDefinition;
import org.gettserver.gettcore.data.NpcMapping;
import org.gettserver.gettcore.data.ShopDefinition;
import org.gettserver.gettcore.quest.QuestLine;

/** Everything the web editor edits, keyed by file id. */
public record EditorBundle(Map<String, ShopDefinition> shops, Map<String, DialogDefinition> dialogs,
                           NpcMapping npcs, Map<String, QuestLine> quests) {
}
