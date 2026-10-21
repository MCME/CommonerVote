package com.mcmiddleearth.commonervote.service;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mcmiddleearth.commonervote.domain.Settings;
import com.mcmiddleearth.commonervote.domain.SettingsForm;
import com.mcmiddleearth.commonervote.storage.ConnectionFactory;
import com.mcmiddleearth.commonervote.storage.DatabaseUnavailableException;
import com.mcmiddleearth.commonervote.storage.DbExecutor;
import com.mcmiddleearth.commonervote.storage.Schema;
import com.mcmiddleearth.commonervote.storage.SettingsStore;
import com.mcmiddleearth.commonervote.support.CapturingLogger;
import com.mcmiddleearth.commonervote.support.QueueExecutor;
import com.mcmiddleearth.commonervote.support.TestDatabase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsCacheTest {

    private ConnectionFactory database;
    private final AtomicBoolean databaseDown = new AtomicBoolean();
    private final CapturingLogger cacheLog = CapturingLogger.create();
    private final CapturingLogger dbLog = CapturingLogger.create();
    private SettingsStore store;
    private SettingsCache cache;

    @BeforeEach
    void setUp() throws SQLException {
        database = TestDatabase.fresh();
        Schema.create(database);
        ConnectionFactory flaky = () -> {
            if (databaseDown.get()) {
                throw new SQLException("Communications link failure");
            }
            return database.open();
        };
        store = new SettingsStore(flaky);
        cache = new SettingsCache(new DbExecutor(Runnable::run, dbLog, System::currentTimeMillis), store, cacheLog);
    }

    @Test
    void cache_defaults_then_load() throws Exception {
        TestDatabase.exec(database, "INSERT INTO commonervote_settings (name, value) VALUES ('validityDays', '50')");

        assertEquals(Settings.defaults(), cache.get(), "before the first load the defaults apply");

        Settings loaded = cache.reload().get();

        assertEquals(50, loaded.validityDays());
        assertEquals(50, cache.get().validityDays());
        assertEquals(Settings.defaults().votesNeeded(), cache.get().votesNeeded(), "unstored settings stay default");
        assertEquals(0, cacheLog.records().size());
    }

    @Test
    void reload_loads_on_the_io_executor() throws Exception {
        TestDatabase.exec(database, "INSERT INTO commonervote_settings (name, value) VALUES ('validityDays', '50')");
        QueueExecutor io = new QueueExecutor();
        SettingsCache queued = new SettingsCache(new DbExecutor(io, dbLog, System::currentTimeMillis), store, cacheLog);

        CompletableFuture<Settings> result = queued.reload();

        assertFalse(result.isDone());
        assertEquals(Settings.defaults(), queued.get(), "the old value stays until the load has run");

        io.runAll();

        assertEquals(50, result.get().validityDays());
        assertEquals(50, queued.get().validityDays());
    }

    @Test
    void a_failed_reload_keeps_the_cached_settings() throws Exception {
        TestDatabase.exec(database, "INSERT INTO commonervote_settings (name, value) VALUES ('validityDays', '50')");
        Settings before = cache.reload().get();

        databaseDown.set(true);
        CompletableFuture<Settings> failed = cache.reload();

        ExecutionException failure = assertThrows(ExecutionException.class, failed::get);
        assertInstanceOf(DatabaseUnavailableException.class, failure.getCause());
        assertSame(before, cache.get());

        databaseDown.set(false);
        TestDatabase.exec(database, "UPDATE commonervote_settings SET value = '70' WHERE name = 'validityDays'");
        assertEquals(70, cache.reload().get().validityDays(), "the next reload works again");
    }

    @Test
    void a_failed_reload_is_logged_by_the_executor_not_by_the_cache() {
        databaseDown.set(true);

        cache.reload();

        assertEquals(1, dbLog.records(Level.WARNING).size());
        assertEquals(0, cacheLog.records().size());
    }

    @Test
    void loaded_after_the_first_successful_reload() throws Exception {
        assertFalse(cache.loaded(), "the defaults are not loaded settings");

        databaseDown.set(true);
        assertThrows(ExecutionException.class, () -> cache.reload().get());
        assertFalse(cache.loaded(), "a failed reload loads nothing");

        databaseDown.set(false);
        cache.reload().get();
        assertTrue(cache.loaded());

        databaseDown.set(true);
        assertThrows(ExecutionException.class, () -> cache.reload().get());
        assertTrue(cache.loaded(), "once loaded, the cache keeps serving what it loaded");
    }

    @Test
    void loaded_after_a_replace() {
        cache.replace(Settings.defaults());

        assertTrue(cache.loaded(), "this server saved them, so they are the stored settings");
    }

    @Test
    void replace_sets_what_get_returns() {
        Settings edited = Settings.defaults().withNumbers(new SettingsForm(
                7, 30, 3, 2, false, false, false));

        cache.replace(edited);

        assertSame(edited, cache.get());
    }

    // ---- logging of what is wrong in the stored settings ----

    @Test
    void cache_warns_once() throws Exception {
        TestDatabase.exec(database, "INSERT INTO commonervote_settings (name, value) VALUES ('votesNeeded', 'abc')");

        cache.reload().get();
        cache.reload().get();
        cache.reload().get();

        List<LogRecord> warnings = cacheLog.records();
        assertEquals(1, warnings.size(), "one warning across three reloads");
        assertEquals(Level.WARNING, warnings.get(0).getLevel());
        assertTrue(warnings.get(0).getMessage().contains("votesNeeded"), warnings.get(0).getMessage());
        assertEquals(Settings.defaults().votesNeeded(), cache.get().votesNeeded(), "the default is used meanwhile");
    }

    @Test
    void each_distinct_warning_is_logged_once() throws Exception {
        TestDatabase.exec(database, "INSERT INTO commonervote_settings (name, value) VALUES ('votesNeeded', 'abc')");
        TestDatabase.exec(database, "INSERT INTO commonervote_settings (name, value) VALUES ('staffWeight', '9')");

        cache.reload().get();
        cache.reload().get();
        assertEquals(2, cacheLog.records().size());

        TestDatabase.exec(database, "UPDATE commonervote_settings SET value = 'xyz' WHERE name = 'votesNeeded'");
        cache.reload().get();
        cache.reload().get();

        assertEquals(3, cacheLog.records().size(), "a different bad value is a different warning");
    }

    @Test
    void problems_of_the_loaded_settings_are_warned_once_and_the_settings_are_still_used() throws Exception {
        TestDatabase.exec(database,
                "INSERT INTO commonervote_settings (name, value) VALUES ('promotionCommand', 'lp user bilbo')");
        TestDatabase.exec(database, "INSERT INTO commonervote_settings (name, value) VALUES ('commonerGroup', '')");

        Settings first = cache.reload().get();
        cache.reload().get();
        cache.reload().get();

        List<LogRecord> warnings = cacheLog.records();
        assertEquals(2, warnings.size(), "one warning per problem, however often the settings are reloaded");
        for (LogRecord warning : warnings) {
            assertEquals(Level.WARNING, warning.getLevel());
        }
        assertTrue(warnings.stream().anyMatch(w -> w.getMessage().contains("promotionCommand must contain <player>")),
                warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.getMessage().contains("commonerGroup must not be empty")),
                warnings.toString());
        assertEquals("lp user bilbo", first.promotionCommand(), "what was stored is still what the cache serves");
        assertEquals("", cache.get().commonerGroup());
    }

    @Test
    void parse_warnings_and_problems_do_not_hide_each_other() throws Exception {
        TestDatabase.exec(database, "INSERT INTO commonervote_settings (name, value) VALUES ('votesNeeded', 'abc')");
        TestDatabase.exec(database,
                "INSERT INTO commonervote_settings (name, value) VALUES ('promotionCommand', 'lp user bilbo')");

        cache.reload().get();
        cache.reload().get();

        assertEquals(2, cacheLog.records().size());
    }
}
