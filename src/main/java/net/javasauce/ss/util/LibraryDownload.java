package net.javasauce.ss.util;

import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.maven.MavenNotation;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;

/**
 * Created by covers1624 on 1/20/25.
 */
public record LibraryDownload(MavenNotation notation, String url, @Nullable String sha1, long size, Path path) {

    // Minecraft compiles against these annotations, but does not ship them for runtime.
    // Some versions don't need them applied, but it's fine to add anyway.
    public static final List<VersionManifest.Library> ANNOTATIONS = List.of(
            new VersionManifest.Library(MavenNotation.parse("org.jetbrains:annotations:26.0.1"), null, null, null, null, null),
            new VersionManifest.Library(MavenNotation.parse("com.google.code.findbugs:jsr305:3.0.2"), null, null, null, null, null)
    );

    public static List<LibraryDownload> getVersionLibraries(VersionManifest manifest, Path librariesDir) {
        var seenDependencies = new HashSet<String>();
        // Run the rule filtering, excludes duplicate dependencies of different versions for different platforms.
        var libraries = FastStream.of(manifest.libraries())
                .filter(e -> VersionManifest.Rule.apply(e.rules(), Set.of()))
                .toList();
        libraries.forEach(e -> seenDependencies.add(e.name().group + ":" + e.name().module));

        // Second pass, find any dependencies which are platform specific and forcibly add those back in.
        // These dependencies may be required for platform-specific code to compile.
        for (VersionManifest.Library library : manifest.libraries()) {
            if (seenDependencies.add(library.name().group + ":" + library.name().module)) {
                libraries.add(library);
            }
        }
        return FastStream.of(libraries)
                .concat(ANNOTATIONS)
                .map(e -> computeDownload(e, librariesDir))
                .filter(Objects::nonNull)
                .toList();
    }

    private static @Nullable LibraryDownload computeDownload(VersionManifest.Library library, Path librariesDir) {
        // No natives required here.
        if (!library.natives().isEmpty()) return null;

        Path libPath = library.name().toPath(librariesDir);
        if (library.url() != null || library.downloads() == null) {
            String url = library.url();
            if (url == null) {
                url = library.name().toURL("https://proxy-maven.covers1624.net/").toString();
            }
            return new LibraryDownload(library.name(), url, null, -1, libPath);
        }

        var downloads = library.downloads();
        var artifact = downloads.artifact();
        if (artifact == null) return null;
        if (StringUtils.isEmpty(artifact.url())) return null;

        return new LibraryDownload(library.name(), artifact.url(), artifact.sha1(), artifact.size(), libPath);
    }
}
