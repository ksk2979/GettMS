package org.gettserver.gettcore.editor;

import java.util.List;
import java.util.Map;
import org.gettserver.gettcore.data.DialogDefinition;
import org.gettserver.gettcore.data.NpcMapping;
import org.gettserver.gettcore.data.ShopDefinition;
import org.gettserver.gettcore.equipment.EquipmentConfig;
import org.gettserver.gettcore.hunting.HuntingConfig;
import org.gettserver.gettcore.mobs.MobStatsConfig;
import org.gettserver.gettcore.pet.PetConfig;
import org.gettserver.gettcore.pet.boss.BossPoint;
import org.gettserver.gettcore.protect.ProtectConfig;
import org.gettserver.gettcore.quest.QuestLine;
import org.gettserver.gettcore.reinforce.ReinforceConfig;

/**
 * Everything the web editor edits: shops/dialogs/quests keyed by file id, plus boss_points.json, pets.json,
 * protect.json, reinforce.json, hunting.json, mobs.json and equipment.json.
 */
public record EditorBundle(Map<String, ShopDefinition> shops, Map<String, DialogDefinition> dialogs,
                           NpcMapping npcs, Map<String, QuestLine> quests, List<BossPoint> bossPoints,
                           PetConfig pets, ProtectConfig protect,
                           ReinforceConfig reinforce, HuntingConfig hunting, MobStatsConfig mobs,
                           EquipmentConfig equipment) {
    /** Without equipment.json (default). Only for callers that never save the result as a whole. */
    public EditorBundle(Map<String, ShopDefinition> shops, Map<String, DialogDefinition> dialogs, NpcMapping npcs,
                        Map<String, QuestLine> quests, List<BossPoint> bossPoints, PetConfig pets,
                        ProtectConfig protect, ReinforceConfig reinforce, HuntingConfig hunting,
                        MobStatsConfig mobs) {
        this(shops, dialogs, npcs, quests, bossPoints, pets, protect, reinforce, hunting, mobs,
                EquipmentConfig.DEFAULT);
    }

    /** Without hunting.json / mobs.json / equipment.json (all default). Only for callers that never save the result as a whole. */
    public EditorBundle(Map<String, ShopDefinition> shops, Map<String, DialogDefinition> dialogs, NpcMapping npcs,
                        Map<String, QuestLine> quests, List<BossPoint> bossPoints, PetConfig pets,
                        ProtectConfig protect, ReinforceConfig reinforce) {
        this(shops, dialogs, npcs, quests, bossPoints, pets, protect, reinforce, HuntingConfig.DEFAULT,
                MobStatsConfig.DEFAULT);
    }
}
