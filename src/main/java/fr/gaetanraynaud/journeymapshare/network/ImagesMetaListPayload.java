package fr.gaetanraynaud.journeymapshare.network;

import fr.gaetanraynaud.journeymapshare.JourneyMapShare;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Packet with the list of images on disk for a type and world, used for C2S and S2C.
 */
public final class ImagesMetaListPayload implements CustomPayload {

    public static final Id<ImagesMetaListPayload> ID = new Id<>(Identifier.of(JourneyMapShare.MOD_ID, "images_meta_list"));

    public static final PacketCodec<RegistryByteBuf, ImagesMetaListPayload> CODEC = PacketCodec.tuple(PacketCodecs.STRING, ImagesMetaListPayload::getWorld,
                                                                                                      PacketCodecs.STRING, ImagesMetaListPayload::getType,
                                                                                                      ImageTimestamp.CODEC.collect(PacketCodecs.toList()),
                                                                                                      ImagesMetaListPayload::getImages,
                                                                                                      ImagesMetaListPayload::new);
    private final String world;

    private final String type;

    private final List<ImageTimestamp> images;

    public ImagesMetaListPayload(String world, String type, List<ImageTimestamp> images) {
        this.world = world;
        this.type = type;
        this.images = images;
    }

    public ImagesMetaListPayload(String world, String type, Set<ImageTimestamp> images) {
        this(world, type, images == null ? List.of() : new ArrayList<>(images));
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

    public List<ImageTimestamp> getImages() {
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
