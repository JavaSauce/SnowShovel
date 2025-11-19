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
                    "unobfuscated",
                    "https://gist.githubusercontent.com/covers1624/be877edb5e463c17ceb34c53e81fb8a3/raw/96dc13be5fe27f5a20911d0015b111ed4a4a66df/25w45a_unobfuscated.json",
                    null,
                    null,
                    "6d1ea1ebfbb189a2a40fbb5899d569c6437aff31",
                    1
            )),
            Map.entry("25w46a", new VersionListManifest.Version(
                    "25w46a_unobfuscated",
                    "unobfuscated",
                    "https://gist.githubusercontent.com/covers1624/673f8d0e357f50b98c15e695031766de/raw/17bc0aecb2efa4264b308ce4222b2206c603ddb4/25w46a_unobfuscated.json",
                    null,
                    null,
                    "075d0d8cea4a7ba8a1c3911dc06b1e3284e58c4a",
                    1
            )),
            Map.entry("1.21.11-pre1", new VersionListManifest.Version(
                    "1.21.11-pre1_unobfuscated",
                    "unobfuscated",
                    "https://gist.githubusercontent.com/covers1624/46579051fa00feb0ab76ad321d86900d/raw/ba76a28325e56fc4b0799489cfa085dfe5b37157/1.21.11-pre1_unobfuscated.json",
                    null,
                    null,
                    "37d88fdaeb5686b3b6fbea686b7b88e58cf3bd47",
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
