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
            )),
            Map.entry("1.21.11-pre2", new VersionListManifest.Version(
                    "1.21.11-pre2_unobfuscated",
                    "unobfuscated",
                    "https://gist.githubusercontent.com/covers1624/087014068561eb8dde30ee3e881c06a4/raw/daf74e5a1a6c8b521c9db53518551f3012f7a9f1/1.21.11-pre2_unobfuscated.json",
                    null,
                    null,
                    "dfcad9b5f743c13c0e8599b4aed4260e6bd2e941",
                    1
            )),
            Map.entry("1.21.11-pre3", new VersionListManifest.Version(
                    "1.21.11-pre3_unobfuscated",
                    "unobfuscated",
                    "https://gist.githubusercontent.com/covers1624/db4e3ea37830b007b4f1a6e9858c52a5/raw/134199189de6f6e1e42f975d1c2b27ad896f9248/1.21.11-pre3_unobfuscated.json",
                    null,
                    null,
                    "25b3833993eca3c8270f701839b9fe771cc3bd6d",
                    1
            )),
            Map.entry("1.21.11-pre4", new VersionListManifest.Version(
                    "1.21.11-pre4_unobfuscated",
                    "unobfuscated",
                    "https://gist.githubusercontent.com/covers1624/bd7a5bd40e103c8ffb798e00fb90de3e/raw/fc7ef876ed979c8cfad90050d3faceb53ac8f85d/1.21.11-pre4_unobfuscated.json",
                    null,
                    null,
                    "046a64387924162ceae6e96d5b16dd1da9cecbf5",
                    1
            )),
            Map.entry("1.21.11-pre5", new VersionListManifest.Version(
                    "1.21.11-pre5_unobfuscated",
                    "unobfuscated",
                    "https://gist.githubusercontent.com/covers1624/e2430f3aee50ef13ca960bb08441b59e/raw/05153cf92355e09339abfe88f89d1e27e2b7f447/1.21.11-pre5_unobfuscated.json",
                    null,
                    null,
                    "3b03edbfa4fe3f8b07706236a3dbfcbe8dc20ec0",
                    1
            )),
            Map.entry("1.21.11-rc1", new VersionListManifest.Version(
                    "1.21.11-rc1_unobfuscated",
                    "unobfuscated",
                    "https://gist.githubusercontent.com/covers1624/200d29e6913036bcb1d2bfb309c19af4/raw/cdfb261e224d972850da809465fc2afe96a3ff61/1.21.11-rc1_unobfuscated.json",
                    null,
                    null,
                    "5296d11a6c544aaf8b9ae8dbdfdf6f3b58cefe29",
                    1
            )),
            Map.entry("1.21.11-rc2", new VersionListManifest.Version(
                    "1.21.11-rc2_unobfuscated",
                    "unobfuscated",
                    "https://gist.githubusercontent.com/covers1624/908e852d367c8380244267b7d421145e/raw/9d18b0ff373554fcf64cbf229413665a5c801258/1.21.11-rc2_unobfuscated.json",
                    null,
                    null,
                    "9324c27bf3f03da4f9bd8a48aed678018a03b3ca",
                    1
            )),
            Map.entry("1.21.11-rc3", new VersionListManifest.Version(
                    "1.21.11-rc3_unobfuscated",
                    "unobfuscated",
                    "https://gist.githubusercontent.com/covers1624/91091c03e2de098655d432ed4a3e950d/raw/707ddd89f65a497c75a165379383135a1f3bc04f/1.21.11-rc3_unobfuscated.json",
                    null,
                    null,
                    "c5517fa2e57de78216c6662f121e8e2094b689b6",
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
