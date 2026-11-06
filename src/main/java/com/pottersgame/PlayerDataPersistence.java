package com.pottersgame;

import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueOutput;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** A checked player-data commit: vanilla's save method only logs write failures. */
public final class PlayerDataPersistence {
    private PlayerDataPersistence() {}

    public static void save(ServerPlayer player) {
        ProblemReporter strict = new ProblemReporter() {
            @Override
            public ProblemReporter forChild(PathElement path) { return this; }

            @Override
            public void report(Problem problem) {
                throw new IllegalStateException("Player data could not be serialized completely");
            }
        };
        TagValueOutput output = TagValueOutput.createWithContext(strict, player.registryAccess());
        player.saveWithoutId(output);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.writeCompressed(output.buildResult(), bytes);
            DiskIO.writeBytes(player.level().getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR)
                .resolve(player.getStringUUID() + ".dat"), bytes.toByteArray());
        } catch (IOException error) {
            throw new UncheckedIOException("Could not commit player data", error);
        }
    }
}
