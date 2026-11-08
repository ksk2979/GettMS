package org.gettserver.gettcore.editor;

import static org.gettserver.gettcore.editor.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.gettserver.gettcore.data.*;
import org.gettserver.gettcore.quest.*;
import org.junit.jupiter.api.Test;

class EditorValidatorTest {
    private static final Map<String, DialogDefinition> NO_D = Map.of();
    private static final Map<String, QuestLine> NO_Q = Map.of();

    private static boolean any(List<String> l, String s) {
        return l.stream().anyMatch(x -> x.contains(s));
    }

    @Test
    void serverOnlyModItemIsKept() {
        RegistryChecks checks = new RegistryChecks(rl -> rl.getNamespace().equals("servermod"), rl -> true);
        EditorBundle b = bundle(Map.of("s", shop("s", entry("servermod:gem", 5, 1))), NO_D, NpcMapping.EMPTY, NO_Q);
        EditorValidator.Result r = EditorValidator.validate(b, checks);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertTrue(r.warnings().isEmpty());
        assertEquals(b, r.cleaned());
    }

    @Test
    void missingItemIsRemovedWithWarning() {
        RegistryChecks checks = new RegistryChecks(rl -> !rl.getNamespace().equals("gone"), rl -> true);
        EditorBundle b = bundle(Map.of("s", shop("s", entry("minecraft:a", 1, 1), entry("minecraft:b", 1, 1),
                entry("minecraft:c", 1, 1), entry("gone:x", 1, 1))), NO_D, NpcMapping.EMPTY, NO_Q);
        EditorValidator.Result r = EditorValidator.validate(b, checks);
        assertTrue(r.errors().isEmpty());
        assertTrue(any(r.warnings(), "shops.s.entries[3]"), r.warnings().toString());
        assertEquals(3, r.cleaned().shops().get("s").entries().size());
    }

    @Test
    void danglingNextNodeIsError() {
        var d = dialog("d", "a", Map.of("a", new DialogNode("x", List.of(choice(ChoiceAction.Type.NEXT, "zzz")))));
        var r = EditorValidator.validate(bundle(Map.of(), Map.of("d", d), NpcMapping.EMPTY, NO_Q),
                RegistryChecks.ALL_EXIST);
        assertTrue(any(r.errors(), "dialogs.d.nodes.a"), r.errors().toString());
        var d2 = dialog("d", "nope", Map.of("a", new DialogNode("x", List.of())));
        var r2 = EditorValidator.validate(bundle(Map.of(), Map.of("d", d2), NpcMapping.EMPTY, NO_Q),
                RegistryChecks.ALL_EXIST);
        assertTrue(any(r2.errors(), "dialogs.d"), r2.errors().toString());
    }

    @Test
    void unknownShopTargetIsError() {
        var d = dialog("d", "a", Map.of("a", new DialogNode("x", List.of(
                choice(ChoiceAction.Type.OPEN_SHOP_BUY, "ghost"),
                choice(ChoiceAction.Type.QUEST, "nolines")))));
        var r = EditorValidator.validate(bundle(Map.of(), Map.of("d", d), NpcMapping.EMPTY, NO_Q),
                RegistryChecks.ALL_EXIST);
        assertTrue(any(r.errors(), "ghost"));
        assertTrue(any(r.errors(), "nolines"));
        // quest action without target defaults to line "main", which is absent as well
        var d2 = dialog("d", "a", Map.of("a", new DialogNode("x", List.of(choice(ChoiceAction.Type.QUEST, null)))));
        var r2 = EditorValidator.validate(bundle(Map.of(), Map.of("d", d2), NpcMapping.EMPTY, NO_Q),
                RegistryChecks.ALL_EXIST);
        assertTrue(any(r2.errors(), "main"), r2.errors().toString());
    }

    @Test
    void npcWithUnknownDialogIsError() {
        var r = EditorValidator.validate(bundle(Map.of(), NO_D, new NpcMapping(Map.of(), Map.of("A", "nodlg")), NO_Q),
                RegistryChecks.ALL_EXIST);
        assertTrue(any(r.errors(), "npcs.byName.A"), r.errors().toString());
    }

