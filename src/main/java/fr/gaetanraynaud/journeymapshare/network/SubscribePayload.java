package fr.gaetanraynaud.journeymapshare.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/**
 * Packet sent by the client to subscribe to images' updates from other players.
 */
public record SubscribePayload(boolean b) implements CustomPayload {

    public static final Id<SubscribePayload> ID = new Id<>(NetworkingConstants.SUBSCRIBE_ID);

    public static final PacketCodec<RegistryByteBuf, SubscribePayload> CODEC = PacketCodec.tuple(PacketCodecs.BOOLEAN, SubscribePayload::b,
                                                                                                 SubscribePayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
