package org.gettserver.gettcore.editor;

import java.util.List;
import java.util.Map;
import org.gettserver.gettcore.data.DialogDefinition;
import org.gettserver.gettcore.data.NpcMapping;
import org.gettserver.gettcore.data.ShopDefinition;
import org.gettserver.gettcore.pet.PetConfig;
import org.gettserver.gettcore.pet.boss.BossPoint;
import org.gettserver.gettcore.protect.ProtectConfig;
import org.gettserver.gettcore.quest.QuestLine;
import org.gettserver.gettcore.reinforce.ReinforceConfig;

/** Everything the web editor edits: shops/dialogs/quests keyed by file id, plus boss_points.json, pets.json, protect.json and reinforce.json. */
public record EditorBundle(Map<String, ShopDefinition> shops, Map<String, DialogDefinition> dialogs,
                           NpcMapping npcs, Map<String, QuestLine> quests, List<BossPoint> bossPoints,
                           PetConfig pets, ProtectConfig protect,
                           ReinforceConfig reinforce) {
}
