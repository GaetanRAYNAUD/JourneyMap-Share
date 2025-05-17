package fr.gaetanraynaud.journeymapshare;

import com.google.gson.Gson;
import com.sun.nio.file.ExtendedWatchEventModifier;
import fr.gaetanraynaud.journeymapshare.network.ImagePayload;
import fr.gaetanraynaud.journeymapshare.network.ImageTimestamp;
import fr.gaetanraynaud.journeymapshare.network.ImagesListPayload;
import fr.gaetanraynaud.journeymapshare.network.ImagesMetaListPayload;
import fr.gaetanraynaud.journeymapshare.network.SubscribePayload;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.JourneyMapPlugin;
import journeymap.api.v2.client.event.MappingEvent;
import journeymap.api.v2.common.event.ClientEventRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.impl.util.version.VersionParser;
import net.minecraft.client.MinecraftClient;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@JourneyMapPlugin(apiVersion = "2.0.0")
public class JourneyMapApiClient implements IClientPlugin {

    private final Set<Path> processedPaths = ConcurrentHashMap.newKeySet();

    private final ConcurrentLinkedQueue<MapId> mapsToSend = new ConcurrentLinkedQueue<>();

    private IClientAPI jmClientApi;

    private Path location;

    private WatchService watchService;

    private ExecutorService executor;

    private boolean init;

    @Override
    public void initialize(IClientAPI jmClientApi) {
        this.jmClientApi = jmClientApi;

        ClientPlayNetworking.registerGlobalReceiver(ImagePayload.ID, (payload, context) -> context.client().execute(() -> processImagePayload(payload)));
        ClientPlayNetworking.registerGlobalReceiver(ImagesMetaListPayload.ID,
                                                    (payload, context) -> context.client().execute(() -> processImagesListPayload(payload)));

        //Init
        ClientEventRegistry.MAPPING_EVENT.subscribe(JourneyMapShare.MOD_ID, this::mappingStageEvent);

        //Stop watching files when disconnecting
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(this::cleanUp));

