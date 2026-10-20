package com.mcmiddleearth.commonervote;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import com.mcmiddleearth.commonervote.command.VoteCommand;
import com.mcmiddleearth.commonervote.domain.Settings;
import com.mcmiddleearth.commonervote.listener.PlayerListener;
import com.mcmiddleearth.commonervote.network.BungeeMessenger;
import com.mcmiddleearth.commonervote.rank.LuckPermsRankService;
import com.mcmiddleearth.commonervote.rank.RankService;
import com.mcmiddleearth.commonervote.service.CommandRunner;
import com.mcmiddleearth.commonervote.service.ProfileCache;
import com.mcmiddleearth.commonervote.service.PromotionService;
import com.mcmiddleearth.commonervote.service.ReviewService;
import com.mcmiddleearth.commonervote.service.SettingsCache;
import com.mcmiddleearth.commonervote.service.SettingsService;
import com.mcmiddleearth.commonervote.service.VoteService;
import com.mcmiddleearth.commonervote.service.XpBarService;
import com.mcmiddleearth.commonervote.storage.ConnectionFactory;
import com.mcmiddleearth.commonervote.storage.DatabaseConfig;
import com.mcmiddleearth.commonervote.storage.DbExecutor;
import com.mcmiddleearth.commonervote.storage.PromotionStore;
import com.mcmiddleearth.commonervote.storage.Schema;
import com.mcmiddleearth.commonervote.storage.SettingsStore;
import com.mcmiddleearth.commonervote.storage.VoteStore;
import com.mcmiddleearth.commonervote.ui.DialogPresenter;
import com.mcmiddleearth.commonervote.ui.PaperDialogPresenter;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.luckperms.api.LuckPerms;

/**
 * Builds the plugin on enable and stops its database thread on disable.
 * <p>
 * Each enable builds exactly one of every service. The promotion service remembers, for the whole server run, whose
 * promotion command has run. So the minute timer reloads only the settings cache (and retries the database setup
 * until it has succeeded), and never rebuilds the services.
 * <p>
 * The protected {@code create} methods are where the plugin meets the outside world (the database, its own threads,
 * LuckPerms, the dialogs, the console and Paper's profile cache). A test subclass replaces them with an in-memory
 * database, inline executors and fakes. Not final, because MockBukkit subclasses the main class to load it in tests.
 */
public class CommonerVotePlugin extends JavaPlugin {

    /** The config.yml keys of 1.2.9 that 2.0 no longer reads: these settings live in the database now. */
    private static final List<String> LEGACY_KEYS = List.of("validityPeriod", "staffWeight", "otherWeight",
            "votesNeeded", "allowMultipleVotes", "automatedPromotion", "useXpBar", "applicationNeeded",
            "commonerGroupName", "promotionMessage", "promotionCommand", "useDatabase");

    /** How often the shared settings are read again, for what another server saved: every minute, in ticks. */
    private static final long SETTINGS_REFRESH_TICKS = 1200;

    /** How long {@link #onDisable} waits for the database work already queued. */
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(5);

    // Set by onEnable once LuckPerms is found; null until then.
    private DbExecutor db;
    private BungeeMessenger messenger;
    private PromotionService promotion;
    private VoteService voting;
    private ReviewService reviewing;
    private SettingsService configuring;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        DatabaseConfig databaseConfig = DatabaseConfig.from(getConfig().getConfigurationSection("database"));
        boolean useBungee = getConfig().getBoolean("useBungee", true);
        logLegacyKeys(getConfig());

        RankService ranks = createRankService();
        if (ranks == null) {
            return; // createRankService has disabled the plugin
        }
        ConnectionFactory connections = createConnectionFactory(databaseConfig);
        db = createDbExecutor();
        Executor main = createMainExecutor();
        DialogPresenter presenter = createPresenter();
        CommandRunner commands = createCommandRunner();
        ProfileCache profiles = createProfileCache();
        Logger logger = getLogger();
        LongSupplier clock = System::currentTimeMillis;

        VoteStore votes = new VoteStore(connections);
        PromotionStore promotions = new PromotionStore(connections);
        SettingsStore settingsStore = new SettingsStore(connections);
        SettingsCache settings = new SettingsCache(db, settingsStore, logger);
        XpBarService xp = new XpBarService(settings::get);
        // The promotion service needs the messenger, and the messenger hands what it receives to the promotion
        // service: the lambda reads the field when a message arrives, which is after register() below.
        messenger = new BungeeMessenger(this, settings::get, useBungee,
                (player, kind) -> promotion.onRemoteUpdate(player, kind));
        promotion = new PromotionService(db, main, votes, promotions, settings, ranks, messenger, presenter, xp,
                commands, logger, clock);
        voting = new VoteService(db, main, votes, settings, ranks, profiles, messenger, presenter, xp, promotion,
                clock);
        reviewing = new ReviewService(db, main, votes, settings, ranks, profiles, messenger, presenter, xp, promotion,
                clock);
        configuring = new SettingsService(db, main, settingsStore, settings, presenter);
        // Before anything can send: a send on a channel that is not registered throws.
        messenger.register();

