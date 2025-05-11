package fr.gaetanraynaud.journeymapshare;

import fr.gaetanraynaud.journeymapshare.network.ImagePayload;
import fr.gaetanraynaud.journeymapshare.network.SubscribePayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.Context;
import net.minecraft.server.network.ServerPlayerEntity;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public class JourneyMapShare implements ModInitializer {

    public static final String[] WORLDS_TO_WATCH = new String[]{"overworld", "the_nether", "the_end"};

    public static final Predicate<String> FILENAME_MATCH = Pattern.compile("-?\\d+,-?\\d+\\.png").asMatchPredicate();

    public static final String MOD_ID = "journeymap-share";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private final Map<String, Map<String, Map<Integer, Integer>>> maps = new ConcurrentHashMap<>();

    private final Set<UUID> subscribedPlayers = ConcurrentHashMap.newKeySet();

    private Path location;

    @Override
    public void onInitialize() {
        //Prepare local disk
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("Started {} on server!", MOD_ID);
            LOGGER.debug("Started server in folder {}", server.getSavePath(WorldSavePath.ROOT).toAbsolutePath());
            this.location = server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().resolve("journeymap-share");
        });

        //Unsubscribe player when disconnect
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            this.subscribedPlayers.remove(handler.getPlayer().getUuid());
        });

        //Packets handlers
        PayloadTypeRegistry.playC2S().register(ImagePayload.ID, ImagePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SubscribePayload.ID, SubscribePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ImagePayload.ID,
                                                    (payload, context) -> context.server().execute(() -> processImagePayload(payload, context)));
        ServerPlayNetworking.registerGlobalReceiver(SubscribePayload.ID,
                                                    (payload, context) -> context.server().execute(() -> subscribe(context)));
    }

    private void init() {
        if (this.location == null) {
            return;
        }

        for (String world : WORLDS_TO_WATCH) {
            this.location.resolve(world).toFile().mkdirs();
        }


    }

    private void processImagePayload(ImagePayload payload, Context context) {
        LOGGER.debug("Received image payload {} from {}", payload, context.player());

        if (this.location == null) {
            return;
        }

        try {
            Path path = payload.getPath(this.location).normalize().toAbsolutePath();
            FileUtils.forceMkdirParent(path.toFile());

            synchronized (path.toString()) {
                ImagePayload returnPayload = null;
                boolean sendToSender = false;

                if (!Files.exists(path)) {
                    //If the file does not exist on server, just write it
                    Files.write(path, payload.image(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    returnPayload = payload;
                } else {
                    //If the file does exist, combine two versions and return result to the client
                    boolean payloadMoreRecent = payload.timestamp() > path.toFile().lastModified();

                    //Write the oldest image first then override with the new one
                    byte[] oldData;
                    byte[] newData;
                    byte[] serverData;
                    if (payloadMoreRecent) {
                        newData = payload.image();
                        oldData = Files.readAllBytes(path);
                        serverData = oldData;
                    } else {
                        newData = Files.readAllBytes(path);
                        oldData = payload.image();
                        serverData = newData;
                    }

                    try (ByteArrayInputStream oldImage = new ByteArrayInputStream(oldData);
                         ByteArrayInputStream newImage = new ByteArrayInputStream(newData)) {
                        BufferedImage destImage = ImageIO.read(oldImage);

                        if (destImage == null) {
                            LOGGER.error("An error occurred while processing payload {}", payload);
                            return;
                        }

                        Graphics2D graphics = destImage.createGraphics();
                        graphics.setComposite(AlphaComposite.DstOver);
                        graphics.drawImage(ImageIO.read(newImage), null, 0, 0);
                        graphics.dispose();

                        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream(payload.image().length)) {
                            ImageIO.write(destImage, "PNG", outputStream);
                            destImage.flush();
                            byte[] outArray = outputStream.toByteArray();

                            //If content is the same as the one already on the server, don't write to disk
                            if (!Arrays.equals(outArray, serverData)) {
                                Files.write(path, outArray, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                                            StandardOpenOption.CREATE);
                                returnPayload = new ImagePayload(payload.world(), payload.type(), payload.x(), payload.y(), outArray,
                                                                 path.toFile().lastModified());
                                LOGGER.debug("Updated: {}", payload);
                            }

                            sendToSender = !Arrays.equals(payload.image(), outArray);
                        }

                    }
                }

                if (returnPayload != null) {
                    UUID sender = context.player().getUuid();
                    for (ServerPlayerEntity player : PlayerLookup.all(context.server())) {
                        try {
                            if (this.subscribedPlayers.contains(player.getUuid()) && (sendToSender || !sender.equals(player.getUuid()))) {
                                ServerPlayNetworking.send(player, returnPayload);
                            }
                        } catch (Exception e) {
                            LOGGER.error("Error while sending image {} to {}", returnPayload, player, e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error while processing image {}", payload, e);
        }
    }

    private void subscribe(Context context) {
        this.subscribedPlayers.add(context.player().getUuid());
    }
}