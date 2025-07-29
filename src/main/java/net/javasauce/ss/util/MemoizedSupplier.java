package net.javasauce.ss.util;

import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

public class MemoizedSupplier<T> implements Supplier<T> {

    private volatile @Nullable Supplier<T> delegate;
    @Nullable T value;

    public MemoizedSupplier(Supplier<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public T get() {
        if (delegate == null) return requireNonNull(value);
        synchronized (this) {
            // standard double latch
            if (delegate == null) return requireNonNull(value);

            T t = requireNonNull(delegate).get();
            value = t;
            // Clear delegate as our initialized check, also for GC
            delegate = null;
            return t;
        }
    }
}
