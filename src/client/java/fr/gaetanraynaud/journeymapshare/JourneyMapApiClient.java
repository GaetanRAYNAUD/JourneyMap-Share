package fr.gaetanraynaud.journeymapshare;

import com.sun.nio.file.ExtendedWatchEventModifier;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.JourneyMapPlugin;
import journeymap.api.v2.client.event.MappingEvent;
import journeymap.api.v2.common.event.ClientEventRegistry;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@JourneyMapPlugin(apiVersion = "2.0.0")
public class JourneyMapApiClient implements IClientPlugin {

    private IClientAPI jmClientApi;

    private Path location;

    private WatchService watchService;

    private ExecutorService executor;

    @Override
    public void initialize(IClientAPI jmClientApi) {
        this.jmClientApi = jmClientApi;
        ClientEventRegistry.MAPPING_EVENT.subscribe(JourneyMapShare.MOD_ID, this::mappingStageEvent);
    }

    @Override
    public String getModId() {
        return JourneyMapShare.MOD_ID;
    }

    private void mappingStageEvent(MappingEvent event) {
        if (event.getStage() == MappingEvent.Stage.MAPPING_STARTED) {
            JourneyMapShare.LOGGER.info("Mapping started");
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
                    JourneyMapShare.LOGGER.info("WatchService closed");
                } catch (IOException e) {
                    JourneyMapShare.LOGGER.error("An error occurred while closing watch service", e);
                }
            }

            if (this.executor != null) {
                this.executor.shutdownNow();
                JourneyMapShare.LOGGER.info("Executor shutdown");
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

                if (paths.isEmpty()) {
                    continue;
                }

                for (Path path : paths) {
                    JourneyMapShare.LOGGER.info("Watch event received for png: {}", path);
                }

                paths.clear();
                key.reset();
            }
        });

    }
}
