package net.javasauce.ss.util;

import net.covers1624.jdkutils.JavaInstall;
import net.covers1624.jdkutils.JavaVersion;
import net.covers1624.jdkutils.JdkInstallationManager;
import net.covers1624.jdkutils.locator.JavaLocator;
import net.covers1624.jdkutils.provisioning.adoptium.AdoptiumProvisioner;
import net.covers1624.quack.net.httpapi.HttpEngine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * Created by covers1624 on 2/9/23.
 */
public final class JdkProvider {

    private final JavaLocator locator;
    private final JdkInstallationManager installer;

    private final Supplier<List<JavaInstall>> installs;

    public JdkProvider(Path baseInstallDir, HttpEngine httpEngine) {
        locator = JavaLocator.builder()
                .useJavaw()
                .findGradleJdks()
                .findIntellijJdks()
                .ignoreOpenJ9()
                .build();
        installer = new JdkInstallationManager(baseInstallDir, new AdoptiumProvisioner(httpEngine));

        installs = new MemoizedSupplier<>(() -> {
            try {
                return locator.findJavaVersions();
            } catch (IOException ex) {
                throw new RuntimeException("Unable to find java versions.", ex);
            }
        });
    }

    public Path findOrProvisionJdk(JavaVersion version) {
        for (JavaInstall javaInstall : installs.get()) {
            if (javaInstall.hasCompiler && javaInstall.langVersion == version) {
                return javaInstall.javaHome;
            }
        }
        try {
            synchronized (installer) {
                return installer.provisionJdk(new JdkInstallationManager.ProvisionRequest.Builder()
                        .forVersion(version)
                        .build()
                );
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to provision JDK.", ex);
        }
    }
}
