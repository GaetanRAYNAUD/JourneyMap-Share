package fr.gaetanraynaud.journeymapshare;

import fr.gaetanraynaud.journeymapshare.network.ImagePayload;
import fr.gaetanraynaud.journeymapshare.network.ImageTimestamp;
import fr.gaetanraynaud.journeymapshare.network.ImagesListPayload;
import fr.gaetanraynaud.journeymapshare.network.ImagesMetaListPayload;
import fr.gaetanraynaud.journeymapshare.network.SubscribePayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.Context;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.World;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.Pair;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JourneyMapShare implements ModInitializer {

    public static final Map<String, RegistryKey<World>> WORLDS_TO_WATCH = Stream.of(ServerWorld.OVERWORLD, ServerWorld.NETHER, ServerWorld.END)
                                                                                .collect(Collectors.toMap(k -> k.getValue().getPath(), Function.identity()));

    public static final Predicate<String> FILENAME_MATCH = Pattern.compile("-?\\d+,-?\\d+\\.png").asMatchPredicate();

    public static final String MOD_ID = "journeymap-share";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private final Map<RegistryKey<World>, Map<String, Set<ImageTimestamp>>> maps = WORLDS_TO_WATCH.values()
                                                                                                  .stream()
                                                                                                  .collect(Collectors.toMap(Function.identity(),
                                                                                                                            s -> new ConcurrentHashMap<>()));

    private final Map<UUID, RegistryKey<World>> subscribedPlayers = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, ConcurrentLinkedQueue<MapId>> mapsToSend = new ConcurrentHashMap<>();

    private boolean init = false;

    private Path location;

    @Override
    public void onInitialize() {
        //Prepare local disk
        ServerLifecycleEvents.SERVER_STARTED.register(this::init);

        //Unsubscribe player when disconnect
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> unsubscribe(handler.player));

        //For singleplayer
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> cleanup());

        //Register player changing dimension to send him out of date images for this dimension
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(this::playerChangeWorld);

        //Register a task to send 1 packet at each tick
        ServerTickEvents.END_SERVER_TICK.register(this::sendNextPacket);

        //Packets handlers
        PayloadTypeRegistry.playC2S().register(ImagePayload.ID, ImagePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SubscribePayload.ID, SubscribePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ImagesMetaListPayload.ID, ImagesMetaListPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ImagesListPayload.ID, ImagesListPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ImagePayload.ID,
                                                    (payload, context) -> context.server().execute(() -> processImagePayload(payload, context)));
        ServerPlayNetworking.registerGlobalReceiver(SubscribePayload.ID, (payload, context) -> context.server().execute(() -> subscribe(context, payload)));
        ServerPlayNetworking.registerGlobalReceiver(ImagesListPayload.ID,
                                                    (payload, context) -> context.server().execute(() -> handleListRequest(context, payload)));
    }

    private void init(MinecraftServer server) {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment() && !server.isDedicated()) {
            cleanup();
            return;
        }

        this.location = server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().resolve("journeymap-share");
        this.init = true;
        LOGGER.info("Started {} on server in folder {}!", MOD_ID, this.location);

        try (ExecutorService executorService = Executors.newSingleThreadExecutor()) {
            executorService.submit(() -> {
                for (Map.Entry<String, RegistryKey<World>> world : WORLDS_TO_WATCH.entrySet()) {
                    Path worldPath = this.location.resolve(world.getKey());
                    worldPath.toFile().mkdirs();

                    for (String type : JourneyMapShareUtils.worldToTypes(server.getWorld(world.getValue()))) {
                        worldPath.resolve(type).toFile().mkdir();
                    }

                    try (Stream<Path> stream = Files.walk(worldPath)) {
                        stream.filter(Files::isRegularFile).filter(path -> FILENAME_MATCH.test(path.getFileName().toString())).forEach(path -> {
                            Pair<Integer, Integer> pos = JourneyMapShareUtils.pathToXY(path);

                            MapId mapId = new MapId(world.getKey(), path.getParent().getFileName().toString(), pos.getLeft(), pos.getRight());
                            this.maps.get(world.getValue())
                                     .computeIfAbsent(mapId.type(), s -> ConcurrentHashMap.newKeySet())
                                     .add(new ImageTimestamp(pos.getLeft(), pos.getRight(), path.toFile().lastModified()));
                        });
                    } catch (IOException e) {
                        LOGGER.error("An error occurred while listing files", e);
                    }
                }
            });
        }
    }

    private void cleanup() {
        this.location = null;
        this.mapsToSend.clear();
        this.subscribedPlayers.clear();
        for (Map<String, Set<ImageTimestamp>> map : this.maps.values()) {
            map.clear();
        }
    }

    private void processImagePayload(ImagePayload payload, Context context) {
        LOGGER.debug("Received image payload {} from {}", payload, context.player());

        if (!this.init || this.location == null) {
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
                    Map<String, Set<ImageTimestamp>> map = this.maps.get(WORLDS_TO_WATCH.get(payload.getWorld()));

                    if (map == null) {
                        return;
                    }

                    map.computeIfAbsent(payload.getType(), s -> ConcurrentHashMap.newKeySet())
                       .add(payload.getImageTimestamp());
                    UUID sender = context.player().getUuid();
                    for (ServerPlayerEntity player : PlayerLookup.all(context.server())) {
                        try {
                            if ((sendToSender || !sender.equals(player.getUuid())) && this.subscribedPlayers.containsKey(player.getUuid()) &&
                                this.subscribedPlayers.get(player.getUuid()).equals(WORLDS_TO_WATCH.get(payload.getWorld()))) {
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
     * Init action when a player with the mod connects. Check if config is compatible before.
     */
    private void subscribe(Context context, SubscribePayload payload) {
        if (!this.init) {
            return;
        }

        boolean valid = true;
        valid &= "true".equals(payload.config().get("mapBathymetry"));
        valid &= "true".equals(payload.config().get("mapWaterBiomeColors"));
        valid &= "true".equals(payload.config().get("ignoreHeightmaps"));
        valid &= "true".equals(payload.config().get("mapTransparency"));
        valid &= "true".equals(payload.config().get("mapCaveLighting"));
        valid &= "true".equals(payload.config().get("mapAntialiasing"));
        valid &= "false".equals(payload.config().get("mapPlantShadows"));
        valid &= "true".equals(payload.config().get("mapShadows"));
        valid &= "false".equals(payload.config().get("mapPlants"));
        valid &= "false".equals(payload.config().get("mapCrops"));
        valid &= "true".equals(payload.config().get("mapBlendGrass"));
        valid &= "true".equals(payload.config().get("mapBlendFoliage"));
        valid &= "true".equals(payload.config().get("mapBlendWater"));

        if (valid) {
            registerPlayerForWorld(context.player(), context.player().getEntityWorld().getRegistryKey());
        } else {
            this.subscribedPlayers.remove(context.player().getUuid());
        }
    }

    private void unsubscribe(ServerPlayerEntity player) {
        if (!this.init) {
            return;
        }

        this.subscribedPlayers.remove(player.getUuid());
        this.mapsToSend.remove(player.getUuid());
    }

    private void playerChangeWorld(ServerPlayerEntity player, ServerWorld origin, ServerWorld destination) {
        if (!this.init) {
            return;
        }

        if (this.subscribedPlayers.containsKey(player.getUuid())) {
            if (!origin.equals(destination)) {
                registerPlayerForWorld(player, destination.getRegistryKey());
            }
        }
    }

    private void registerPlayerForWorld(ServerPlayerEntity player, RegistryKey<World> world) {
        this.subscribedPlayers.put(player.getUuid(), world);

        for (String type : JourneyMapShareUtils.worldToTypes(player.getEntityWorld())) {
            ServerPlayNetworking.send(player, new ImagesMetaListPayload(world.getValue().getPath(), type, this.maps.get(world).get(type)));
        }
    }

    /**
     * Pick the first map to send in the first element in the list to send, and send it to all players waiting for it.
     */
    private void sendNextPacket(MinecraftServer server) {
        if (!this.init) {
            return;
        }

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
                        }
                    }

                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void handleListRequest(Context context, ImagesListPayload payload) {
        if (!this.init) {
            return;
        }

        ConcurrentLinkedQueue<MapId> queue = this.mapsToSend.computeIfAbsent(context.player().getUuid(), p -> new ConcurrentLinkedQueue<>());

        for (Map.Entry<Integer, List<Integer>> entry : payload.getImages().entrySet()) {
            for (Integer y : entry.getValue()) {
                queue.add(new MapId(payload.getWorld(), payload.getType(), entry.getKey(), y));
            }
        }
    }
}
