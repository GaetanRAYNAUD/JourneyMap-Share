package fr.gaetanraynaud.journeymapshare;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public class JourneyMapShare implements ModInitializer {

    public static final String[] WORLDS_TO_WATCH = new String[]{"overworld", "the_nether", "the_end"};

    public static final Predicate<String> FILENAME_MATCH = Pattern.compile("-?\\d+,-?\\d+\\.png").asMatchPredicate();

    public static final String MOD_ID = "journeymap-share";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private Path location;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("Started JourneyMapShare on server!");
            LOGGER.info("Started server in folder {}", server.getSavePath(WorldSavePath.ROOT).toAbsolutePath());
            this.location = server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().resolve("journeymap-share");

            for (String world : WORLDS_TO_WATCH) {
                this.location.resolve(world).toFile().mkdirs();
            }
        });
    }
}