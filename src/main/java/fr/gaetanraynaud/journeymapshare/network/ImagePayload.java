package fr.gaetanraynaud.journeymapshare.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

import java.nio.file.Path;

/**
 * Packet with the data on an image, used for C2S and S2C.
 */
public record ImagePayload(String world, String type, int x, int y, byte[] image, long timestamp) implements CustomPayload {

    public static final Id<ImagePayload> ID = new Id<>(NetworkingConstants.IMAGE_ID);

    public static final PacketCodec<RegistryByteBuf, ImagePayload> CODEC = PacketCodec.tuple(PacketCodecs.STRING, ImagePayload::world,
                                                                                             PacketCodecs.STRING, ImagePayload::type,
                                                                                             PacketCodecs.INTEGER, ImagePayload::x,
                                                                                             PacketCodecs.INTEGER, ImagePayload::y,
                                                                                             PacketCodecs.BYTE_ARRAY, ImagePayload::image,
                                                                                             PacketCodecs.LONG, ImagePayload::timestamp,
                                                                                             ImagePayload::new);

    public Path getPath(Path rootPath) {
        return rootPath.resolve(this.world).resolve(this.type).resolve(this.x + "," + this.y + ".png");
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    @Override
    public String toString() {
        return "world='" + world + '\'' + ", type='" + type + '\'' + ", x=" + x + ", y=" + y + ", image=" + image.length + '}';
    }
}