    @Test
    void keyIdMismatchIsError() {
        var r = EditorValidator.validate(bundle(Map.of("a", shop("b")), NO_D, NpcMapping.EMPTY, NO_Q),
                RegistryChecks.ALL_EXIST);
        assertTrue(any(r.errors(), "shops.a"));
        var r2 = EditorValidator.validate(bundle(Map.of("Bad-Id", shop("Bad-Id")), NO_D, NpcMapping.EMPTY, NO_Q),
                RegistryChecks.ALL_EXIST);
        assertTrue(any(r2.errors(), "shops.Bad-Id"));
    }

    @Test
    void negativePriceIsError() {
        var r = EditorValidator.validate(bundle(Map.of("s", shop("s", entry("minecraft:a", -1, 1))), NO_D,
                NpcMapping.EMPTY, NO_Q), RegistryChecks.ALL_EXIST);
        assertTrue(any(r.errors(), "shops.s.entries[0]"));
    }

    @Test
    void questNpcMustBeMapped() {
        var line = new QuestLine("main", "m", "e", List.of(quest("meet", npc("이장"), List.of(), Rewards.EMPTY)));
        var r = EditorValidator.validate(bundle(Map.of(), NO_D, NpcMapping.EMPTY, Map.of("main", line)),
                RegistryChecks.ALL_EXIST);
        assertTrue(any(r.errors(), "quests.main.meet"), r.errors().toString());
        var r2 = EditorValidator.validate(bundle(Map.of(), Map.of("c", dialog("c", "a",
                        Map.of("a", new DialogNode("x", List.of())))),
                new NpcMapping(Map.of(), Map.of("이장", "c")), Map.of("main", line)), RegistryChecks.ALL_EXIST);
        assertTrue(r2.errors().isEmpty(), r2.errors().toString());
    }

    @Test
    void questRuleErrors() {
        var q1 = quest("dup", QuestTrigger.AUTO, List.of(), Rewards.EMPTY);
        var q2 = quest("dup", new QuestTrigger(QuestTrigger.Type.NPC, Optional.empty()), List.of(), Rewards.EMPTY);
        var q3 = quest("bad", QuestTrigger.AUTO, List.of(),
                new Rewards(-1, List.of(new ItemReward(rl("minecraft:a"), 0)), -2));
        var q4 = quest("cnt", QuestTrigger.AUTO,
                List.of(new Objective(Objective.Type.COLLECT, "minecraft:a", 0, false, Optional.empty())), Rewards.EMPTY);
        var line = new QuestLine("main", "m", "e", List.of(q1, q2, q3, q4));
        var r = EditorValidator.validate(bundle(Map.of(), NO_D, NpcMapping.EMPTY, Map.of("main", line)),
                RegistryChecks.ALL_EXIST);
        assertEquals(1, r.errors().stream().filter(e -> e.contains("duplicate")).count(), r.errors().toString());
        assertTrue(any(r.errors(), "quests.main.dup.start"), r.errors().toString());
        assertTrue(any(r.errors(), "quests.main.bad.rewards.gold"));
        assertTrue(any(r.errors(), "quests.main.bad.rewards.xp"));
        assertTrue(any(r.errors(), "quests.main.bad.rewards.items[0]"));
        assertTrue(any(r.errors(), "quests.main.cnt.objectives[0]"));
    }

    @Test
    void questMissingItemsPrunedAndTagsKept() {
        RegistryChecks checks = new RegistryChecks(rl -> !rl.getNamespace().equals("gone"), rl -> true);
        var q = quest("q", QuestTrigger.AUTO, List.of(
                new Objective(Objective.Type.COLLECT, "#gone:logs", 1, false, Optional.empty()),
                new Objective(Objective.Type.COLLECT, "gone:x", 1, false, Optional.empty())),
                Rewards.EMPTY);
        var line = new QuestLine("main", "m", "e", List.of(q));
        var r = EditorValidator.validate(bundle(Map.of(), NO_D, NpcMapping.EMPTY, Map.of("main", line)), checks);
        assertTrue(r.errors().isEmpty());
        assertTrue(any(r.warnings(), "quests.main.q"), r.warnings().toString());
        assertEquals(1, r.cleaned().quests().get("main").quests().get(0).objectives().size());
    }

    @Test
    void bossPointBadRadius() {
        var b = bundle(Map.of(), NO_D, NpcMapping.EMPTY, NO_Q,
                List.of(bossPoint("den", "gettcore:boss_lion", 0, 1, 30)), pets(species("gettcore:boss_lion", true)));
        var r = EditorValidator.validate(b, RegistryChecks.ALL_EXIST);
        assertEquals(1, r.errors().size(), r.errors().toString());
        assertTrue(r.errors().get(0).startsWith("boss_points.den: radius"), r.errors().toString());
    }

