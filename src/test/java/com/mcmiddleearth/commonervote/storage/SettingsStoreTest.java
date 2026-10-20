package com.mcmiddleearth.commonervote.storage;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mcmiddleearth.commonervote.domain.Settings;
import com.mcmiddleearth.commonervote.domain.SettingsForm;
import com.mcmiddleearth.commonervote.support.TestDatabase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SettingsStoreTest {

    private static final long NOW = 1_800_000_000_000L;

    private ConnectionFactory db;
    private SettingsStore store;

    @BeforeEach
    void setUp() throws SQLException {
        db = TestDatabase.fresh();
        Schema.create(db);
        store = new SettingsStore(db);
    }

    // ---- DatabaseConfig ----

    @Test
    void config_from_legacy_keys() {
        MemoryConfiguration section = new MemoryConfiguration();
        section.set("ip", "db");
        section.set("port", 3307);
        section.set("dbName", "x");
        section.set("user", "u");
        section.set("password", "p");

        DatabaseConfig config = DatabaseConfig.from(section);

        assertEquals(new DatabaseConfig("db", 3307, "x", "u", "p"), config);
        assertEquals("jdbc:mysql://db:3307/x?useSSL=false&connectTimeout=5000&socketTimeout=10000",
                config.jdbcUrl());
    }

    @Test
    void a_null_section_gives_the_1_2_9_defaults() {
        DatabaseConfig config = DatabaseConfig.from(null);

        assertEquals(new DatabaseConfig("localhost", 3306, "development", "development", "development"), config);
        assertEquals("jdbc:mysql://localhost:3306/development?useSSL=false&connectTimeout=5000&socketTimeout=10000",
                config.jdbcUrl());
    }

    @Test
    void missing_keys_fall_back_one_by_one() {
        MemoryConfiguration section = new MemoryConfiguration();
        section.set("ip", "db");
        section.set("user", "u");

        assertEquals(new DatabaseConfig("db", 3306, "development", "u", "development"),
                DatabaseConfig.from(section));
    }

    @Test
    void the_password_is_not_printed() {
        String text = new DatabaseConfig("db", 3306, "x", "u", "s3cret-pw").toString();

        assertFalse(text.contains("s3cret-pw"), text);
        assertTrue(text.contains("db"), text);
    }

    // ---- Schema ----

    @Test
    void schema_is_idempotent_and_1_2_9_compatible() throws SQLException {
        ConnectionFactory fresh = TestDatabase.fresh();
        Schema.create(fresh);
        Schema.create(fresh);

        // 1.2.9's own insert, with no column list, still works on the table 2.0 created.
        try (Connection c = fresh.open();
             PreparedStatement ps = c.prepareStatement("INSERT INTO commonervote_votes VALUES (?,?,?,?,?)")) {
            ps.setString(1, "voter-uuid");
            ps.setString(2, "recipient-uuid");
            ps.setLong(3, NOW);
            ps.setDouble(4, 0.4);
            ps.setString(5, "");
            assertEquals(1, ps.executeUpdate());
        }
        assertEquals(1, count(fresh, "commonervote_votes"));
        assertEquals(0, count(fresh, "commonervote_settings"));
        assertEquals(0, count(fresh, "commonervote_promotions"));
    }

    @Test
    void the_votes_table_keeps_exactly_the_1_2_9_columns() throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM commonervote_votes");
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            List<String> expected = List.of("voter", "recipient", "timestamp", "weight", "reason");
            assertEquals(expected.size(), meta.getColumnCount());
            for (int i = 0; i < expected.size(); i++) {
                assertEquals(expected.get(i), meta.getColumnLabel(i + 1).toLowerCase(Locale.ROOT));
            }
        }
    }

    // ---- load and seedMissing ----

    @Test
    void load_of_an_empty_table_is_empty() throws SQLException {
        assertTrue(store.load().isEmpty());
    }

    @Test
    void seed_only_missing() throws SQLException {
        TestDatabase.exec(db, "INSERT INTO commonervote_settings (name, value) VALUES ('votesNeeded', '7')");

        store.seedMissing(Settings.defaults().toMap());

        Map<String, String> loaded = store.load();
        assertEquals("7", loaded.get("votesNeeded"));
        assertEquals(Settings.defaults().toMap().keySet(), loaded.keySet());
        assertEquals(15, loaded.size());
        assertEquals("100", loaded.get("validityDays"));
    }

    @Test
    void seeding_twice_changes_nothing() throws SQLException {
        store.seedMissing(Settings.defaults().toMap());
        store.save(Settings.defaults().withNumbers(form(5, 50)));
        store.seedMissing(Settings.defaults().toMap());

        assertEquals("50", store.load().get("validityDays"));
        assertEquals(15, store.load().size());
    }

    @Test
    void seeding_survives_another_backend_seeding_at_the_same_moment() throws SQLException {
        // Several backends enable at once on the first start after an upgrade. Just as this seeder is about to
        // insert, another one commits its own row for the same setting.
        AtomicBoolean raced = new AtomicBoolean();
        ConnectionFactory racing = () -> {
            Connection real = db.open();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                                && sql.startsWith("INSERT") && raced.compareAndSet(false, true)) {
                            TestDatabase.exec(db, "INSERT INTO commonervote_settings (name, value) "
                                    + "VALUES ('votesNeeded', '9')");
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        };

        new SettingsStore(racing).seedMissing(Settings.defaults().toMap());

        assertTrue(raced.get(), "the competing insert should have happened");
        Map<String, String> loaded = store.load();
        assertEquals(15, loaded.size());
        assertEquals("9", loaded.get("votesNeeded"));
    }

    // ---- save ----

    @Test
    void save_writes_all() throws SQLException {
        store.save(Settings.defaults().withNumbers(form(5, 50)));

        Map<String, String> loaded = store.load();
        assertEquals("50", loaded.get("validityDays"));
        assertEquals(15, loaded.size());
        // Saved over seeded rows too: update, not a second insert.
        store.save(Settings.defaults().withNumbers(form(5, 60)));
        assertEquals("60", store.load().get("validityDays"));
        assertEquals(15, store.load().size());
    }

    @Test
    void every_setting_survives_a_round_trip_including_odd_text() throws SQLException {
        Settings custom = new Settings(7, 50, 3, 2, false, false, false, "citizen", Set.of("badge_a", "badge_b"),
                "promote <player> now", "https://example.org/rules", "https://example.org/guide",
                "guest \"quoted\" it's <yellow>x</yellow>", "line one<newline>line two\nreal newline",
                "<gold>★ <player> has joined!");

        store.save(custom);

        assertEquals(custom, Settings.fromMap(store.load(), message -> fail(message)));
    }

    @Test
    void save_rescales_on_votes_needed_change() throws SQLException {
        store.seedMissing(Settings.defaults().toMap());
        insertVote("a", 0.2);
        insertVote("b", 0.4);

        store.save(Settings.defaults().withNumbers(form(10, 100)));
        assertWeights(0.1, 0.2);
        assertEquals("10", store.load().get("votesNeeded"));

        // The same value again is not a change.
        store.save(Settings.defaults().withNumbers(form(10, 100)));
        assertWeights(0.1, 0.2);

        // And back: the round trip restores the weights.
        store.save(Settings.defaults());
        assertWeights(0.2, 0.4);
        assertEquals("5", store.load().get("votesNeeded"));
    }

    @Test
    void a_missing_votes_needed_row_counts_as_the_default() throws SQLException {
        insertVote("a", 0.2);

        store.save(Settings.defaults().withNumbers(form(10, 100)));

        assertWeight("a", 0.1);
        assertEquals(15, store.load().size());
    }

    @Test
    void an_unusable_stored_votes_needed_counts_as_the_default() throws SQLException {
        // Settings.fromMap reads this value as 5, so the votes were written for 5.
        TestDatabase.exec(db, "INSERT INTO commonervote_settings (name, value) VALUES ('votesNeeded', 'many')");
        insertVote("a", 0.2);

        store.save(Settings.defaults().withNumbers(form(10, 100)));

        assertWeight("a", 0.1);
        assertEquals("10", store.load().get("votesNeeded"));
    }

    @Test
    void save_changes_nothing_when_it_fails_halfway() throws SQLException {
        store.seedMissing(Settings.defaults().toMap());
        insertVote("a", 0.2);
        insertVote("b", 0.4);
        Map<String, String> before = store.load();

        // announceText is the last row written; a null there breaks the NOT NULL column after the weights were
        // rescaled and fourteen settings were already updated.
        Settings d = Settings.defaults();
        Settings broken = new Settings(10, 50, d.staffWeight(), d.teamWeight(), d.autoPromote(), d.xpBar(),
                d.broadcastNetwork(), d.commonerGroup(), d.promotableGroups(), d.promotionCommand(), d.rulesUrl(),
                d.guideUrl(), d.guestInfoText(), d.welcomeText(), null);

        assertThrows(SQLException.class, () -> store.save(broken));

        assertEquals(before, store.load());
        assertWeights(0.2, 0.4);
        // Nothing is left locked or half-open: the next save goes through.
        store.save(Settings.defaults().withNumbers(form(10, 100)));
        assertWeights(0.1, 0.2);
    }

    // ---- helpers ----

    private static SettingsForm form(int votesNeeded, int validityDays) {
        Settings d = Settings.defaults();
        return new SettingsForm(votesNeeded, validityDays, d.staffWeight(), d.teamWeight(), d.autoPromote(),
                d.xpBar(), d.broadcastNetwork());
    }

    private void insertVote(String voter, double weight) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("INSERT INTO commonervote_votes VALUES (?,?,?,?,?)")) {
            ps.setString(1, voter);
            ps.setString(2, "recipient");
            ps.setLong(3, NOW);
            ps.setDouble(4, weight);
            ps.setString(5, "");
            ps.executeUpdate();
        }
    }

    private void assertWeights(double a, double b) throws SQLException {
        assertWeight("a", a);
        assertWeight("b", b);
    }

    private void assertWeight(String voter, double expected) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT weight FROM commonervote_votes WHERE voter = ?")) {
            ps.setString(1, voter);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no vote from " + voter);
                assertEquals(expected, rs.getDouble(1), 1e-9, "weight of the vote from " + voter);
            }
        }
    }

    private static int count(ConnectionFactory f, String table) throws SQLException {
        try (Connection c = f.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
