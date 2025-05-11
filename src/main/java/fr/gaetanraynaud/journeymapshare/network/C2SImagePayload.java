package fr.gaetanraynaud.journeymapshare.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

public record C2SImagePayload(String world, String type, int x, int y, byte[] image) implements CustomPayload {

    public static final Id<C2SImagePayload> ID = new Id<>(NetworkingConstants.C2S_IMAGE_ID);

    public static final PacketCodec<RegistryByteBuf, C2SImagePayload> CODEC = PacketCodec.tuple(PacketCodecs.STRING, C2SImagePayload::world,
                                                                                                PacketCodecs.STRING, C2SImagePayload::type,
                                                                                                PacketCodecs.INTEGER, C2SImagePayload::x,
                                                                                                PacketCodecs.INTEGER, C2SImagePayload::y,
                                                                                                PacketCodecs.BYTE_ARRAY, C2SImagePayload::image,
                                                                                                C2SImagePayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