    @Test
    void bossPointUnknownType() {
        // Not in species at all, and present but not a boss: both are errors.
        var b = bundle(Map.of(), NO_D, NpcMapping.EMPTY, NO_Q,
                List.of(bossPoint("a", "gettcore:boss_ghost", 16, 1, 30), bossPoint("b", "naturalist:lion", 16, 1, 30)),
                pets(species("naturalist:lion", false)));
        var r = EditorValidator.validate(b, RegistryChecks.ALL_EXIST);
        assertTrue(any(r.errors(), "boss_points.a: bossType gettcore:boss_ghost"), r.errors().toString());
        assertTrue(any(r.errors(), "boss_points.b: bossType naturalist:lion"), r.errors().toString());
    }

    @Test
    void bossPointRangesIdsAndValidPoint() {
        var ok = bossPoint("ok", "gettcore:boss_lion", 128, 10, 1440);
        var b = bundle(Map.of(), NO_D, NpcMapping.EMPTY, NO_Q, List.of(ok,
                        bossPoint("ok", "gettcore:boss_lion", 16, 1, 30),
                        bossPoint("", "gettcore:boss_lion", 16, 1, 30),
                        bossPoint("big", "gettcore:boss_lion", 129, 11, 1441)),
                pets(species("gettcore:boss_lion", true)));
        var r = EditorValidator.validate(b, RegistryChecks.ALL_EXIST);
        assertTrue(any(r.errors(), "boss_points.ok: duplicate id"), r.errors().toString());
        assertTrue(any(r.errors(), "boss_points[2]: id must not be empty"), r.errors().toString());
        assertTrue(any(r.errors(), "boss_points.big: radius"), r.errors().toString());
        assertTrue(any(r.errors(), "boss_points.big: maxCount"), r.errors().toString());
        assertTrue(any(r.errors(), "boss_points.big: respawnMinutes"), r.errors().toString());
        assertEquals(5, r.errors().size(), r.errors().toString());

        var good = bundle(Map.of(), NO_D, NpcMapping.EMPTY, NO_Q, List.of(ok), pets(species("gettcore:boss_lion", true)));
        var r2 = EditorValidator.validate(good, RegistryChecks.ALL_EXIST);
        assertTrue(r2.errors().isEmpty(), r2.errors().toString());
        assertEquals(good, r2.cleaned());
    }

    @Test
    void petsAreSanitizedWithWarnings() {
        var d = org.gettserver.gettcore.pet.PetConfig.DEFAULT;
        var bad = new org.gettserver.gettcore.pet.PetConfig(d.healthbar(), new org.gettserver.gettcore.pet.PetConfig.Capture(
                -5, 20, d.capture().ballChance(), d.capture().bossBallChance(),
                java.util.Map.of(org.gettserver.gettcore.pet.CaptureTier.GOLD, 150.0), d.capture().bossObtainChance(), 20),
                Map.of());
        var r = EditorValidator.validate(bundle(Map.of(), NO_D, NpcMapping.EMPTY, NO_Q, List.of(), bad),
                RegistryChecks.ALL_EXIST);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertTrue(any(r.warnings(), "pets: capture.minPercent"), r.warnings().toString());
        assertTrue(any(r.warnings(), "pets: capture.obtainChance.gold"), r.warnings().toString());
        assertEquals(d.capture().minPercent(), r.cleaned().pets().capture().minPercent());
        assertEquals(d.capture().obtainChance(), r.cleaned().pets().capture().obtainChance());
    }

    @Test
    void petEntityMustBeRegistered() {
        RegistryChecks checks = new RegistryChecks(rl -> true, rl -> !rl.getPath().equals("pet_lion"));
        var b = bundle(Map.of(), NO_D, NpcMapping.EMPTY, NO_Q, List.of(), pets(species("naturalist:lion", false)));
        var r = EditorValidator.validate(b, checks);
        assertEquals(List.of("pets.naturalist:lion: petEntity 'gettcore:pet_lion' is not a registered entity"),
                r.errors());
    }

    @Test
    void unknownWildSpeciesIsAWarning() {
        RegistryChecks checks = new RegistryChecks(rl -> true, rl -> !rl.getNamespace().equals("gone"));
        var b = bundle(Map.of(), NO_D, NpcMapping.EMPTY, NO_Q, List.of(), pets(species("gone:lion", false)));
        var r = EditorValidator.validate(b, checks);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertTrue(any(r.warnings(), "pets.gone:lion: species 'gone:lion' is not a registered entity"),
                r.warnings().toString());
    }

