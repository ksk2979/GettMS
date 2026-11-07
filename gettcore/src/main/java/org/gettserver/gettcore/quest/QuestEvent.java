package org.gettserver.gettcore.quest;

/** Something that happened to a player's quest during a judgment (for notifications and rewards). */
public record QuestEvent(Kind kind, Quest quest) {
    public enum Kind { ACCEPTED, COMPLETED }
}
