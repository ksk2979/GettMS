package org.gettserver.gettcore.quest;

/** Judged state of one objective; {@code current} is capped at {@code required}. */
public record ObjectiveState(Objective objective, int current, int required, boolean done) {}
