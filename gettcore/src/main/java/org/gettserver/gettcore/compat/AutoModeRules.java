package org.gettserver.gettcore.compat;

import java.util.Locale;
import java.util.Set;

/**
 * Which Epic Fight mode the main-hand item calls for. Only the main hand decides: an off-hand weapon matters to
 * Epic Fight only when the main-hand weapon dual-wields, so with a tool, food or an empty main hand the player stays
 * in vanilla mode whatever the off hand holds.
 *
 * <p>Weapons with Epic Fight attack motions (swords, spears, daggers, tachi, the categories other mods add, ...) ->
 * Epic Fight mode. That includes the fist category: it is what gloves and clawed gauntlets are, while an empty hand
 * reaches here with no category at all. Everything else -> vanilla mode: no weapon data, bare hands, digging tools, shields, and bows and
 * crossbows (their draw and aim poses are the vanilla ones; guns have no Epic Fight weapon data at all).
 * Digging tool items (pickaxes, shovels, hoes, vanilla-style axes) always stay vanilla; mod battle axes are not tools.
 * {@code epicFightAutoMode.combat} / {@code epicFightAutoMode.normal} in gettcore-client.properties pin single items.
 *
 * <p>Pure: the caller reads the weapon category from Epic Fight and passes its name.
 */
public final class AutoModeRules {
    public enum Mode { EPIC_FIGHT, VANILLA }

    /** Categories that never switch to Epic Fight mode. */
    private static final Set<String> VANILLA_CATEGORIES =
            Set.of("", "NOT_WEAPON", "PICKAXE", "SHOVEL", "HOE", "SHIELD", "RANGED");

    public static final String SETTING_KEY = "epicFightAutoMode";
    /** Item ids that always switch to Epic Fight mode / always stay in vanilla mode (comma-separated). */
    public static final String COMBAT_IDS_KEY = "epicFightAutoMode.combat";
    public static final String NORMAL_IDS_KEY = "epicFightAutoMode.normal";

    private AutoModeRules() {}

    /**
     * @param category   Epic Fight weapon category name of the main-hand item; null or "" when it has no weapon data
     * @param diggingTool the item is a digging tool (pickaxe, shovel, hoe, tool-type axe)
     */
    public static Mode forMainHand(String category, boolean diggingTool) {
        // A digging tool stays a tool whatever category Epic Fight files it under (its pickaxes and shovels come
        // through with a fighting category, the same one gloves have).
        if (diggingTool) return Mode.VANILLA;
        String c = category == null ? "" : category.trim().toUpperCase(Locale.ROOT);
        if (VANILLA_CATEGORIES.contains(c)) return Mode.VANILLA;
        return Mode.EPIC_FIGHT;
    }

    /**
     * As {@link #forMainHand(String, boolean)}, but an item listed in the settings always gets the mode it is
     * listed for ({@code combatIds} first).
     *
     * @param itemId registry id of the main-hand item ("minecraft:stick"); "" for an empty hand
     */
    public static Mode forMainHand(String itemId, String category, boolean diggingTool, Set<String> combatIds,
            Set<String> normalIds) {
        if (combatIds.contains(itemId)) return Mode.EPIC_FIGHT;
        if (normalIds.contains(itemId)) return Mode.VANILLA;
        return forMainHand(category, diggingTool);
    }

    /** Item ids from a comma-separated setting: lower-cased; blanks and anything that is not "namespace:path" dropped. */
    public static Set<String> parseIds(String setting) {
        if (setting == null) return Set.of();
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String part : setting.split(",")) {
            String id = part.trim().toLowerCase(Locale.ROOT);
            if (id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) out.add(id);
        }
        return Set.copyOf(out);
    }

    /** {@code epicFightAutoMode} in gettcore-client.properties: anything but "off" / "false" keeps it on. */
    public static boolean enabled(String setting) {
        if (setting == null) return true;
        String s = setting.trim().toLowerCase(Locale.ROOT);
        return !(s.equals("off") || s.equals("false") || s.equals("no") || s.equals("0"));
    }

    /**
     * What to do this tick.
     *
     * @param busy          casting a spell or playing an emote (those motions are made for the vanilla model)
     * @param epicNow       currently in Epic Fight mode
     * @param suspended     we put the player into vanilla mode for the current cast / emote
     * @param heldChanged   the main-hand item is not the one the last decision was made for
     * @param wanted        {@link #forMainHand} for the item held now
     */
    public static Action decide(boolean busy, boolean epicNow, boolean suspended, boolean heldChanged, Mode wanted) {
        if (busy) return epicNow ? Action.SUSPEND : Action.NONE;
        if (suspended) {
            // The cast / emote is over: back to Epic Fight mode, unless the hand now holds something else.
            if (!heldChanged) return epicNow ? Action.RESUME_NOOP : Action.RESUME_EPIC;
            return apply(epicNow, wanted, true);
        }
        if (!heldChanged) return Action.NONE;   // a manual toggle stays until the held item changes
        return apply(epicNow, wanted, false);
    }

    private static Action apply(boolean epicNow, Mode wanted, boolean resuming) {
        if (wanted == Mode.EPIC_FIGHT) return epicNow ? (resuming ? Action.RESUME_NOOP : Action.REMEMBER) : (resuming ? Action.RESUME_EPIC : Action.TO_EPIC);
        return epicNow ? (resuming ? Action.RESUME_VANILLA : Action.TO_VANILLA) : (resuming ? Action.RESUME_NOOP : Action.REMEMBER);
    }

    public enum Action {
        /** Nothing to do. */
        NONE(null, false, false, false),
        /** Mode already right; just note the item this decision was for. */
        REMEMBER(null, false, false, true),
        TO_EPIC(Mode.EPIC_FIGHT, false, false, true),
        TO_VANILLA(Mode.VANILLA, false, false, true),
        /** Cast / emote started in Epic Fight mode: vanilla mode until it ends. */
        SUSPEND(Mode.VANILLA, true, false, false),
        RESUME_EPIC(Mode.EPIC_FIGHT, false, true, true),
        RESUME_VANILLA(Mode.VANILLA, false, true, true),
        RESUME_NOOP(null, false, true, true);

        /** Mode to switch to, or null to leave it. */
        public final Mode switchTo;
        public final boolean startsSuspend;
        public final boolean endsSuspend;
        /** Record the current main-hand item as the one decided for. */
        public final boolean remember;

        Action(Mode switchTo, boolean startsSuspend, boolean endsSuspend, boolean remember) {
            this.switchTo = switchTo;
            this.startsSuspend = startsSuspend;
            this.endsSuspend = endsSuspend;
            this.remember = remember;
        }
    }
}
