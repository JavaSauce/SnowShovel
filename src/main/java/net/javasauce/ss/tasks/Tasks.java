package net.javasauce.ss.tasks;

import net.covers1624.quack.util.SneakyUtils;

import java.io.IOException;

/**
 * Created by covers1624 on 6/9/25.
 */
public class Tasks {

    public static void doWithRetry(int retries, SneakyUtils.ThrowingRunnable<IOException> r) {
        getWithRetry(retries, () -> {
            r.run();
            return null;
        });
    }

    public static <T> T getWithRetry(int retries, SneakyUtils.ThrowingSupplier<T, IOException> r) {
        if (retries == 0) throw new IllegalArgumentException("Need more than 0 retries.");

        Throwable throwable = null;
        for (int i = 0; i < retries; i++) {
            try {
                return r.get();
            } catch (Throwable ex) {
                if (throwable == null) {
                    throwable = ex;
                } else {
                    throwable.addSuppressed(ex);
                }
            }
        }
        if (throwable == null) {
            throwable = new RuntimeException("Run failed?");
        }
        SneakyUtils.throwUnchecked(throwable);
        return null; // Never hit.
    }
}
