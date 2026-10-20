package com.mcmiddleearth.commonervote.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.mcmiddleearth.commonervote.Permissions;
import com.mcmiddleearth.commonervote.domain.Settings;
import com.mcmiddleearth.commonervote.service.ProfileCache;
import com.mcmiddleearth.commonervote.service.PromotionService;
import com.mcmiddleearth.commonervote.service.ReviewService;
import com.mcmiddleearth.commonervote.service.SettingsCache;
import com.mcmiddleearth.commonervote.service.SettingsService;
import com.mcmiddleearth.commonervote.service.VoteService;
import com.mcmiddleearth.commonervote.service.XpBarService;
import com.mcmiddleearth.commonervote.storage.ConnectionFactory;
import com.mcmiddleearth.commonervote.storage.DbExecutor;
import com.mcmiddleearth.commonervote.storage.PromotionRecord;
import com.mcmiddleearth.commonervote.storage.PromotionStore;
import com.mcmiddleearth.commonervote.storage.Schema;
import com.mcmiddleearth.commonervote.storage.SettingsStore;
import com.mcmiddleearth.commonervote.storage.VoteStore;

/**
 * What every service test starts from: a MockBukkit server, a fresh database with the schema and the default settings,
 * the three stores, the fakes, and the services built on them.
 * <p>
 * Out of the box everything is synchronous: the database work and the main-thread continuations run at once, on the
 * test thread. A test that needs another {@link #main} executor (a {@link QueueExecutor}, to quit between the async
 * work and its continuation) or other {@link #connections} (see {@link #breakDatabase()}) sets the field and calls
 * {@link #build()} again. That rebuilds everything that depends on them and keeps the server, the players, the
 * database contents and the fakes.
 * <p>
 * A test class for a further service overrides {@link #build()}, calls {@code super.build()} first and then builds its
 * own service from these fields, so a rebuild covers it too.
 */
public abstract class ServiceTestBase {

    /** The time the services see, unless a test moves {@link #now}. */
    protected static final long NOW = 1_800_000_000_000L;

    private static final long DAY_MILLIS = 86_400_000L;

    protected ServerMock server;
    /** A plugin to own the permission attachments of the test players. */
    protected Plugin plugin;

    /** The test database itself, for raw inserts and reads. It stays reachable when the services' connections fail. */
    protected ConnectionFactory database;
    /** What the stores open connections with: the test database, unless a test swaps it and calls {@link #build()}. */
    protected ConnectionFactory connections;
    /** The main thread of the services: runs work at once, unless a test swaps it and calls {@link #build()}. */
    protected Executor main;
    /** The current time of the services, in epoch milliseconds. */
    protected long now = NOW;

    /** Everything the services and the database executor log. */
    protected final CapturingLogger logger = CapturingLogger.create();
    protected final FakeRankService ranks = new FakeRankService();
    protected final RecordingPresenter presenter = new RecordingPresenter();
    protected final RecordingMessenger messenger = new RecordingMessenger();
    protected final RecordingCommandRunner commands = new RecordingCommandRunner();
    /**
     * Paper's profile cache as the services see it, by player: the name of everyone who joined this server (see
     * {@link #guest}). A test may add names, for a player who was here once.
     */
    protected final Map<UUID, String> cachedNames = new ConcurrentHashMap<>();
    /** The services' profile cache, which reads {@link #cachedNames}. */
    protected final ProfileCache profiles = player -> Optional.ofNullable(cachedNames.get(player));

    protected DbExecutor db;
    protected VoteStore votes;
    protected PromotionStore promotions;
    protected SettingsStore settingsStore;
    protected SettingsCache settings;
    protected XpBarService xp;
    protected PromotionService promotion;
    protected VoteService voting;
    protected ReviewService reviewing;
    protected SettingsService configuring;

    @BeforeEach
    protected void setUpServiceTestBase() throws SQLException {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        database = TestDatabase.fresh();
        Schema.create(database);
        new SettingsStore(database).seedMissing(Settings.defaults().toMap());
        connections = database;
        main = Runnable::run;
        build();
    }

    @AfterEach
    protected void tearDownServiceTestBase() {
        MockBukkit.unmock();
    }

    /**
     * Builds the database executor (on {@code Runnable::run}), the stores on {@link #connections}, the settings cache,
     * the XP bar service and the services (promotion, voting, reviewing, configuring) on {@link #main}.
     * <p>
     * The settings cache holds the settings stored in the test database, as after a reload, also when a test has
     * broken the {@link #connections} (as a server keeps its last settings while the database is down).
     */
    protected void build() throws SQLException {
        db = new DbExecutor(Runnable::run, logger, () -> now);
        votes = new VoteStore(connections);
        promotions = new PromotionStore(connections);
        settingsStore = new SettingsStore(connections);
        settings = new SettingsCache(db, settingsStore, logger);
        settings.replace(storedSettings());
        xp = new XpBarService(settings::get);
        promotion = new PromotionService(db, main, votes, promotions, settings, ranks, messenger, presenter, xp,
                commands, logger, () -> now);
        voting = new VoteService(db, main, votes, settings, ranks, profiles, messenger, presenter, xp, promotion,
                () -> now);
        reviewing = new ReviewService(db, main, votes, settings, ranks, profiles, messenger, presenter, xp, promotion,
                () -> now);
        configuring = new SettingsService(db, main, settingsStore, settings, presenter);
    }

