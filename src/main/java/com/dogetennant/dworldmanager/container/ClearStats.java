package com.dogetennant.dworldmanager.container;

/** Tally of what a container-clear sweep actually did, for the staff-facing summary message. */
public class ClearStats {
    public int containersCleared;
    public int itemFramesCleared;
    public int armorStandsCleared;
    public int allaysCleared;
    public int droppedItemsRemoved;

    public int total() {
        return containersCleared + itemFramesCleared + armorStandsCleared + allaysCleared + droppedItemsRemoved;
    }
}
