package com.mcmiddleearth.commonervote;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.mcmiddleearth.commonervote.domain.Settings;
import com.mcmiddleearth.commonervote.service.PromotionService;
import com.mcmiddleearth.commonervote.storage.ConnectionFactory;
import com.mcmiddleearth.commonervote.storage.PromotionRecord;
import com.mcmiddleearth.commonervote.storage.PromotionStore;
import com.mcmiddleearth.commonervote.storage.Schema;
import com.mcmiddleearth.commonervote.storage.SettingsStore;
import com.mcmiddleearth.commonervote.support.FlakyDatabase;
import com.mcmiddleearth.commonervote.support.TestCommonerVotePlugin;
import com.mcmiddleearth.commonervote.support.TestDatabase;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin as a whole, enabled on a MockBukkit server through {@link TestCommonerVotePlugin}: the startup work, the
 * config.yml notes, the listener wiring, the settings refresh, the proxy channel and the shutdown.
 */
class PluginLifecycleTest {

    private ServerMock server;
    private PluginDescriptionFile description;

    @BeforeEach
    void setUp() throws IOException, InvalidDescriptionException {
        server = MockBukkit.mock();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            description = new PluginDescriptionFile(in);
        }
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock(); // does nothing when the test has unmocked already
    }

    @Test
    void enable_creates_tables_and_seeds() throws SQLException {
        TestCommonerVotePlugin plugin = load();

        assertTrue(plugin.isEnabled());
        assertEquals(Set.of("commonervote_votes", "commonervote_settings", "commonervote_promotions"),
                tables(plugin.database()));
        assertEquals(15, rows(plugin.database(), "commonervote_settings"));
        assertEquals(List.of(), messages(plugin, Level.WARNING), "a clean start warns about nothing");
        assertEquals(List.of(), messages(plugin, Level.SEVERE));
        assertTrue(messages(plugin, Level.INFO).stream().noneMatch(m -> m.contains("/vote config")),
                "the default config.yml has no 1.2.9 keys to mention");
    }

    @Test
    void legacy_keys_logged_once() throws IOException {
        writeConfig("""
                validityPeriod: 200
                useXpBar: true
                database:
                  dbName: development
                """);

        TestCommonerVotePlugin plugin = load();

        assertEquals(dataFolder(), plugin.getDataFolder(), "the config.yml was written where the plugin reads it");
        List<String> lines = messages(plugin, Level.INFO).stream()
                .filter(m -> m.contains("validityPeriod") && m.contains("/vote config"))
                .toList();
        assertEquals(1, lines.size(), "one INFO line about the ignored keys: " + lines);
        assertTrue(lines.getFirst().contains("useXpBar"), lines.getFirst());
        assertEquals(List.of(), messages(plugin, Level.WARNING), "useDatabase is not set, so nothing to warn about");
    }

    @Test
    void use_database_false_warns() throws IOException {
        writeConfig("useDatabase: false\n");

        TestCommonerVotePlugin plugin = load();

        List<String> warnings = messages(plugin, Level.WARNING).stream()
                .filter(m -> m.contains("always in the database"))
                .toList();
        assertEquals(1, warnings.size(), "one WARNING for useDatabase: false: " + messages(plugin, Level.WARNING));
    }

    @Test
    void join_flows_through() throws SQLException {
        TestCommonerVotePlugin plugin = load();
        UUID frodo = UUID.randomUUID();
        vote(plugin.database(), UUID.randomUUID(), frodo);
        PlayerMock player = new PlayerMock(server, "Frodo99", frodo);
        player.addAttachment(plugin, Permissions.EXEMPT, true);

        server.addPlayer(player);

        Optional<PromotionRecord> record = new PromotionStore(plugin.database()).find(frodo);
        assertEquals(Optional.of(PromotionRecord.Source.DETECTED), record.map(PromotionRecord::source));
    }

    @Test
    void exp_event_routed() {
        load();
        PlayerMock guest = server.addPlayer("Bilbo_B");

        PlayerExpChangeEvent event = new PlayerExpChangeEvent(guest, 7);
        server.getPluginManager().callEvent(event);

        assertEquals(0, event.getAmount(), "a guest's XP bar is the vote meter");
    }

    @Test
    void settings_reload_every_minute_without_rebuilding_the_services() throws SQLException {
        TestCommonerVotePlugin plugin = load();
        PromotionService promotion = plugin.promotionService();
        PlayerMock guest = server.addPlayer("Bilbo_B");
        TestDatabase.exec(plugin.database(), "UPDATE commonervote_settings SET value = 'false' WHERE name = 'xpBar'");

        server.getScheduler().performTicks(1199);
        assertEquals(0, expGained(guest, 7), "not reloaded before the minute is over");

        server.getScheduler().performTicks(1);
        assertEquals(7, expGained(guest, 7), "the stored xpBar=false applies after the reload");
        assertSame(promotion, plugin.promotionService(), "a reload never rebuilds the services");
    }

    @Test
    void a_failed_setup_is_retried_every_minute_until_it_succeeds() throws SQLException {
        ConnectionFactory raw = TestDatabase.fresh();
        FlakyDatabase flaky = new FlakyDatabase(raw);
        flaky.goDown();
        load(flaky);
        assertEquals(Set.of(), tables(raw), "the database was down at startup");

        server.getScheduler().performTicks(1200);
        assertEquals(Set.of(), tables(raw), "still down a minute later");

        flaky.recover();
        server.getScheduler().performTicks(1200);
        assertEquals(Set.of("commonervote_votes", "commonervote_settings", "commonervote_promotions"), tables(raw),
                "the next minute's retry created the tables");
        assertEquals(15, rows(raw, "commonervote_settings"), "and seeded the settings");

        TestDatabase.exec(raw, "DROP TABLE commonervote_promotions");
        server.getScheduler().performTicks(1200);
        assertFalse(tables(raw).contains("commonervote_promotions"),
                "once it has succeeded, the setup is not run again");
    }

    @Test
    void a_failed_setup_does_not_hold_up_the_first_reload() throws SQLException {
        ConnectionFactory raw = TestDatabase.fresh();
        Schema.create(raw);
        new SettingsStore(raw).seedMissing(Settings.defaults().toMap());
        TestDatabase.exec(raw, "UPDATE commonervote_settings SET value = 'false' WHERE name = 'xpBar'");
        FlakyDatabase flaky = new FlakyDatabase(raw);
        flaky.failNext(1); // the setup's connection

        TestCommonerVotePlugin plugin = load(flaky);
        PlayerMock guest = server.addPlayer("Bilbo_B");

        assertTrue(messages(plugin, Level.WARNING).stream().anyMatch(m -> m.contains("Communications link failure")),
                "the setup failed: " + messages(plugin, Level.WARNING));
        assertEquals(7, expGained(guest, 7), "the reload at startup still loaded the stored xpBar=false");
    }

    @Test
    void bungeecord_channel_open_while_enabled() {
        TestCommonerVotePlugin plugin = load();
        assertTrue(server.getMessenger().isIncomingChannelRegistered(plugin, "BungeeCord"));
        assertTrue(server.getMessenger().isOutgoingChannelRegistered(plugin, "BungeeCord"));

        MockBukkit.unmock();

        assertFalse(server.getMessenger().isIncomingChannelRegistered(plugin, "BungeeCord"));
        assertFalse(server.getMessenger().isOutgoingChannelRegistered(plugin, "BungeeCord"));
    }

    @Test
    void disable_is_clean() {
        TestCommonerVotePlugin plugin = load();

        assertDoesNotThrow(MockBukkit::unmock);

        assertFalse(plugin.isEnabled());
        assertTrue(plugin.databaseThread().isShutdown(), "onDisable shut the database executor down");
    }

    @Test
    void without_luckperms_the_plugin_disables_itself() {
        TestCommonerVotePlugin plugin = MockBukkit.loadWith(TestCommonerVotePlugin.WithoutLuckPerms.class,
                description);

        assertFalse(plugin.isEnabled());
        assertTrue(messages(plugin, Level.SEVERE).stream().anyMatch(m -> m.contains("LuckPerms is required")),
                "SEVERE lines: " + messages(plugin, Level.SEVERE));
        assertNull(plugin.promotionService(), "nothing is built after the LuckPerms check fails");
        assertDoesNotThrow(MockBukkit::unmock);
    }

    // ---- helpers ----

    private TestCommonerVotePlugin load() {
        return MockBukkit.loadWith(TestCommonerVotePlugin.class, description);
    }

    /** The plugin on the test's own database, made ready (or failing) before the plugin starts. */
    private TestCommonerVotePlugin load(ConnectionFactory database) {
        return MockBukkit.loadWith(TestCommonerVotePlugin.class, description, database);
    }

    /**
     * The plugin's data folder before it is loaded. MockBukkit 4.116.1 names it {@code <name>-<version>} in its plugins
     * folder ({@code PluginManagerMock.createClassLoader}); {@link #legacy_keys_logged_once} checks that it still does.
     */
    private File dataFolder() {
        return new File(server.getPluginsFolder(), description.getName() + "-" + description.getVersion());
    }

    private void writeConfig(String yaml) throws IOException {
        File folder = dataFolder();
        Files.createDirectories(folder.toPath());
        Files.writeString(new File(folder, "config.yml").toPath(), yaml, StandardCharsets.UTF_8);
    }

    private static List<String> messages(TestCommonerVotePlugin plugin, Level level) {
        return plugin.log().records(level).stream().map(LogRecord::getMessage).toList();
    }

    /** The amount left of an XP pickup of {@code amount} once the plugin's listener has seen it. */
    private int expGained(PlayerMock player, int amount) {
        PlayerExpChangeEvent event = new PlayerExpChangeEvent(player, amount);
        server.getPluginManager().callEvent(event);
        return event.getAmount();
    }

    /** Stores a vote cast now, with the weight of a team vote under the default settings. */
    private static void vote(ConnectionFactory database, UUID voter, UUID recipient) throws SQLException {
        try (Connection c = database.open();
             PreparedStatement ps = c.prepareStatement("INSERT INTO commonervote_votes "
                     + "(voter, recipient, timestamp, weight, reason) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, voter.toString());
            ps.setString(2, recipient.toString());
            ps.setLong(3, System.currentTimeMillis());
            ps.setDouble(4, 0.2);
            ps.setString(5, "");
            ps.executeUpdate();
        }
    }

    private static Set<String> tables(ConnectionFactory database) throws SQLException {
        Set<String> names = new TreeSet<>();
        try (Connection c = database.open();
             ResultSet rs = c.getMetaData().getTables(null, null, "commonervote%", new String[] {"TABLE"})) {
            while (rs.next()) {
                names.add(rs.getString("TABLE_NAME"));
            }
        }
        return names;
    }

    private static int rows(ConnectionFactory database, String table) throws SQLException {
        try (Connection c = database.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
