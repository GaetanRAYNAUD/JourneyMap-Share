package fr.gaetanraynaud.journeymapshare;

import fr.gaetanraynaud.journeymapshare.network.ImagePayload;
import fr.gaetanraynaud.journeymapshare.network.ImagesMetaListPayload;
import fr.gaetanraynaud.journeymapshare.network.SubscribePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public class JourneyMapShareClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.playS2C().register(ImagePayload.ID, ImagePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SubscribePayload.ID, SubscribePayload.CODEC);
    }
}
