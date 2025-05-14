package fr.gaetanraynaud.journeymapshare;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapDecoder;
import com.mojang.serialization.codecs.FieldDecoder;
import fr.gaetanraynaud.journeymapshare.network.ImagePayload;
import fr.gaetanraynaud.journeymapshare.network.SubscribePayload;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.Context;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JourneyMapShare implements ModInitializer {

    public static final String[] WORLDS_TO_WATCH = new String[] {"overworld", "the_nether", "the_end"};

    public static final Predicate<String> FILENAME_MATCH = Pattern.compile("-?\\d+,-?\\d+\\.png").asMatchPredicate();

    public static final String MOD_ID = "journeymap-share";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final Identifier DATES_IDENTIFIER = Identifier.of(MOD_ID, "last_sync_dates");

    public static final String DATES_IDENTIFIER_STING = DATES_IDENTIFIER.toString();

    public static final Codec<Map<String, Long>> DATES_CODEC = Codec.unboundedMap(Codec.STRING, Codec.LONG);

    public static final MapDecoder<Map<String, Long>> DATES_DECODER = new FieldDecoder<>(DATES_IDENTIFIER_STING, DATES_CODEC);

    private final SortedMap<Long, Map<String, Set<MapId>>> maps = new ConcurrentSkipListMap<>(Comparator.reverseOrder());

    private final Map<UUID, String> subscribedPlayers = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, ConcurrentLinkedQueue<MapId>> mapsToSend = new ConcurrentHashMap<>();

    private Path location;

    @Override
    public void onInitialize() {
        //Prepare local disk
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("Started {} on server!", MOD_ID);
            LOGGER.debug("Started server in folder {}", server.getSavePath(WorldSavePath.ROOT).toAbsolutePath());
            this.location = server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().resolve("journeymap-share");
            init();
        });

        //Unsubscribe player when disconnect
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> unsubscribe(handler.player));

        //Register player changing dimension to send him out of date images for this dimension
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
            if (this.subscribedPlayers.containsKey(player.getUuid())) {
                if (!origin.equals(destination)) {
                    registerPlayerForWorld(player, JourneyMapShareUtils.serverWorldToWorldName(destination));
                }
            }
        });

        //Register a task to send 1 packet at each tick
        ServerTickEvents.END_SERVER_TICK.register(this::sendNextPacket);

        //Packets handlers
        PayloadTypeRegistry.playC2S().register(ImagePayload.ID, ImagePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SubscribePayload.ID, SubscribePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ImagePayload.ID,
                                                    (payload, context) -> context.server().execute(() -> processImagePayload(payload, context)));
        ServerPlayNetworking.registerGlobalReceiver(SubscribePayload.ID, (payload, context) -> context.server().execute(() -> subscribe(context)));
    }

    private void init() {
        if (this.location == null) {
            return;
        }

        for (String world : WORLDS_TO_WATCH) {
            this.location.resolve(world).toFile().mkdirs();
        }

        try (ExecutorService executorService = Executors.newSingleThreadExecutor()) {
            executorService.submit(() -> {
                for (String world : WORLDS_TO_WATCH) {
                    try (Stream<Path> stream = Files.walk(this.location.resolve(world))) {
                        stream.filter(Files::isRegularFile).filter(path -> FILENAME_MATCH.test(path.getFileName().toString())).forEach(path -> {
                            long lastModified = path.toFile().lastModified();
                            String filename = path.getFileName().toString();
                            filename = filename.substring(0, filename.length() - 4);
                            String[] pos = filename.split(",");

                            MapId mapId = new MapId(world, path.getParent().getFileName().toString(), Integer.parseInt(pos[0]), Integer.parseInt(pos[1]));
                            this.maps.computeIfAbsent(lastModified, aLong -> new ConcurrentHashMap<>())
                                     .computeIfAbsent(mapId.world(), s -> ConcurrentHashMap.newKeySet())
                                     .add(new MapId(world, path.getParent().getFileName().toString(), Integer.parseInt(pos[0]), Integer.parseInt(pos[1])));
                        });
                    } catch (IOException e) {
                        LOGGER.error("An error occurred while listing files", e);
                    }
                }
            });
        }
    }

    private void processImagePayload(ImagePayload payload, Context context) {
        LOGGER.debug("Received image payload {} from {}", payload, context.player());

        if (this.location == null) {
            return;
        }

        try {
            Path path = payload.getPath(this.location);
            FileUtils.forceMkdirParent(path.toFile());

            synchronized (path.toString()) {
                boolean changed = false;
                boolean sendToSender = false;

                if (!Files.exists(path)) {
                    //If the file does not exist on server, just write it
                    Files.write(path, payload.getImage(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    changed = true;
                    LOGGER.debug("Created: {}", payload);
                } else {
                    //If the file does exist, combine two versions and return result to the client
                    boolean payloadMoreRecent = payload.getTimestamp() > path.toFile().lastModified();

                    //Write the oldest image first then override with the new one
                    byte[] oldData;
                    byte[] newData;
                    byte[] serverData;
                    if (payloadMoreRecent) {
                        newData = payload.getImage();
                        oldData = Files.readAllBytes(path);
                        serverData = oldData;
                    } else {
                        newData = Files.readAllBytes(path);
                        oldData = payload.getImage();
                        serverData = newData;
                    }

                    try (ByteArrayInputStream oldImage = new ByteArrayInputStream(oldData); ByteArrayInputStream newImage = new ByteArrayInputStream(newData)) {
                        BufferedImage destImage = ImageIO.read(oldImage);

                        if (destImage == null) {
                            LOGGER.error("An error occurred while processing payload {}", payload);
                            return;
                        }

                        Graphics2D graphics = destImage.createGraphics();
                        graphics.setComposite(AlphaComposite.DstOver);
                        graphics.drawImage(ImageIO.read(newImage), null, 0, 0);
                        graphics.dispose();

                        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream(payload.getImage().length)) {
                            ImageIO.write(destImage, "PNG", outputStream);
                            destImage.flush();
                            byte[] outArray = outputStream.toByteArray();

                            //If content is the same as the one already on the server, don't write to disk
                            if (!Arrays.equals(outArray, serverData)) {
                                Files.write(path, outArray, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
                                changed = true;
                                LOGGER.debug("Updated: {}", payload);
                            }

                            sendToSender = !Arrays.equals(payload.getImage(), outArray);
                        }

                    }
                }

                if (changed) {
                    payload.setTimestamp(path.toFile().lastModified());
                    this.maps.computeIfAbsent(payload.getTimestamp(), aLong -> new ConcurrentHashMap<>())
                             .computeIfAbsent(payload.getWorld(), s -> ConcurrentHashMap.newKeySet())
                             .add(payload.getMapId());
                    UUID sender = context.player().getUuid();
                    for (ServerPlayerEntity player : PlayerLookup.all(context.server())) {
                        try {
                            if ((sendToSender || !sender.equals(player.getUuid())) && this.subscribedPlayers.containsKey(player.getUuid()) &&
                                this.subscribedPlayers.get(player.getUuid()).equals(payload.getWorld())) {
                                this.mapsToSend.computeIfAbsent(player.getUuid(), e -> new ConcurrentLinkedQueue<>()).add(payload.getMapId());
                            }
                        } catch (Exception e) {
                            LOGGER.error("Error while sending image {} to {}", payload, player, e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error while processing image {}", payload, e);
        }
    }

    /**
     * Init action when a player with the mod connects.
     */
    private void subscribe(Context context) {
        registerPlayerForWorld(context.player(), JourneyMapShareUtils.serverWorldToWorldName(context.player().getServerWorld()));
    }

    private void unsubscribe(ServerPlayerEntity player) {
        this.subscribedPlayers.remove(player.getUuid());
        this.mapsToSend.remove(player.getUuid());
    }

    private void registerPlayerForWorld(ServerPlayerEntity player, String world) {
        this.subscribedPlayers.put(player.getUuid(), world);

        Map<String, Long> dates = getMapForPlayer(player);

        if (dates.containsKey(world)) {
            ConcurrentLinkedQueue<MapId> queue = this.mapsToSend.computeIfAbsent(player.getUuid(), p -> new ConcurrentLinkedQueue<>());
            for (Map<String, Set<MapId>> map : this.maps.headMap(dates.get(world)).values()) {
                if (map.containsKey(world)) {
                    queue.addAll(map.get(world));
                }
            }
        }
    }

    /**
     * Pick the first map to send in the first element in the list to send, and send it to all players waiting for it.
     */
    private void sendNextPacket(MinecraftServer server) {
        MapId mapId = null;
        for (Map.Entry<UUID, ConcurrentLinkedQueue<MapId>> entry : this.mapsToSend.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                mapId = entry.getValue().peek();
                break;
            }
        }

        if (mapId != null) {
            try {
                Path path = mapId.getPath(this.location);
                ImagePayload payload = new ImagePayload(mapId.world(), mapId.type(), mapId.x(), mapId.y(), Files.readAllBytes(path),
                                                        path.toFile().lastModified());
                for (Map.Entry<UUID, ConcurrentLinkedQueue<MapId>> entry : this.mapsToSend.entrySet()) {
                    if (entry.getValue().contains(mapId)) {
                        entry.getValue().remove(mapId);
                        ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());

                        if (player != null) {
                            LOGGER.debug("Sent image {} to {}", payload, player);
                            ServerPlayNetworking.send(player, payload);
                            updateDateForPlayer(player, payload.getWorld(), payload.getTimestamp());
                        }
                    }

                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private NbtComponent initNbtForPlayer(ServerPlayerEntity player) {
        NbtComponent nbt = player.get(DataComponentTypes.CUSTOM_DATA);

        if (nbt == null) {
            nbt = NbtComponent.of(new NbtCompound());
        }

        if (!nbt.contains(DATES_IDENTIFIER_STING)) {
            nbt = nbt.apply(nbtCompound -> nbtCompound.put(DATES_IDENTIFIER_STING, DATES_CODEC,
                                                           Arrays.stream(WORLDS_TO_WATCH).collect(Collectors.toMap(Function.identity(), s -> 0L))));
            player.setComponent(DataComponentTypes.CUSTOM_DATA, nbt);
        }

        return nbt;
    }

    private Map<String, Long> getMapForPlayer(ServerPlayerEntity player) {
        return getMapForPlayer(player, false);
    }

    private Map<String, Long> getMapForPlayer(ServerPlayerEntity player, boolean writeableCopy) {
        Map<String, Long> map = initNbtForPlayer(player).get(DATES_DECODER).getOrThrow();

        return writeableCopy ? new Object2ObjectOpenHashMap<>(map) : map;
    }

    private void updateDateForPlayer(ServerPlayerEntity player, String word, long timestamp) {
        NbtComponent nbt = initNbtForPlayer(player);
        Map<String, Long> dates = getMapForPlayer(player, true);
        dates.put(word, timestamp);
        player.setComponent(DataComponentTypes.CUSTOM_DATA, nbt.apply(nbtCompound -> nbtCompound.put(DATES_IDENTIFIER_STING, DATES_CODEC, dates)));
    }
}
