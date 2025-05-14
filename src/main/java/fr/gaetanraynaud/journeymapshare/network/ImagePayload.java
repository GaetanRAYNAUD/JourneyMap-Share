package fr.gaetanraynaud.journeymapshare.network;

import fr.gaetanraynaud.journeymapshare.JourneyMapShare;
import fr.gaetanraynaud.journeymapshare.MapId;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Packet with the data on an image, used for C2S and S2C.
 */
public final class ImagePayload implements CustomPayload {

    public static final Id<ImagePayload> ID = new Id<>(Identifier.of(JourneyMapShare.MOD_ID, "image"));

    public static final PacketCodec<RegistryByteBuf, ImagePayload> CODEC = PacketCodec.tuple(PacketCodecs.STRING, ImagePayload::getWorld,
                                                                                             PacketCodecs.STRING, ImagePayload::getType,
                                                                                             PacketCodecs.INTEGER, ImagePayload::getX,
                                                                                             PacketCodecs.INTEGER, ImagePayload::getY,
                                                                                             PacketCodecs.BYTE_ARRAY, ImagePayload::getImage,
                                                                                             PacketCodecs.LONG, ImagePayload::getTimestamp,
                                                                                             ImagePayload::new);
    private final String world;

    private final String type;

    private final int x;

    private final int y;

    private final byte[] image;

    private long timestamp;

    public ImagePayload(String world, String type, int x, int y, byte[] image, long timestamp) {
        this.world = world;
        this.type = type;
        this.x = x;
        this.y = y;
        this.image = image;
        this.timestamp = timestamp;
    }

    public Path getPath(Path rootPath) {
        return rootPath.resolve(this.world).resolve(this.type).resolve(this.x + "," + this.y + ".png").normalize().toAbsolutePath();
    }

    public MapId getMapId() {
        return new MapId(this.world, this.type, this.x, this.y);
    }

    public String getWorld() {
        return world;
    }

    public String getType() {
        return type;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public byte[] getImage() {
        return image;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
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
