package net.javasauce.ss.util;

import net.covers1624.quack.collection.FastStream;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Created by covers1624 on 11/5/25.
 */
public class UnobfuscatedVersions {

    // Mojang releases these alongside the regular version as a manual download.
    private static final Map<String, VersionListManifest.Version> UNOBFUSCATED_VERSIONS = Map.ofEntries(
            Map.entry("25w45a", new VersionListManifest.Version(
                    "25w45a_unobfuscated",
                    "snapshot",
                    "https://ss.ln-k.net/7bc38.json",
                    null,
                    null,
                    "6d1ea1ebfbb189a2a40fbb5899d569c6437aff31",
                    1
            ))
    );

    @Nullable
    @Contract ("!null->!null")
    public static VersionListManifest insert(@Nullable VersionListManifest manifest) {
        if (manifest == null) return null;

        return new VersionListManifest(
                manifest.latest(),
                FastStream.of(manifest.versions())
                        .flatMap(e -> {
                            var unobf = UNOBFUSCATED_VERSIONS.get(e.id());
                            if (unobf != null) return FastStream.of(unobf, e);

                            return FastStream.of(e);
                        })
                        .toList()
        );
    }
}