    /** Makes every connection the services open fail, as when the database is down, and rebuilds the services. */
    protected void breakDatabase() throws SQLException {
        connections = () -> {
            throw new SQLException("Communications link failure");
        };
        build();
    }

    /**
     * Stores one setting in the test database and puts it in the settings cache. Fails the test for a name that has
     * no row or a value that {@link Settings#fromMap} cannot use.
     */
    protected void setting(String name, String value) throws SQLException {
        int updated;
        try (Connection c = database.open();
             PreparedStatement ps = c.prepareStatement("UPDATE commonervote_settings SET value = ? WHERE name = ?")) {
            ps.setString(1, value);
            ps.setString(2, name);
            updated = ps.executeUpdate();
        }
        if (updated != 1) {
            throw new IllegalArgumentException("No setting called " + name);
        }
        settings.replace(storedSettings());
    }

    // ---- players ----

    /**
     * A player online on this server with no permissions, known to the rank service under this name. Like Paper's,
     * the profile cache keeps the name, also once the player has left.
     */
    protected PlayerMock guest(String name) {
        PlayerMock player = server.addPlayer(name);
        ranks.add(player.getUniqueId(), name);
        cachedNames.put(player.getUniqueId(), name);
        return player;
    }

    /** A guest who may vote. */
    protected PlayerMock teamMember(String name) {
        PlayerMock player = guest(name);
        player.addAttachment(plugin, Permissions.VOTE, true);
        return player;
    }

    /** A guest who may vote with the staff weight. */
    protected PlayerMock staff(String name) {
        PlayerMock player = teamMember(name);
        player.addAttachment(plugin, Permissions.STAFF, true);
        return player;
    }

    /** A player past the vote: exempt both online and, through the rank service, offline. */
    protected PlayerMock exempt(String name) {
        PlayerMock player = guest(name);
        player.addAttachment(plugin, Permissions.EXEMPT, true);
        ranks.setExempt(player.getUniqueId(), true);
        return player;
    }

    /**
     * A player who is not on this server and whom it has never seen, known to the rank service only: neither the
     * players online nor the profile cache know them. MockBukkit's {@code getOfflinePlayer(id).getName()} would make up
     * a random name for them, so a service that read it (it must not: Paper reads the player's data file for it) would
     * show that name and fail the test.
     */
    protected UUID remoteGuest(String name) {
        UUID id = UUID.randomUUID();
        ranks.add(id, name);
        return id;
    }

    // ---- database contents ----

    /** Inserts a vote row as it is stored, with no reason, cast {@code daysAgo} days before {@link #now}. */
    protected void vote(UUID voter, UUID recipient, double weight, int daysAgo) throws SQLException {
        vote(voter, recipient, weight, daysAgo, "");
    }

    /** Inserts a vote row as it is stored, cast {@code daysAgo} days before {@link #now}. */
    protected void vote(UUID voter, UUID recipient, double weight, int daysAgo, String reason) throws SQLException {
        try (Connection c = database.open();
             PreparedStatement ps = c.prepareStatement("INSERT INTO commonervote_votes "
                     + "(voter, recipient, timestamp, weight, reason) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, voter.toString());
            ps.setString(2, recipient.toString());
            ps.setLong(3, now - daysAgo * DAY_MILLIS);
            ps.setDouble(4, weight);
            ps.setString(5, reason);
            ps.executeUpdate();
        }
    }

    /** Stores a promotion record for {@code player}, promoted at {@link #now}. */
    protected void recordPromotion(UUID player, PromotionRecord.Source source, boolean welcomed) throws SQLException {
        new PromotionStore(database).save(new PromotionRecord(player, now, source, welcomed));
    }

    /** The promotion record of {@code player} in the test database. */
    protected Optional<PromotionRecord> promotionOf(UUID player) throws SQLException {
        return new PromotionStore(database).find(player);
    }

    /** The console command that the default settings run to promote {@code player}: it names them by UUID. */
    protected static String promotionCommand(UUID player) {
        return "lp user " + player + " parent set commoner";
    }

    private Settings storedSettings() throws SQLException {
        return Settings.fromMap(new SettingsStore(database).load(), warning -> {
            throw new IllegalArgumentException(warning);
        });
    }
}
