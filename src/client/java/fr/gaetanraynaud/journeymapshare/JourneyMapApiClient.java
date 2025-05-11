package fr.gaetanraynaud.journeymapshare;

import com.sun.nio.file.ExtendedWatchEventModifier;
import fr.gaetanraynaud.journeymapshare.network.ImagePayload;
import fr.gaetanraynaud.journeymapshare.network.SubscribePayload;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.JourneyMapPlugin;
import journeymap.api.v2.client.event.MappingEvent;
import journeymap.api.v2.common.event.ClientEventRegistry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.apache.commons.io.FileUtils;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentHashMap.KeySetView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@JourneyMapPlugin(apiVersion = "2.0.0")
public class JourneyMapApiClient implements IClientPlugin {

    private final Set<Path> processedPaths = ConcurrentHashMap.newKeySet();

    private IClientAPI jmClientApi;

    private Path location;

    private WatchService watchService;

    private ExecutorService executor;

    @Override
    public void initialize(IClientAPI jmClientApi) {
        this.jmClientApi = jmClientApi;
        ClientEventRegistry.MAPPING_EVENT.subscribe(JourneyMapShare.MOD_ID, this::mappingStageEvent);
        ClientPlayNetworking.registerGlobalReceiver(ImagePayload.ID,
                                                    (payload, context) -> context.client().execute(() -> processImagePayload(payload)));
    }

    @Override
    public String getModId() {
        return JourneyMapShare.MOD_ID;
    }

    private void mappingStageEvent(MappingEvent event) {
        if (event.getStage() == MappingEvent.Stage.MAPPING_STARTED) {
            JourneyMapShare.LOGGER.info("Starting {} on the client", JourneyMapShare.MOD_ID);
            this.location = this.jmClientApi.getDataPath(JourneyMapShare.MOD_ID).getParentFile().getParentFile().toPath();

            try {
                init();
            } catch (IOException e) {
                JourneyMapShare.LOGGER.error("An error occurred while initialising", e);
            }
        } else {
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
        }
    }

    private void init() throws IOException {
        if (this.location == null || !Files.exists(this.location)) {
            return;
        }

        this.watchService = FileSystems.getDefault().newWatchService();
        for (String world : JourneyMapShare.WORLDS_TO_WATCH) {
            this.location.resolve(world)
                         .toAbsolutePath()
                         .register(this.watchService, new WatchEvent.Kind[]{StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE},
                                   ExtendedWatchEventModifier.FILE_TREE);
            JourneyMapShare.LOGGER.debug("Starting watching: {}", this.location.resolve(world).toAbsolutePath());
        }

        this.executor = Executors.newSingleThreadExecutor();
        this.executor.submit(() -> {
            while (true) {
                WatchKey key = this.watchService.take();
                Path folder = (Path) key.watchable();
                Set<Path> paths = new HashSet<>();

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.context() instanceof Path path) {
                        path = folder.resolve(path).normalize().toAbsolutePath();
                        if (JourneyMapShare.FILENAME_MATCH.test(path.getFileName().toString())) {
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
                    //To prevent infinite loop where because we received an image from the server, we write to disk so it triggers a change event.
                    if (this.processedPaths.contains(path)) {
                        continue;
                    }

                    try {
                        JourneyMapShare.LOGGER.debug("Watch event received for image: {}", path);
                        String filename = path.getFileName().toString();
                        filename = filename.substring(0, filename.length() - 4);
                        String[] pos = filename.split(",");

                        ClientPlayNetworking.send(
                                new ImagePayload(worldName, path.getParent().getFileName().toString(), Integer.parseInt(pos[0]), Integer.parseInt(pos[1]),
                                                 Files.readAllBytes(path), path.toFile().lastModified()));
                    } catch (FileSystemException e) {
                        //ignored because it is most likely an infinite loop
                    } catch (Exception e) {
                        JourneyMapShare.LOGGER.error("Error while sending image {}", path, e);
                    }
                }

                this.processedPaths.removeAll(paths);
                paths.clear();
            }
        });
        ClientPlayNetworking.send(new SubscribePayload(false));
    }

    private void processImagePayload(ImagePayload payload) {
        JourneyMapShare.LOGGER.debug("Received image payload {} from server", payload);

        if (this.location == null) {
            return;
        }

        try {
            Path path = payload.getPath(this.location).normalize().toAbsolutePath();
            FileUtils.forceMkdirParent(path.toFile());

            synchronized (path.toString()) {
                //Write the file if it does not exist or the server version is more recent
                if (!Files.exists(path) || payload.timestamp() > path.toFile().lastModified()) {
                    this.processedPaths.add(path);
                    Files.write(path, payload.image(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                }
            }
        } catch (Exception e) {
            JourneyMapShare.LOGGER.error("Error while processing image {}", payload, e);
        }
    }
}
