package com.mcmiddleearth.commonervote.service;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import com.mcmiddleearth.commonervote.domain.Settings;
import com.mcmiddleearth.commonervote.storage.DbExecutor;
import com.mcmiddleearth.commonervote.storage.SettingsStore;

/**
 * This server's copy of the shared settings. Every backend of the network keeps one, so a flow never reads the
 * settings table on the main thread; a timer calls {@link #reload()} to pick up what another server saved.
 * <p>
 * {@link #get()} can be called from any thread and never blocks. It gives {@link Settings#defaults()} until the
 * first load has finished, and the last settings that loaded (or were handed to {@link #replace}) after that, also
 * while the database is down. The defaults may not be what is stored, so {@link #loaded()} tells the flows that must
 * not act on them (a promotion, the settings dialogs) whether real settings have arrived yet.
 * <p>
 * What is wrong with the stored settings is logged as a WARNING, once per server run for each distinct message, so a
 * timer reloading every minute does not repeat it. The settings are used all the same: an unusable value has already
 * fallen back to its default in {@link Settings#fromMap}, and a text-field problem that {@link Settings#problems()}
 * finds is left for the owner to fix in the settings dialog.
 */
public final class SettingsCache {

    private final DbExecutor db;
    private final SettingsStore store;
    private final Logger logger;

    private volatile Settings current = Settings.defaults();
    private volatile boolean loadedOnce;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public SettingsCache(DbExecutor db, SettingsStore store, Logger logger) {
        this.db = Objects.requireNonNull(db);
        this.store = Objects.requireNonNull(store);
        this.logger = Objects.requireNonNull(logger);
    }

    public Settings get() {
        return current;
    }

    /**
     * Whether {@link #get()} gives settings from the database: true once a {@link #reload()} has succeeded or settings
     * were handed to {@link #replace}, and from then on, also while the database is down again. False while
     * {@link #get()} still gives the defaults.
     */
    public boolean loaded() {
        return loadedOnce;
    }

    /**
     * Loads the settings table on the database thread and caches what it holds. The future completes with the
     * settings once {@link #get()} returns them. If the load fails, the cached settings stay as they are and the
     * future completes exceptionally, as {@link DbExecutor#supply} describes (the failure is logged there).
     */
    public CompletableFuture<Settings> reload() {
        // The cache is updated inside the task, on the database thread, so a reload and a save that follows it
        // there cannot finish in the wrong order.
        return db.supply(() -> {
            Map<String, String> stored = store.load();
            Settings loaded = Settings.fromMap(stored, this::warnOnce);
            for (String problem : loaded.problems()) {
                warnOnce("Stored setting problem: " + problem);
            }
            current = loaded;
            loadedOnce = true;
            return loaded;
        });
    }

    /** Makes {@code s} the cached settings, for after this server saved them itself. */
    public void replace(Settings s) {
        current = Objects.requireNonNull(s);
        loadedOnce = true;
    }

    private void warnOnce(String message) {
        if (warned.add(message)) {
            logger.warning(message);
        }
    }
}
