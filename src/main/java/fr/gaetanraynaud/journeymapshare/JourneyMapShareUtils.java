package fr.gaetanraynaud.journeymapshare;

import net.minecraft.server.world.ServerWorld;
import org.apache.commons.lang3.tuple.Pair;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class JourneyMapShareUtils {

    public static Pair<Integer, Integer> pathToXY(Path path) {
        String filename = path.getFileName().toString();
        filename = filename.substring(0, filename.length() - 4);
        String[] pos = filename.split(",");

        return Pair.of(Integer.parseInt(pos[0]), Integer.parseInt(pos[1]));
    }

    public static List<String> worldToTypes(ServerWorld world) {
        if (world == null) {
            return List.of();
        }

        List<String> types = new ArrayList<>();
        types.add("biome");
        types.add("day");
        types.add("night");
        types.add("topo");

        for (int i = world.getDimension().minY(); i < world.getDimension().height() + world.getDimension().minY(); i += 16) {
            types.add(String.valueOf(i / 16));
        }

        return types;

    }
}
