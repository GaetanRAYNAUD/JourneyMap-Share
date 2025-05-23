package fr.gaetanraynaud.journeymapshare.network;

import fr.gaetanraynaud.journeymapshare.JourneyMapShare;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Packet sent by the client to subscribe to images' updates from other players.
 */
public record SubscribePayload(Map<String, String> config) implements CustomPayload {

    public static final Id<SubscribePayload> ID = new Id<>(Identifier.of(JourneyMapShare.MOD_ID, "subscribe"));

    public static final PacketCodec<RegistryByteBuf, SubscribePayload> CODEC = PacketCodec.tuple(
            PacketCodecs.map(HashMap::new, PacketCodecs.STRING, PacketCodecs.STRING), SubscribePayload::config, SubscribePayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
