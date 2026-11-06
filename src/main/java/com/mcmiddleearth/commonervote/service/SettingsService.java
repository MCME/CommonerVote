package com.mcmiddleearth.commonervote.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.bukkit.entity.Player;

import com.mcmiddleearth.commonervote.Permissions;
import com.mcmiddleearth.commonervote.domain.Settings;
import com.mcmiddleearth.commonervote.domain.SettingsForm;
import com.mcmiddleearth.commonervote.domain.TextsForm;
import com.mcmiddleearth.commonervote.storage.DbExecutor;
import com.mcmiddleearth.commonervote.storage.SettingsStore;
import com.mcmiddleearth.commonervote.ui.DialogPresenter;
import com.mcmiddleearth.commonervote.ui.Texts;
import com.mcmiddleearth.commonervote.ui.view.SettingsView;
import com.mcmiddleearth.commonervote.ui.view.TextsView;

/**
 * What an admin does on {@code /vote config}: a dialog for the numbers and switches, a second one for the texts and
 * links, and saving either for every server of the network. A save writes the shared settings table (and rescales the
 * stored vote weights when {@code votesNeeded} changed, see {@link SettingsStore#save}) and then replaces this server's
 * {@link SettingsCache} at once; the other servers pick the new settings up when their own cache reloads.
 * <p>
 * The public methods are called on the main thread. Database work runs on the {@link DbExecutor} and the continuation
 * comes back through {@code main}; an admin who has quit in the meantime is told nothing and shown nothing.
 * <p>
 * <b>Opening.</b> Both dialogs first reload the cache, so they show (and a save then builds on) what is stored now,
 * not what this server loaded up to a minute ago. A save writes every setting, so a stale cache would otherwise
 * undo what another server saved meanwhile. If the reload fails, the dialog opens with the cached settings: a possibly
 * stale dialog is better than none, and the {@link DbExecutor} has logged the failure. The admin is told nothing then.
 * But if the settings have never loaded on this server ({@link SettingsCache#loaded()}), the cache holds only the
 * defaults, and a save from them would overwrite every stored setting; the admin is then told
 * {@link Messages#databaseDown()} instead, and no dialog opens.
 * <p>
 * <b>Saving.</b> The form is applied to the cached settings and the result is checked with {@link Settings#problems()}
 * before anything is written; a problem is answered with {@link Messages#settingsRejected} and nothing changes. A
 * failure of the write is answered with {@link Messages#databaseDown()}; the cache keeps its settings, since they were
 * not stored.
 * <p>
 * <b>The log.</b> Every save that is stored logs who made it and, for each setting it changed, the old and new value.
 * The old value is what the table held, read in the save's own transaction: the cache can be behind it. The lines are
 * written on the database thread as soon as the save is stored, since a server that stops then drops the continuation.
 * A value goes in as one quoted line, however it was typed.
 * <p>
 * <b>Permissions.</b> A dialog can outlive the permission it was opened with, so every method checks
 * {@link Permissions#CONFIG} again, also in the callbacks of the dialogs, and answers {@link Messages#noPermission()}.
 */
public final class SettingsService {

