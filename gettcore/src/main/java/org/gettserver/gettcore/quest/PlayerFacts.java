package org.gettserver.gettcore.quest;

/** What the engine needs to know about a player, so it can be tested without a server. */
public interface PlayerFacts {
    /** Number of items in the inventory matching an item id or #tag. */
    int countItem(String itemOrTag);

    boolean hasAdvancement(String id);
}
