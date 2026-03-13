package com.adbtunnel.tunnel;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicInteger;

public class ReconnectScheduler {
    private static final int MAX_DELAY_MS = 60_000;

    private final Runnable connectAction;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicInteger attempt = new AtomicInteger(0);
    private volatile boolean cancelled = false;

    public ReconnectScheduler(Runnable connectAction) {
        this.connectAction = connectAction;
    }

    public void schedule() {
        if (cancelled) return;
        int n = attempt.getAndIncrement();
        long delay = Math.min((long) Math.pow(2, n) * 1000, MAX_DELAY_MS);
        handler.postDelayed(() -> {
            if (!cancelled) connectAction.run();
        }, delay);
    }

    public void reset() {
        attempt.set(0);
    }

    public void cancel() {
        cancelled = true;
        handler.removeCallbacksAndMessages(null);
    }
}