    /**
     * What could break up a log line or change how it reads: control and format characters, line separators, and the
     * section sign of the old colour codes.
     */
    private static final Pattern NOT_FOR_THE_LOG = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}§]");

    /** The most of one value that the log shows: what the longest text box of the dialogs takes. */
    private static final int MOST_LOGGED = 1024;

    private final DbExecutor db;
    private final Continuations continuations;
    private final SettingsStore store;
    private final SettingsCache cache;
    private final DialogPresenter presenter;
    private final Logger logger;

    /**
     * @param main runs work on the server's main thread
     */
    public SettingsService(DbExecutor db, Executor main, SettingsStore store, SettingsCache cache,
                           DialogPresenter presenter, Logger logger) {
        this.db = Objects.requireNonNull(db);
        this.continuations = new Continuations(main);
        this.store = Objects.requireNonNull(store);
        this.cache = Objects.requireNonNull(cache);
        this.presenter = Objects.requireNonNull(presenter);
        this.logger = Objects.requireNonNull(logger);
    }

    /** {@code /vote config}: the dialog for the numbers and switches, filled with the stored settings. */
    public void open(Player admin) {
        if (!allowed(admin)) {
            return;
        }
        continuations.whenReady(admin, reloaded(), current ->
                presenter.showSettings(admin, new SettingsView(current, this::openTexts, this::save)));
    }

    /** The second page of {@code /vote config}: the dialog for the groups that may be promoted, links and texts. */
    public void openTexts(Player admin) {
        if (!allowed(admin)) {
            return;
        }
        continuations.whenReady(admin, reloaded(), current ->
                presenter.showTexts(admin, new TextsView(current, this::saveTexts)));
    }

    /**
     * Stores the numbers and switches of the form for every server; the texts stay as they are. Rescales the stored
     * vote weights in the same transaction when the form changes {@code votesNeeded}.
     */
    public void save(Player admin, SettingsForm form) {
        if (!allowed(admin)) {
            return;
        }
        persist(admin, cache.get().withNumbers(form));
    }

    /** Stores the texts of the form for every server; the numbers and switches stay as they are. */
    public void saveTexts(Player admin, TextsForm form) {
        if (!allowed(admin)) {
            return;
        }
        persist(admin, cache.get().withTexts(form));
    }

    /**
     * The settings stored now, which the reload also puts in the cache; if they cannot be read, the cached settings.
     * The future fails only when the reload fails and the cache has never loaded, so it holds just the defaults.
     */
    private CompletableFuture<Settings> reloaded() {
        return cache.reload().exceptionallyCompose(error -> cache.loaded()
                ? CompletableFuture.completedFuture(cache.get())
                : CompletableFuture.failedFuture(error));
    }

    /** Checks and stores the settings the admin's form resulted in, and updates this server's cache. */
    private void persist(Player admin, Settings updated) {
        List<String> problems = updated.problems();
        if (!problems.isEmpty()) {
            admin.sendMessage(Messages.settingsRejected(String.join(" ", problems)));
            return;
        }
        String who = admin.getName() + " (" + admin.getUniqueId() + ")";
        continuations.onMain(db.supply(() -> {
            logChanges(who, store.save(updated), updated.toMap());
            return updated;
        }), (saved, error) -> {
            if (error != null) {
                Continuations.tell(admin, Messages.databaseDown());
                return;
            }
            // Stored, so this server follows at once, also if the admin has quit meanwhile.
            cache.replace(saved);
            Continuations.tell(admin, Messages.settingsSaved());
        });
    }

    private void logChanges(String who, Map<String, String> before, Map<String, String> after) {
        boolean changed = false;
        for (Map.Entry<String, String> setting : after.entrySet()) {
            String old = before.get(setting.getKey());
            if (!setting.getValue().equals(old)) {
                changed = true;
                logger.info("Settings: " + who + " changed " + setting.getKey() + " from " + forLog(old) + " to "
                        + forLog(setting.getValue()));
            }
        }
        if (!changed) {
            logger.info("Settings: " + who + " saved without changing anything");
        }
    }

    /** A stored value as one quoted line, cut if it is longer than a dialog takes; {@code null} when it has no row. */
    private static String forLog(String value) {
        if (value == null) {
            return "(not set)";
        }
        String oneLine = NOT_FOR_THE_LOG.matcher(value).replaceAll(" ");
        return oneLine.length() <= MOST_LOGGED
                ? quoted(oneLine)
                : quoted(Texts.fit(oneLine, MOST_LOGGED) + "...") + " (" + oneLine.length() + " characters)";
    }

    /** In quotes, with the quotes inside escaped, so that a value shows where it ends. */
    private static String quoted(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static boolean allowed(Player admin) {
        if (admin.hasPermission(Permissions.CONFIG)) {
            return true;
        }
        admin.sendMessage(Messages.noPermission());
        return false;
    }
}