    @Test
    void zeroSizeScaleIsResetWithAWarning() {
        var s = species("gettcore:boss_lion", true);
        var zero = new org.gettserver.gettcore.pet.PetSpecies(s.id(), s.kind(), s.petEntity(), s.attack(), s.speed(),
                s.attackSpeed(), s.flySpeed(), s.climbSpeed(), true, 0, s.healthMult(), s.attackMult(), s.killGold(),
                s.jumpHeight(), s.ballChance(), s.obtainChance());
        var r = EditorValidator.validate(bundle(Map.of(), NO_D, NpcMapping.EMPTY, NO_Q, List.of(), pets(zero)),
                RegistryChecks.ALL_EXIST);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertTrue(any(r.warnings(), "pets: species gettcore:boss_lion sizeScale"), r.warnings().toString());
        assertEquals(1.0, r.cleaned().pets().species().get("gettcore:boss_lion").sizeScale());
    }

    private static final org.gettserver.gettcore.pet.PetConfig NO_PETS = org.gettserver.gettcore.pet.PetConfig.DEFAULT;
    private static final org.gettserver.gettcore.protect.ProtectConfig.Flags NONE =
            org.gettserver.gettcore.protect.ProtectConfig.Flags.DEFAULT;

    @Test
    void protectDuplicateRegionId() {
        var p = protect(region("town", new net.minecraft.core.BlockPos(0, 0, 0), new net.minecraft.core.BlockPos(5, 5, 5), NONE),
                region("town", new net.minecraft.core.BlockPos(9, 9, 9), new net.minecraft.core.BlockPos(12, 12, 12), NONE),
                region("", new net.minecraft.core.BlockPos(0, 0, 0), new net.minecraft.core.BlockPos(1, 1, 1), NONE));
        var r = EditorValidator.validate(bundle(Map.of(), NO_D, NpcMapping.EMPTY, NO_Q, List.of(), NO_PETS, p),
                RegistryChecks.ALL_EXIST);
        assertEquals(List.of("protect.regions.town: duplicate id", "protect.regions[2]: id must not be empty"),
                r.errors());
    }

    @Test
    void protectMinMaxNormalized() {
        var flags = new org.gettserver.gettcore.protect.ProtectConfig.Flags(true, false, false);
        var p = new org.gettserver.gettcore.protect.ProtectConfig(true, true, List.of(
                region("town", new net.minecraft.core.BlockPos(10, 80, -5), new net.minecraft.core.BlockPos(-10, 0, 5), flags)));
        var r = EditorValidator.validate(bundle(Map.of(), NO_D, NpcMapping.EMPTY, NO_Q, List.of(), NO_PETS, p),
                RegistryChecks.ALL_EXIST);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertTrue(any(r.warnings(), "protect.regions.town: min/max swapped to -10, 0, -5 ~ 10, 80, 5"),
                r.warnings().toString());
        var cleaned = r.cleaned().protect();
        assertTrue(cleaned.explosionsBreakBlocks() && cleaned.endermanPickup());
        assertEquals(List.of(region("town", new net.minecraft.core.BlockPos(-10, 0, -5),
                new net.minecraft.core.BlockPos(10, 80, 5), flags)), cleaned.regions());

        // Already ordered: untouched, no warning.
        var ok = protect(region("farm", new net.minecraft.core.BlockPos(0, 0, 0), new net.minecraft.core.BlockPos(3, 3, 3), NONE));
        var b = bundle(Map.of(), NO_D, NpcMapping.EMPTY, NO_Q, List.of(), NO_PETS, ok);
        var r2 = EditorValidator.validate(b, RegistryChecks.ALL_EXIST);
        assertTrue(r2.errors().isEmpty() && r2.warnings().isEmpty(), r2.warnings().toString());
        assertEquals(b, r2.cleaned());
    }

