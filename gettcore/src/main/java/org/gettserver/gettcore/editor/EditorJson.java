package org.gettserver.gettcore.editor;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.util.LinkedHashMap;
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
import org.gettserver.gettcore.skill.BossConfig;
import org.gettserver.gettcore.upgrade.UpgradeConfig;

/** Bundle JSON as in spec 3.4: same per-file formats as the on-disk files, via the existing codecs. */
public final class EditorJson {
    private static final Gson GSON = new Gson();

    private EditorJson() {}

    public static String encode(EditorBundle bundle, EditorRefs refs) {
        JsonObject root = new JsonObject();
        root.add("shops", encodeMap(bundle.shops(), ShopDefinition.CODEC));
        root.add("dialogs", encodeMap(bundle.dialogs(), DialogDefinition.CODEC));
        root.add("npcs", NpcMapping.CODEC.encodeStart(JsonOps.INSTANCE, bundle.npcs()).getOrThrow());
        root.add("quests", encodeMap(bundle.quests(), QuestLine.CODEC));
        root.add("boss_points", BossPoint.CODEC.listOf().encodeStart(JsonOps.INSTANCE, bundle.bossPoints()).getOrThrow());
        root.add("pets", PetConfig.CODEC.encodeStart(JsonOps.INSTANCE, bundle.pets()).getOrThrow());
        root.add("protect", ProtectConfig.CODEC.encodeStart(JsonOps.INSTANCE, bundle.protect()).getOrThrow());
        root.add("reinforce", ReinforceConfig.CODEC.encodeStart(JsonOps.INSTANCE, bundle.reinforce()).getOrThrow());
        root.add("hunting", HuntingConfig.CODEC.encodeStart(JsonOps.INSTANCE, bundle.hunting()).getOrThrow());
        root.add("mobs", MobStatsConfig.CODEC.encodeStart(JsonOps.INSTANCE, bundle.mobs()).getOrThrow());
        root.add("equipment", EquipmentConfig.CODEC.encodeStart(JsonOps.INSTANCE, bundle.equipment()).getOrThrow());
        root.add("upgrade", UpgradeConfig.CODEC.encodeStart(JsonOps.INSTANCE, bundle.upgrade()).getOrThrow());
        root.add("bosses", BossConfig.CODEC.encodeStart(JsonOps.INSTANCE, bundle.bosses()).getOrThrow());
        if (refs != null) {
            JsonObject r = new JsonObject();
            JsonArray adv = new JsonArray();
            for (EditorRefs.AdvRef a : refs.advancements()) {
                JsonObject o = new JsonObject();
                o.addProperty("id", a.id());
                o.addProperty("title", a.title());
                adv.add(o);
            }
            r.add("advancements", adv);
            r.add("missingItems", strings(refs.missingItems()));
            r.add("missingEntities", strings(refs.missingEntities()));
            root.add("refs", r);
        }
        return GSON.toJson(root);
    }

    public static EditorBundle decode(String json) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("bundle: invalid JSON: " + e.getMessage());
        }
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("bundle: expected a JSON object");
        JsonObject root = parsed.getAsJsonObject();
        requireSection(root, "npcs");
        NpcMapping npcs = NpcMapping.CODEC.parse(JsonOps.INSTANCE, root.get("npcs")).getOrThrow(
                m -> new IllegalArgumentException("npcs: " + m));
        return new EditorBundle(decodeMap(root, "shops", ShopDefinition.CODEC),
                decodeMap(root, "dialogs", DialogDefinition.CODEC), npcs,
                decodeMap(root, "quests", QuestLine.CODEC),
                decodeSection(root, "boss_points", BossPoint.CODEC.listOf()),
                decodeSection(root, "pets", PetConfig.CODEC),
                decodeSection(root, "protect", ProtectConfig.CODEC),
                decodeSection(root, "reinforce", ReinforceConfig.CODEC),
                decodeSection(root, "hunting", HuntingConfig.CODEC),
                decodeSection(root, "mobs", MobStatsConfig.CODEC),
                decodeSection(root, "equipment", EquipmentConfig.CODEC),
                decodeSection(root, "upgrade", UpgradeConfig.CODEC),
                // Optional for a payload from before bosses.json existed (every other section is required).
                root.has("bosses") && !root.get("bosses").isJsonNull()
                        ? decodeSection(root, "bosses", BossConfig.CODEC) : BossConfig.DEFAULT);
    }

    private static <T> T decodeSection(JsonObject root, String section, Codec<T> codec) {
        requireSection(root, section);
        return codec.parse(JsonOps.INSTANCE, root.get(section))
                .getOrThrow(m -> new IllegalArgumentException(section + ": " + m));
    }

    /** A save replaces everything, so a missing section must be an error rather than "empty". */
    private static void requireSection(JsonObject root, String section) {
        if (!root.has(section) || root.get(section).isJsonNull()) {
            throw new IllegalArgumentException(section + ": missing section");
        }
    }

    private static JsonArray strings(List<String> l) {
        JsonArray a = new JsonArray();
        l.forEach(a::add);
        return a;
    }

    private static <T> JsonObject encodeMap(Map<String, T> map, Codec<T> codec) {
        JsonObject o = new JsonObject();
        map.forEach((k, v) -> o.add(k, codec.encodeStart(JsonOps.INSTANCE, v).getOrThrow()));
        return o;
    }

    private static <T> Map<String, T> decodeMap(JsonObject root, String section, Codec<T> codec) {
        Map<String, T> out = new LinkedHashMap<>();
        requireSection(root, section);
        if (!root.get(section).isJsonObject()) throw new IllegalArgumentException(section + ": expected an object");
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject(section).entrySet()) {
            String path = section + "." + e.getKey();
            out.put(e.getKey(), codec.parse(JsonOps.INSTANCE, e.getValue()).getOrThrow(
                    m -> new IllegalArgumentException(path + ": " + m)));
        }
        return out;
    }
}
