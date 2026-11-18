package org.gettserver.gettcore.compat;

import java.util.Locale;
import java.util.Set;

/**
 * Which Epic Fight mode the main-hand item calls for. Only the main hand decides: an off-hand weapon matters to
 * Epic Fight only when the main-hand weapon dual-wields, so with a tool, food or an empty main hand the player stays
 * in vanilla mode whatever the off hand holds.
 *
 * <p>Weapons with Epic Fight attack motions (swords, spears, daggers, tachi, the categories other mods add, ...) ->
 * Epic Fight mode. Everything else -> vanilla mode: no weapon data, bare hands, digging tools, shields, and bows and
 * crossbows (their draw and aim poses are the vanilla ones; guns have no Epic Fight weapon data at all).
 * An axe is a weapon unless it is a digging tool item (vanilla-style axes chop wood; mod battle axes are not tools).
 *
 * <p>Pure: the caller reads the weapon category from Epic Fight and passes its name.
 */
public final class AutoModeRules {
    public enum Mode { EPIC_FIGHT, VANILLA }

    /** Categories that never switch to Epic Fight mode. */
    private static final Set<String> VANILLA_CATEGORIES =
            Set.of("", "NOT_WEAPON", "FIST", "PICKAXE", "SHOVEL", "HOE", "SHIELD", "RANGED");

    public static final String SETTING_KEY = "epicFightAutoMode";

    private AutoModeRules() {}

    /**
     * @param category   Epic Fight weapon category name of the main-hand item; null or "" when it has no weapon data
     * @param diggingTool the item is a digging tool (pickaxe, shovel, hoe, tool-type axe)
     */
    public static Mode forMainHand(String category, boolean diggingTool) {
        String c = category == null ? "" : category.trim().toUpperCase(Locale.ROOT);
        if (VANILLA_CATEGORIES.contains(c)) return Mode.VANILLA;
        if (c.equals("AXE") && diggingTool) return Mode.VANILLA;
        return Mode.EPIC_FIGHT;
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
