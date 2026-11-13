package org.gettserver.gettcore.display.client;

/** Implemented on Dream Displays' DisplayScreen by a mixin: its private "this client plays its own address" flag. */
public interface PersonalFlag {
    boolean gettcore$personal();

    void gettcore$setPersonal(boolean on);
}