        //Register a task to send 1 packet at each tick
        ClientTickEvents.END_WORLD_TICK.register(world -> sendNextPacket());
    }

    @Override
    public String getModId() {
        return JourneyMapShare.MOD_ID;
    }

    private void mappingStageEvent(MappingEvent event) {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment() && MinecraftClient.getInstance().isInSingleplayer()) {
            cleanUp();
            return;
        }

        //Init when starting mapping
        if (event.getStage() == MappingEvent.Stage.MAPPING_STARTED) {
            if (this.location == null || !this.location.equals(this.jmClientApi.getDataPath(JourneyMapShare.MOD_ID).getParentFile().getParentFile().toPath())) {
                JourneyMapShare.LOGGER.info("Starting {} on the client", JourneyMapShare.MOD_ID);
                this.location = this.jmClientApi.getDataPath(JourneyMapShare.MOD_ID).getParentFile().getParentFile().toPath();

                try {
                    initConfig();
                } catch (IOException e) {
                    JourneyMapShare.LOGGER.error("An error occurred while initialising", e);
                }
            }
        }
    }

    private void initConfig() throws IOException {
        if (this.location == null || !Files.exists(this.location)) {
            return;
        }

        Map config;
        Path configsFolder = this.location.getParent().getParent().getParent().resolve("config");
        try (Stream<Path> stream = Files.list(configsFolder)) {
            Version version = stream.map(p -> {
                try {
                    return VersionParser.parse(p.getFileName().toString(), true);
                } catch (VersionParsingException e) {
                    throw new RuntimeException(e);
                }
            }).sorted(Comparator.reverseOrder()).limit(1).toList().getFirst();

            Path configFile = configsFolder.resolve(version.toString()).resolve("journeymap.core.config");
            config = new Gson().fromJson(Files.newBufferedReader(configFile), Map.class);
            config.values().removeIf(s -> !(s instanceof String));
        }

        //Tell the server that we have this mod installed
        ClientPlayNetworking.send(new SubscribePayload(new HashMap<String, String>(config)));
    }

    private void processImagePayload(ImagePayload payload) {
        JourneyMapShare.LOGGER.debug("Received image payload {} from server", payload);

        if (!this.init || this.location == null) {
            return;
        }

        try {
            Path path = payload.getPath(this.location);
            FileUtils.forceMkdirParent(path.toFile());

            synchronized (path.toString()) {
                //Write the file if it does not exist or the server version is more recent
                if (!Files.exists(path) || payload.getTimestamp() > path.toFile().lastModified()) {
                    this.processedPaths.add(path);
                    Files.write(path, payload.getImage(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                }
            }
        } catch (Exception e) {
            JourneyMapShare.LOGGER.error("Error while processing image {}", payload, e);
        }
    }

    private synchronized void processImagesListPayload(ImagesMetaListPayload payload) {
        if (!this.init) {
            try {
                initWorld();
            } catch (Exception e) {
                JourneyMapShare.LOGGER.error("Error while init world {}", payload, e);
            }
        }

        JourneyMapShare.LOGGER.debug("Received image list payload {}", payload);

        Path folder = payload.getPath(this.location);
        Map<Integer, List<Integer>> toAsk = new HashMap<>();

        if (!Files.exists(folder)) {
            folder.toFile().mkdirs();

            for (ImageTimestamp image : payload.getImages()) {
                toAsk.computeIfAbsent(image.getX(), x -> new ArrayList<>()).add(image.getY());
            }
        } else {
            AtomicInteger nbSending = new AtomicInteger();
            Map<Path, Long> toCheck = payload.getImages()
                                             .stream()
                                             .map(i -> i.getPath(folder))
                                             .collect(Collectors.toMap(Function.identity(), p -> p.toFile().lastModified()));

            try (Stream<Path> stream = Files.list(folder)) {
                stream.filter(Files::isRegularFile).forEach(path -> {
                    Pair<Integer, Integer> pos = JourneyMapShareUtils.pathToXY(path);

                    if (toCheck.containsKey(path) && toCheck.get(path) > path.toFile().lastModified()) {
                        toAsk.computeIfAbsent(pos.getKey(), x -> new ArrayList<>()).add(pos.getValue());
                    } else if (!toCheck.containsKey(path) || toCheck.get(path) < path.toFile().lastModified()) {
                        this.mapsToSend.add(new MapId(payload.getWorld(), payload.getType(), pos.getKey(), pos.getValue()));
                        nbSending.getAndIncrement();
                    }
                });
            } catch (Exception e) {
                JourneyMapShare.LOGGER.error("Error while processing image list {}", payload, e);
            }

            if (nbSending.get() > 0) {
                JourneyMapShare.LOGGER.debug("Sending {} images", nbSending.get());
            }
        }

        if (!toAsk.isEmpty()) {
            JourneyMapShare.LOGGER.debug("Asking for {} images", toAsk.values().stream().mapToInt(List::size).sum());
            ClientPlayNetworking.send(new ImagesListPayload(payload.getWorld(), payload.getType(), toAsk));
        }
    }

    private void initWorld() throws IOException {
        this.init = true;
        this.watchService = FileSystems.getDefault().newWatchService();
        for (String world : JourneyMapShare.WORLDS_TO_WATCH.keySet()) {
            Path path = this.location.resolve(world).normalize().toAbsolutePath();
            path.toFile().mkdirs();
            path.register(this.watchService, new WatchEvent.Kind[] {StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE},
                          ExtendedWatchEventModifier.FILE_TREE);
            JourneyMapShare.LOGGER.debug("Starting watching: {}", path);
        }

        this.executor = Executors.newSingleThreadExecutor();
        this.executor.submit(() -> {
            Set<Path> paths = new HashSet<>();

            while (true) {
                WatchKey key = this.watchService.take();
                Path folder = (Path) key.watchable();

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.context() instanceof Path path) {
                        if (JourneyMapShare.FILENAME_MATCH.test(path.getFileName().toString())) {
                            path = folder.resolve(path).normalize().toAbsolutePath();
                            paths.add(path);
                        }
                    }
                }

                key.reset();

                if (paths.isEmpty()) {
                    continue;
                }

                String worldName = folder.getFileName().toString();

                for (Path path : paths) {
                    //To prevent an infinite loop, because when we receive an image from the server, we write to disk so it triggers a change event.
                    if (this.processedPaths.contains(path)) {
                        continue;
                    }

                    try {
                        JourneyMapShare.LOGGER.debug("Watch event received for image: {}", path);
                        Pair<Integer, Integer> pos = JourneyMapShareUtils.pathToXY(path);
                        this.mapsToSend.add(new MapId(worldName, path.getParent().getFileName().toString(), pos.getKey(), pos.getValue()));
                    } catch (Exception e) {
                        JourneyMapShare.LOGGER.error("Error while sending image {}", path, e);
                    }
                }

                this.processedPaths.removeAll(paths);
                paths.clear();
            }
        });
    }

    /**
     * Pick the first map to send in the list to send, and send it to the server.
     */
    private void sendNextPacket() {
        try {
            if (this.init && this.location != null && !this.mapsToSend.isEmpty()) {
                MapId mapId = this.mapsToSend.poll();
                Path path = mapId.getPath(this.location);
                ImagePayload payload = new ImagePayload(mapId.world(), mapId.type(), mapId.x(), mapId.y(), Files.readAllBytes(path),
                                                        path.toFile().lastModified());
                ClientPlayNetworking.send(payload);
            }
        } catch (IOException e) {
            JourneyMapShare.LOGGER.error("Error while sending image", e);
        }
    }

    private void cleanUp() {
        this.init = false;
        if (this.watchService != null) {
            try {
                this.watchService.close();
                JourneyMapShare.LOGGER.debug("WatchService closed");
            } catch (IOException e) {
                JourneyMapShare.LOGGER.error("An error occurred while closing watch service", e);
            }
        }

        if (this.executor != null) {
            this.executor.shutdownNow();
            JourneyMapShare.LOGGER.debug("Executor shutdown");
        }

        this.mapsToSend.clear();
        this.location = null;
    }
}
