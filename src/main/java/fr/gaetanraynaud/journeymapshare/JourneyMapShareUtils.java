package fr.gaetanraynaud.journeymapshare;

import net.minecraft.server.world.ServerWorld;

public class JourneyMapShareUtils {

    public static String serverWorldToWorldName(ServerWorld serverWorld) {
        return serverWorld.getDimensionEntry().getKey().get().getValue().getPath();
    }
}