    @Test
    void reinforceSanitizedWithWarnings() {
        var bad = new org.gettserver.gettcore.reinforce.ReinforceConfig(3, List.of(150.0, 50.0), List.of(0, 2, 99, 5),
                -1, 10, 2, 1.5, 60);
        var r = EditorValidator.validate(bundle(Map.of(), NO_D, NpcMapping.EMPTY, NO_Q, List.of(), NO_PETS,
                org.gettserver.gettcore.protect.ProtectConfig.DEFAULT, bad), RegistryChecks.ALL_EXIST);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertTrue(r.warnings().stream().allMatch(w -> w.startsWith("reinforce: ")), r.warnings().toString());
        assertTrue(any(r.warnings(), "reinforce: successPercent has 2 entries"), r.warnings().toString());
        assertTrue(any(r.warnings(), "reinforce: successPercent values must be within [0, 100]"), r.warnings().toString());
        assertTrue(any(r.warnings(), "reinforce: stoneCost has 4 entries"), r.warnings().toString());
        assertTrue(any(r.warnings(), "reinforce: stoneCost values must be within [1, 64]"), r.warnings().toString());
        assertTrue(any(r.warnings(), "reinforce: gunDamagePercentPerLevel -1.0"), r.warnings().toString());
        assertEquals(new org.gettserver.gettcore.reinforce.ReinforceConfig(3, List.of(100.0, 50.0, 50.0),
                List.of(1, 2, 64), 10, 10, 2, 1.5, 60), r.cleaned().reinforce());

        // maxLevel out of range falls back to the default (30) and the lists follow it.
        var big = new org.gettserver.gettcore.reinforce.ReinforceConfig(51, List.of(70.0), List.of(3), 10, 10, 2, 1.5, 0);
        var r2 = EditorValidator.validate(bundle(Map.of(), NO_D, NpcMapping.EMPTY, NO_Q, List.of(), NO_PETS,
                org.gettserver.gettcore.protect.ProtectConfig.DEFAULT, big), RegistryChecks.ALL_EXIST);
        assertTrue(any(r2.warnings(), "reinforce: maxLevel 51"), r2.warnings().toString());
        assertEquals(30, r2.cleaned().reinforce().maxLevel());
        assertEquals(30, r2.cleaned().reinforce().successPercent().size());
        assertEquals(0, r2.cleaned().reinforce().armorHealthMax(), "0 = no cap is kept");

        // Valid config: untouched, no warning.
        var ok = bundle(Map.of(), NO_D, NpcMapping.EMPTY, NO_Q, List.of(), NO_PETS,
                org.gettserver.gettcore.protect.ProtectConfig.DEFAULT, reinforce());
        var r3 = EditorValidator.validate(ok, RegistryChecks.ALL_EXIST);
        assertTrue(r3.errors().isEmpty() && r3.warnings().isEmpty(), r3.warnings().toString());
        assertEquals(ok, r3.cleaned());
    }

    @Test
    void reinforceMilestoneWithUnknownEffectIsWarnedAndDropped() {
        var keep = new org.gettserver.gettcore.reinforce.ReinforceConfig.Milestone(
                org.gettserver.gettcore.reinforce.ReinforceRules.Target.ARMOR, 3,
                net.minecraft.resources.ResourceLocation.parse("minecraft:speed"), 0);
        var unknown = new org.gettserver.gettcore.reinforce.ReinforceConfig.Milestone(
                org.gettserver.gettcore.reinforce.ReinforceRules.Target.MELEE, 2,
                net.minecraft.resources.ResourceLocation.parse("othermod:ghost"), 0);
        var cfg = new org.gettserver.gettcore.reinforce.ReinforceConfig(3, List.of(100.0, 50.0, 25.0),
                List.of(1, 1, 1), 10, 10, 2, 1.5, 60, List.of(keep, unknown));
        var saved = org.gettserver.gettcore.reinforce.ReinforceConfig.effectUsable;
        org.gettserver.gettcore.reinforce.ReinforceConfig.effectUsable = id -> id.getNamespace().equals("minecraft");
        try {
            var r = EditorValidator.validate(bundle(Map.of(), NO_D, NpcMapping.EMPTY, NO_Q, List.of(), NO_PETS,
                    org.gettserver.gettcore.protect.ProtectConfig.DEFAULT, cfg), RegistryChecks.ALL_EXIST);
            assertTrue(r.errors().isEmpty(), r.errors().toString());
            assertEquals(List.of("reinforce: milestones[1] effect othermod:ghost is unknown or instant (즉시 효과는 쓸 수 없습니다), removed"), r.warnings());
            assertEquals(List.of(keep), r.cleaned().reinforce().milestones());
        } finally {
            org.gettserver.gettcore.reinforce.ReinforceConfig.effectUsable = saved;
        }
    }
}
