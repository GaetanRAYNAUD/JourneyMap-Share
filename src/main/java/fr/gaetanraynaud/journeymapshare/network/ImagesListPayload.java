package fr.gaetanraynaud.journeymapshare.network;

import fr.gaetanraynaud.journeymapshare.JourneyMapShare;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Packet with the list of images to ask, used for C2S.
 */
public final class ImagesListPayload implements CustomPayload {

    public static final Id<ImagesListPayload> ID = new Id<>(Identifier.of(JourneyMapShare.MOD_ID, "images_list"));

    public static final PacketCodec<RegistryByteBuf, ImagesListPayload> CODEC = PacketCodec.tuple(PacketCodecs.STRING, ImagesListPayload::getWorld,
                                                                                                  PacketCodecs.STRING, ImagesListPayload::getType,
                                                                                                  PacketCodecs.map(HashMap::new, PacketCodecs.INTEGER,
                                                                                                                   PacketCodecs.INTEGER.collect(
                                                                                                                           PacketCodecs.toList())),
                                                                                                  ImagesListPayload::getImages, ImagesListPayload::new);
    private final String world;

    private final String type;

    private final Map<Integer, List<Integer>> images;

    public ImagesListPayload(String world, String type, Map<Integer, List<Integer>> images) {
        this.world = world;
        this.type = type;
        this.images = images;
    }

    public Path getPath(Path rootPath) {
        return rootPath.resolve(this.world).resolve(this.type).normalize().toAbsolutePath();
    }

    public String getWorld() {
        return world;
    }

    public String getType() {
        return type;
    }

    public Map<Integer, List<Integer>> getImages() {
        return images;
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    @Override
    public String toString() {
        return "world=" + world + ", type='" + type + ", images=" + images.size();
    }
}
