package com.mcmiddleearth.commonervote.support;

import java.util.concurrent.Executor;
import java.util.logging.Logger;

import com.mcmiddleearth.commonervote.CommonerVotePlugin;
import com.mcmiddleearth.commonervote.rank.RankService;
import com.mcmiddleearth.commonervote.service.CommandRunner;
import com.mcmiddleearth.commonervote.storage.ConnectionFactory;
import com.mcmiddleearth.commonervote.storage.DatabaseConfig;
import com.mcmiddleearth.commonervote.storage.DbExecutor;
import com.mcmiddleearth.commonervote.ui.DialogPresenter;

/**
 * The real plugin with every outside dependency replaced: a fresh H2 database of its own, a database "thread" and a
 * main thread that both run work at once on the caller's thread, the fake LuckPerms, and recording dialogs and console.
 * Everything it logs is kept in {@link #log()}.
 * <p>
 * Load it with {@code MockBukkit.loadWith(TestCommonerVotePlugin.class, description)}, the description read from the
 * filtered plugin.yml on the classpath. MockBukkit loads a generated subclass of it, so it must stay public and not
 * final. The fakes are fields, made when the plugin is constructed: they exist before {@code onEnable} and a test
 * reaches them through the plugin that {@code loadWith} returns.
 * <p>
 * A test that needs the database prepared before the plugin starts (filled in, or failing, see {@link FlakyDatabase})
 * hands it over as a constructor argument: {@code MockBukkit.loadWith(TestCommonerVotePlugin.class, description,
 * database)}.
 */
public class TestCommonerVotePlugin extends CommonerVotePlugin {

    private final CapturingLogger log = CapturingLogger.create();
    private final ConnectionFactory database;
    private final DirectExecutorService databaseThread = new DirectExecutorService();
    private final FakeRankService ranks = new FakeRankService();
    private final RecordingPresenter presenter = new RecordingPresenter();
    private final RecordingCommandRunner commands = new RecordingCommandRunner();

    /** The plugin on a fresh, empty H2 database of its own. */
    public TestCommonerVotePlugin() {
        this(TestDatabase.fresh());
    }

    /** The plugin on the test's own database. */
    public TestCommonerVotePlugin(ConnectionFactory database) {
        this.database = database;
    }

    /** Everything the plugin and its services log, kept instead of printed. */
    @Override
    public Logger getLogger() {
        return log;
    }

    @Override
    protected ConnectionFactory createConnectionFactory(DatabaseConfig c) {
        return database;
    }

    @Override
    protected DbExecutor createDbExecutor() {
        return new DbExecutor(databaseThread, getLogger(), System::currentTimeMillis);
    }

    @Override
    protected RankService createRankService() {
        return ranks;
    }

    @Override
    protected DialogPresenter createPresenter() {
        return presenter;
    }

    @Override
    protected CommandRunner createCommandRunner() {
        return commands;
    }

    @Override
    protected Executor createMainExecutor() {
        return Runnable::run;
    }

    public CapturingLogger log() {
        return log;
    }

    /**
     * What the plugin connects to, for a test's own reads and writes: its own H2 database, or the one the test handed
     * to the constructor.
     */
    public ConnectionFactory database() {
        return database;
    }

    /** What the plugin's database executor runs its tasks on. */
    public DirectExecutorService databaseThread() {
        return databaseThread;
    }

    public FakeRankService ranks() {
        return ranks;
    }

    public RecordingPresenter presenter() {
        return presenter;
    }

    public RecordingCommandRunner commands() {
        return commands;
    }

    /** The real lookup, for {@link WithoutLuckPerms}, which cannot call past this class's override itself. */
    protected final RankService lookUpLuckPerms() {
        return super.createRankService();
    }

    /**
     * The test plugin on a server without LuckPerms: the real {@link CommonerVotePlugin#createRankService()} looks for
     * it, and finds nothing on the MockBukkit server.
     */
    public static class WithoutLuckPerms extends TestCommonerVotePlugin {

        @Override
        protected RankService createRankService() {
            return lookUpLuckPerms();
        }
    }
}