        // The setup and then the reload, both on the one database thread, which runs them in that order. The reload
        // does not depend on the setup: if the setup fails, it still loads whatever settings can be read. Until a
        // reload succeeds, the cache holds the default settings. The executor logs every failure.
        AtomicBoolean setUpDone = new AtomicBoolean();
        Runnable setUp = () -> db.supply(() -> {
            Schema.create(connections);
            settingsStore.seedMissing(Settings.defaults().toMap());
            setUpDone.set(true);
            return null;
        });
        setUp.run();
        settings.reload();

        // Every minute, the setup runs again until it has succeeded once (in case the database was down at startup),
        // followed by the reload, for the settings that another server saved.
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (!setUpDone.get()) {
                setUp.run();
            }
            settings.reload();
        }, SETTINGS_REFRESH_TICKS, SETTINGS_REFRESH_TICKS);
        getServer().getPluginManager().registerEvents(new PlayerListener(promotion, xp), this);
        // The tree may outlive a disable, so the command checks isEnabled before it calls a service.
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar().register(
                new VoteCommand(voting, reviewing, configuring, this::isEnabled).build(), VoteCommand.DESCRIPTION,
                List.of()));
    }

    /**
     * Paper has marked the plugin disabled before this runs, so the main executor drops whatever reaches it now, and
     * nothing may be scheduled from here; Bukkit cancels the settings refresh itself. The proxy channel closes first,
     * so no incoming message starts new database work; then the database thread finishes what is queued and stops.
     * Work that still arrives after that (a late LuckPerms answer, say) is refused, and its continuation dropped.
     */
    @Override
    public void onDisable() {
        if (messenger != null) {
            messenger.unregister();
        }
        if (db != null) {
            db.shutdown(SHUTDOWN_WAIT);
        }
    }

    /** The vote flows, for the command tree; null until the plugin is enabled. */
    public VoteService voteService() {
        return voting;
    }

    /** The review flows, for the command tree; null until the plugin is enabled. */
    public ReviewService reviewService() {
        return reviewing;
    }

    /** The settings dialogs, for the command tree; null until the plugin is enabled. */
    public SettingsService settingsService() {
        return configuring;
    }

    /** The promotion flows, for the command tree; null until the plugin is enabled. */
    public PromotionService promotionService() {
        return promotion;
    }

    // ---- where the plugin meets the outside world ----

    /** Connections to the vote database, a new one for each unit of work. */
    protected ConnectionFactory createConnectionFactory(DatabaseConfig c) {
        return ConnectionFactory.of(c);
    }

    /** The one thread that runs all database work of this server. */
    protected DbExecutor createDbExecutor() {
        return DbExecutor.singleThread(getLogger());
    }

    /**
     * LuckPerms, through its Bukkit service. The plugin cannot do without it (it finds players and guards every
     * promotion with it), so when it is missing this logs SEVERE, disables the plugin and returns null.
     */
    protected RankService createRankService() {
        LuckPerms luckPerms = getServer().getServicesManager().load(LuckPerms.class);
        if (luckPerms == null) {
            getLogger().severe("LuckPerms is required, but its API is not available. CommonerVote is disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return null;
        }
        return new LuckPermsRankService(luckPerms);
    }

    /** Shows the screens as Paper dialogs. */
    protected DialogPresenter createPresenter() {
        return new PaperDialogPresenter(this);
    }

    /** Runs a command as the server console. */
    protected CommandRunner createCommandRunner() {
        return command -> getServer().dispatchCommand(getServer().getConsoleSender(), command);
    }

    /** The names Paper keeps in memory for players who are offline: its profile cache. */
    protected ProfileCache createProfileCache() {
        return ProfileCache.of(getServer());
    }

    /**
     * Runs work on the main thread, through the scheduler. Work handed over after the plugin was disabled is dropped:
     * Paper refuses to schedule for a disabled plugin, and the services it was for are gone.
     */
    protected Executor createMainExecutor() {
        return task -> {
            if (isEnabled()) {
                getServer().getScheduler().runTask(this, task);
            }
        };
    }

    /**
     * Says once, at INFO, which 1.2.9 keys config.yml still has that are now ignored, and warns about
     * {@code useDatabase: false}, which 1.2.9 obeyed and 2.0 cannot.
     */
    private void logLegacyKeys(FileConfiguration config) {
        List<String> found = LEGACY_KEYS.stream().filter(config::isSet).toList();
        if (!found.isEmpty()) {
            getLogger().info("config.yml still has CommonerVote 1.x settings, which are ignored now: "
                    + String.join(", ", found) + ". The settings live in the database and are shared by every "
                    + "server; change them in game with /vote config.");
        }
        if (config.isSet("useDatabase") && !config.getBoolean("useDatabase")) {
            getLogger().warning("config.yml has useDatabase: false, which is ignored: CommonerVote 2 keeps its votes "
                    + "always in the database, the one in the database section of config.yml.");
        }
    }
}
