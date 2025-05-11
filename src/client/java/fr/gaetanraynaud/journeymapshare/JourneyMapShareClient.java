package fr.gaetanraynaud.journeymapshare;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class JourneyMapShareClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            JourneyMapShare.LOGGER.info("Started JourneyMapShare on client!");
        });
    }
}