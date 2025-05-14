package fr.gaetanraynaud.journeymapshare;

import java.nio.file.Path;

public record MapId(String world, String type, int x, int y) {

    public Path getPath(Path rootPath) {
        return rootPath.resolve(this.world).resolve(this.type).resolve(this.x + "," + this.y + ".png");
    }
}
