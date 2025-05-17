package fr.gaetanraynaud.journeymapshare.network;

import fr.gaetanraynaud.journeymapshare.JourneyMapShare;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Packet with the timestamp on an image, used for C2S and S2C.
 */
public final class ImageTimestamp implements CustomPayload {

    public static final Id<ImageTimestamp> ID = new Id<>(Identifier.of(JourneyMapShare.MOD_ID, "image_timestamp"));

    public static final PacketCodec<RegistryByteBuf, ImageTimestamp> CODEC = PacketCodec.tuple(PacketCodecs.INTEGER, ImageTimestamp::getX, PacketCodecs.INTEGER,
                                                                                               ImageTimestamp::getY, PacketCodecs.LONG,
                                                                                               ImageTimestamp::getTimestamp, ImageTimestamp::new);

    private final int x;

    private final int y;

    private long timestamp;

    public ImageTimestamp(int x, int y, long timestamp) {
        this.x = x;
        this.y = y;
        this.timestamp = timestamp;
    }

    public Path getPath(Path rootPath) {
        return rootPath.resolve(this.x + "," + this.y + ".png").normalize().toAbsolutePath();
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
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
    public boolean equals(Object o) {
        if (!(o instanceof ImageTimestamp that)) {
            return false;
        }
        return x == that.x && y == that.y;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y);
    }

    @Override
    public String toString() {
        return "x=" + x + ", y=" + y + ", timestamp=" + timestamp;
    }
}
