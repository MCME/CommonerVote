package com.mcmiddleearth.commonervote.service;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.mcmiddleearth.commonervote.Permissions;
import com.mcmiddleearth.commonervote.network.Messenger.Update;
import com.mcmiddleearth.commonervote.rank.RankService;
import com.mcmiddleearth.commonervote.storage.PromotionRecord;
import com.mcmiddleearth.commonervote.storage.PromotionRecord.Source;
import com.mcmiddleearth.commonervote.support.QueueExecutor;
import com.mcmiddleearth.commonervote.support.ServiceTestBase;
import com.mcmiddleearth.commonervote.ui.DialogPresenter;
import com.mcmiddleearth.commonervote.ui.Texts;
import com.mcmiddleearth.commonervote.ui.view.WelcomeView;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class PromotionServiceTest extends ServiceTestBase {

    private static final String COMMAND = "lp user Bilbo_B parent set commoner";

    private static final UUID VOTER = UUID.randomUUID();

    /** Gives {@code player} valid votes adding up to {@code score}. */
    private void scoreOf(Player player, double score) throws SQLException {
        vote(VOTER, player.getUniqueId(), score, 1);
    }

    private List<LogRecord> infoNaming(String text) {
        return logger.records(Level.INFO).stream().filter(r -> r.getMessage().contains(text)).toList();
    }

    // ---- auto-promotion ----

    @Test
    void auto_promotes_default_only() throws SQLException {
        PlayerMock bilbo = guest("Bilbo_B");
        bilbo.setLevel(3);
        bilbo.setExp(0.8f);

        promotion.checkAutoPromotion(bilbo, 1.0);

        assertEquals(List.of(COMMAND), commands.commands());
        PromotionRecord record = promotionOf(bilbo.getUniqueId()).orElseThrow();
        assertEquals(Source.AUTO, record.source());
        assertTrue(record.welcomed(), "the welcome was shown at once");
        assertEquals(NOW, record.promotedAt());
        WelcomeView welcome = presenter.last(bilbo, WelcomeView.class);
        assertNotNull(welcome);
        assertEquals(Texts.mini(settings.get().welcomeText(), Texts.placeholders(settings.get(), "Bilbo_B")),
                welcome.welcomeText());
        assertEquals("https://www.mcmiddleearth.com/help/terms", welcome.rulesUrl());
        assertEquals(1, messenger.announcements().size());
        assertEquals("★ Bilbo_B has been promoted to Commoner!", Texts.plain(messenger.announcements().get(0)));
        assertEquals(0, bilbo.getLevel(), "the XP bar is released");
        assertEquals(0.0f, bilbo.getExp());
    }

    @Test
    void the_command_uses_the_stored_command_and_group() throws SQLException {
        setting("promotionCommand", "lp user <player> parent add <group>");
        setting("commonerGroup", "citizen");
        PlayerMock bilbo = guest("Bilbo_B");

        promotion.checkAutoPromotion(bilbo, 1.0);

        assertEquals(List.of("lp user Bilbo_B parent add citizen"), commands.commands());
    }

    @Test
    void welcome_and_announcement_use_the_stored_texts() throws SQLException {
        setting("welcomeText", "Welcome, <player>! Votes last <validity_days> days.");
        setting("announceText", "<player> made it");
        setting("rulesUrl", "https://example.org/rules");
        PlayerMock bilbo = guest("Bilbo_B");

        promotion.checkAutoPromotion(bilbo, 1.0);

        WelcomeView welcome = presenter.last(bilbo, WelcomeView.class);
        assertEquals("Welcome, Bilbo_B! Votes last 100 days.", Texts.plain(welcome.welcomeText()));
        assertEquals("https://example.org/rules", welcome.rulesUrl());
        assertEquals("Bilbo_B made it", Texts.plain(messenger.announcements().get(0)));
    }

    @Test
    void xp_bar_off_keeps_the_xp() throws SQLException {
        setting("xpBar", "false");
        PlayerMock bilbo = guest("Bilbo_B");
        bilbo.setLevel(7);
        bilbo.setExp(0.3f);

        promotion.checkAutoPromotion(bilbo, 1.0);

        assertEquals(List.of(COMMAND), commands.commands());
        assertNotNull(presenter.last(bilbo, WelcomeView.class));
        assertEquals(7, bilbo.getLevel(), "with the switch off the XP is the player's own");
        assertEquals(0.3f, bilbo.getExp());
    }

    @Test
    void guard_blocks_extra_group() throws SQLException {
        presenter.clear();
        PlayerMock bilbo = guest("Bilbo_B");
        ranks.setGroups(bilbo.getUniqueId(), "default", "badge_moderator");

        promotion.checkAutoPromotion(bilbo, 1.0);

        assertEquals(List.of(), commands.commands());
        assertEquals(Optional.empty(), promotionOf(bilbo.getUniqueId()));
        assertNull(presenter.last(bilbo, WelcomeView.class));
        assertEquals(List.of(), messenger.announcements());
        assertEquals(1, infoNaming("badge_moderator").size());

        promotion.checkAutoPromotion(bilbo, 1.0);

        assertEquals(List.of(), commands.commands());
        assertEquals(1, infoNaming("badge_moderator").size(), "one line per player per session");
    }

    @Test
    void guard_logs_again_in_a_new_session() {
        PlayerMock bilbo = guest("Bilbo_B");
        ranks.setGroups(bilbo.getUniqueId(), "default", "badge_moderator");
        promotion.checkAutoPromotion(bilbo, 1.0);

        promotion.onQuit(bilbo);
        promotion.checkAutoPromotion(bilbo, 1.0);

        assertEquals(2, infoNaming("badge_moderator").size());
    }

    @Test
    void promotable_group_allows() throws SQLException {
        setting("promotableGroups", "badge_moderator");
        PlayerMock bilbo = guest("Bilbo_B");
        ranks.setGroups(bilbo.getUniqueId(), "default", "badge_moderator");

        promotion.checkAutoPromotion(bilbo, 1.0);

        assertEquals(List.of(COMMAND), commands.commands());
        assertEquals(Source.AUTO, promotionOf(bilbo.getUniqueId()).orElseThrow().source());
    }

    @Test
    void a_blocked_player_is_checked_again_later() throws SQLException {
        PlayerMock bilbo = guest("Bilbo_B");
        ranks.setGroups(bilbo.getUniqueId(), "default", "badge_moderator");
        promotion.checkAutoPromotion(bilbo, 1.0);
        assertEquals(List.of(), commands.commands());

        setting("promotableGroups", "badge_moderator");
        promotion.checkAutoPromotion(bilbo, 1.0);

        assertEquals(List.of(COMMAND), commands.commands(), "a block does not hold the player for the session");
    }

    @Test
    void no_double_promotion() {
        PlayerMock bilbo = guest("Bilbo_B");

        promotion.checkAutoPromotion(bilbo, 1.0);
        promotion.checkAutoPromotion(bilbo, 1.0);

        assertEquals(List.of(COMMAND), commands.commands());
        assertEquals(1, messenger.announcements().size());
    }

    @Test
    void no_double_promotion_while_the_first_is_under_way() throws SQLException {
        QueueExecutor queue = new QueueExecutor();
        main = queue;
        build();
        PlayerMock bilbo = guest("Bilbo_B");

        promotion.checkAutoPromotion(bilbo, 1.0);
        promotion.checkAutoPromotion(bilbo, 1.0);
        queue.runAll();

        assertEquals(List.of(COMMAND), commands.commands());
    }

    @Test
    void exempt_or_below_threshold_skips() {
        PlayerMock frodo = exempt("Frodo99");
        PlayerMock bilbo = guest("Bilbo_B");

        promotion.checkAutoPromotion(frodo, 1.0);
        promotion.checkAutoPromotion(bilbo, 0.8);

        assertEquals(List.of(), commands.commands());
    }

    @Test
    void a_score_within_float_tolerance_promotes() {
        PlayerMock bilbo = guest("Bilbo_B");

        promotion.checkAutoPromotion(bilbo, 0.99995);

        assertEquals(List.of(COMMAND), commands.commands());
    }

    @Test
    void auto_off() throws SQLException {
        setting("autoPromote", "false");
        PlayerMock bilbo = guest("Bilbo_B");

        promotion.checkAutoPromotion(bilbo, 1.0);

        assertEquals(List.of(), commands.commands());
    }

    // ---- promote now ----

    @Test
    void promote_now_offline() throws SQLException {
        PlayerMock elrond = staff("Elrond");
        UUID bilbo = UUID.randomUUID();
        ranks.add(bilbo, "Bilbo_B");

        promotion.promoteNow(elrond, bilbo, "Bilbo_B");

        assertEquals(List.of(COMMAND), commands.commands());
        PromotionRecord record = promotionOf(bilbo).orElseThrow();
        assertEquals(Source.MANUAL, record.source());
        assertFalse(record.welcomed(), "the welcome waits for the next join");
        assertEquals(Messages.promoted("Bilbo_B"), elrond.nextComponentMessage());
        assertEquals("★ Bilbo_B has been promoted to Commoner!", Texts.plain(messenger.announcements().get(0)));
    }

    @Test
    void promote_now_online_welcomes_at_once() throws SQLException {
        PlayerMock elrond = staff("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        bilbo.setExp(0.6f);

        promotion.promoteNow(elrond, bilbo.getUniqueId(), "Bilbo_B");

        assertEquals(List.of(COMMAND), commands.commands());
        PromotionRecord record = promotionOf(bilbo.getUniqueId()).orElseThrow();
        assertEquals(Source.MANUAL, record.source());
        assertTrue(record.welcomed());
        assertNotNull(presenter.last(bilbo, WelcomeView.class));
        assertEquals(0.0f, bilbo.getExp());
        assertEquals(Messages.promoted("Bilbo_B"), elrond.nextComponentMessage());
    }

    @Test
    void promote_now_blocked() throws SQLException {
        PlayerMock elrond = staff("Elrond");
        UUID bilbo = UUID.randomUUID();
        ranks.add(bilbo, "Bilbo_B").setGroups(bilbo, "artist");

        promotion.promoteNow(elrond, bilbo, "Bilbo_B");

        assertEquals(Messages.promoteBlocked("Bilbo_B", "artist"), elrond.nextComponentMessage());
        assertEquals(List.of(), commands.commands());
        assertEquals(Optional.empty(), promotionOf(bilbo));
        assertEquals(List.of(), messenger.announcements());
    }

    @Test
    void promote_now_refuses_an_exempt_target() {
        PlayerMock elrond = staff("Elrond");
        PlayerMock frodo = exempt("Frodo99");
        UUID sam = UUID.randomUUID();
        ranks.add(sam, "Sam").setExempt(sam, true);

        promotion.promoteNow(elrond, frodo.getUniqueId(), "Frodo99");
        promotion.promoteNow(elrond, sam, "Sam");

        assertEquals(Messages.targetExempt("Frodo99"), elrond.nextComponentMessage());
        assertEquals(Messages.targetExempt("Sam"), elrond.nextComponentMessage());
        assertEquals(List.of(), commands.commands());
    }

    @Test
    void promote_now_twice_runs_one_command() {
        PlayerMock elrond = staff("Elrond");
        UUID bilbo = UUID.randomUUID();
        ranks.add(bilbo, "Bilbo_B");

        promotion.promoteNow(elrond, bilbo, "Bilbo_B");
        promotion.promoteNow(elrond, bilbo, "Bilbo_B");

        assertEquals(List.of(COMMAND), commands.commands());
        assertEquals(Messages.promoted("Bilbo_B"), elrond.nextComponentMessage());
        assertEquals(Messages.targetExempt("Bilbo_B"), elrond.nextComponentMessage());
    }

    @Test
    void promote_now_waits_for_an_auto_promotion_under_way() throws SQLException {
        QueueExecutor queue = new QueueExecutor();
        main = queue;
        build();
        PlayerMock elrond = staff("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");

        promotion.checkAutoPromotion(bilbo, 1.0);
        promotion.promoteNow(elrond, bilbo.getUniqueId(), "Bilbo_B");
        queue.runAll();

        assertEquals(List.of(COMMAND), commands.commands());
        assertEquals(Messages.targetExempt("Bilbo_B"), elrond.nextComponentMessage());
    }

    @Test
    void promote_now_goes_on_when_the_target_joins_meanwhile() throws SQLException {
        QueueExecutor queue = new QueueExecutor();
        main = queue;
        build();
        PlayerMock elrond = staff("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        scoreOf(bilbo, 1.0);

        promotion.promoteNow(elrond, bilbo.getUniqueId(), "Bilbo_B");
        promotion.onJoin(bilbo); // drops the claim; its auto-check comes after the admin's promotion
        queue.runAll();

        assertEquals(List.of(COMMAND), commands.commands());
        assertEquals(Messages.promoted("Bilbo_B"), elrond.nextComponentMessage());
        assertEquals(Source.MANUAL, promotionOf(bilbo.getUniqueId()).orElseThrow().source());
    }

    // ---- join ----

    @Test
    void join_detected() throws SQLException {
        presenter.clear();
        PlayerMock frodo = exempt("Frodo99");
        vote(VOTER, frodo.getUniqueId(), 0.2, 150);

        promotion.onJoin(frodo);

        PromotionRecord record = promotionOf(frodo.getUniqueId()).orElseThrow();
        assertEquals(Source.DETECTED, record.source());
        assertEquals(NOW, record.promotedAt());
        assertTrue(record.welcomed(), "someone promoted by hand gets no welcome");
        assertNull(presenter.last(frodo, WelcomeView.class));
        assertEquals(List.of(), commands.commands());
    }

    @Test
    void join_detected_needs_votes() throws SQLException {
        PlayerMock frodo = exempt("Frodo99");

        promotion.onJoin(frodo);

        assertEquals(Optional.empty(), promotionOf(frodo.getUniqueId()));
    }

    @Test
    void join_keeps_an_existing_record() throws SQLException {
        PlayerMock frodo = exempt("Frodo99");
        vote(VOTER, frodo.getUniqueId(), 0.2, 3);
        now = NOW + 1000;
        recordPromotion(frodo.getUniqueId(), Source.AUTO, true);

        promotion.onJoin(frodo);

        assertEquals(new PromotionRecord(frodo.getUniqueId(), NOW + 1000, Source.AUTO, true),
                promotionOf(frodo.getUniqueId()).orElseThrow());
    }

    @Test
    void join_unpromoted() throws SQLException {
        PlayerMock bilbo = guest("Bilbo_B");
        recordPromotion(bilbo.getUniqueId(), Source.MANUAL, true);

        promotion.onJoin(bilbo);

        assertEquals(Optional.empty(), promotionOf(bilbo.getUniqueId()));
    }

    @Test
    void join_pending_welcome() throws SQLException {
        PlayerMock frodo = exempt("Frodo99");
        frodo.setExp(0.8f);
        recordPromotion(frodo.getUniqueId(), Source.MANUAL, false);

        promotion.onJoin(frodo);

        WelcomeView welcome = presenter.last(frodo, WelcomeView.class);
        assertNotNull(welcome);
        assertTrue(promotionOf(frodo.getUniqueId()).orElseThrow().welcomed());
        assertEquals(0.0f, frodo.getExp(), "the old progress meter is emptied");

        presenter.clear();
        promotion.onJoin(frodo);

        assertNull(presenter.last(frodo, WelcomeView.class), "the welcome is shown once");
    }

    @Test
    void join_refreshes_and_checks() throws SQLException {
        PlayerMock frodo = guest("Frodo99");
        scoreOf(frodo, 0.6);
        PlayerMock bilbo = guest("Bilbo_B");
        scoreOf(bilbo, 1.0);

        promotion.onJoin(frodo);
        promotion.onJoin(bilbo);

        assertEquals(0, frodo.getLevel());
        assertEquals(0.6f, frodo.getExp());
        assertEquals(List.of(COMMAND), commands.commands());
    }

    @Test
    void join_ignores_expired_votes() throws SQLException {
        PlayerMock bilbo = guest("Bilbo_B");
        vote(VOTER, bilbo.getUniqueId(), 0.6, 2);
        vote(UUID.randomUUID(), bilbo.getUniqueId(), 0.4, 101);

        promotion.onJoin(bilbo);

        assertEquals(0.6f, bilbo.getExp());
        assertEquals(List.of(), commands.commands());
    }

    // ---- forwarded updates ----

    @Test
    void remote_voted() throws SQLException {
        PlayerMock bilbo = guest("Bilbo_B");
        scoreOf(bilbo, 0.6);

        promotion.onRemoteUpdate(bilbo, Update.VOTED);

        assertEquals("A team member voted for you · 60% to Commoner", Texts.plain(bilbo.nextActionBar()));
        assertEquals(0.6f, bilbo.getExp());
    }

    @Test
    void remote_update_refreshes_without_a_note() throws SQLException {
        PlayerMock bilbo = guest("Bilbo_B");
        scoreOf(bilbo, 0.4);

        promotion.onRemoteUpdate(bilbo, Update.UPDATE);

        assertNull(bilbo.nextActionBar());
        assertEquals(0.4f, bilbo.getExp());
    }

    @Test
    void remote_update_promotes() throws SQLException {
        PlayerMock bilbo = guest("Bilbo_B");
        scoreOf(bilbo, 1.0);

        promotion.onRemoteUpdate(bilbo, Update.UPDATE);

        assertEquals(List.of(COMMAND), commands.commands());
    }

    // ---- quitting between the async work and its continuation (Review Focus 4) ----

    @Test
    void quit_before_continuation() throws SQLException {
        QueueExecutor queue = new QueueExecutor();
        main = queue;
        build();
        presenter.clear();
        PlayerMock bilbo = guest("Bilbo_B");
        scoreOf(bilbo, 1.0);
        bilbo.setExp(0.3f);

        // The auto-check last, so its claim is not dropped by the join: each continuation must see the player is
        // gone by itself. The quit is not handed to the service yet.
        promotion.onJoin(bilbo);
        promotion.onRemoteUpdate(bilbo, Update.VOTED);
        promotion.checkAutoPromotion(bilbo, 1.0);
        bilbo.disconnect();

        assertDoesNotThrow(queue::runAll);
        assertNull(presenter.last(bilbo, WelcomeView.class));
        assertEquals(List.of(), commands.commands());
        assertEquals(Optional.empty(), promotionOf(bilbo.getUniqueId()));
        assertEquals(List.of(), messenger.announcements());
        assertNull(bilbo.nextActionBar());
        assertEquals(0.3f, bilbo.getExp(), "the bar of a player who left is not touched");

        // Nothing holds the player: the next session promotes.
        promotion.onQuit(bilbo);
        bilbo.reconnect();
        promotion.onJoin(bilbo);
        queue.runAll();

        assertEquals(List.of(COMMAND), commands.commands());
    }

    @Test
    void a_continuation_from_the_last_session_does_nothing_after_a_rejoin() throws SQLException {
        QueueExecutor queue = new QueueExecutor();
        main = queue;
        build();
        PlayerMock bilbo = guest("Bilbo_B");

        promotion.checkAutoPromotion(bilbo, 1.0);
        bilbo.disconnect();
        promotion.onQuit(bilbo);
        bilbo.reconnect();
        promotion.onJoin(bilbo);
        promotion.checkAutoPromotion(bilbo, 1.0);
        queue.runAll();

        assertEquals(List.of(COMMAND), commands.commands(), "only the new session's check promotes");
    }

    @Test
    void quit_after_the_command_welcomes_at_the_next_join() throws SQLException {
        Queue<Runnable> steps = new ArrayDeque<>();
        main = steps::add;
        build();
        presenter.clear();
        PlayerMock bilbo = guest("Bilbo_B");

        promotion.checkAutoPromotion(bilbo, 1.0);
        steps.remove().run(); // the guard passes, the command runs and the record is written
        assertEquals(List.of(COMMAND), commands.commands());
        bilbo.disconnect();
        promotion.onQuit(bilbo);
        steps.remove().run(); // back on the main thread: the announcement goes out, the welcome waits

        assertEquals(1, messenger.announcements().size());
        assertNull(presenter.last(bilbo, WelcomeView.class));
        assertFalse(promotionOf(bilbo.getUniqueId()).orElseThrow().welcomed());

        bilbo.reconnect();
        bilbo.addAttachment(plugin, Permissions.EXEMPT, true); // LuckPerms has applied the command by now
        promotion.onJoin(bilbo);
        while (!steps.isEmpty()) {
            steps.remove().run();
        }

        assertNotNull(presenter.last(bilbo, WelcomeView.class));
        assertTrue(promotionOf(bilbo.getUniqueId()).orElseThrow().welcomed());
        assertEquals(List.of(COMMAND), commands.commands());
    }

    // ---- failures ----

    @Test
    void database_down_on_join_and_update() throws SQLException {
        breakDatabase();
        presenter.clear();
        PlayerMock bilbo = guest("Bilbo_B");
        PlayerMock frodo = exempt("Frodo99");

        assertDoesNotThrow(() -> promotion.onJoin(bilbo));
        assertDoesNotThrow(() -> promotion.onRemoteUpdate(bilbo, Update.VOTED));
        assertDoesNotThrow(() -> promotion.onJoin(frodo));

        assertEquals(Messages.databaseDown(), bilbo.nextComponentMessage());
        assertEquals(Messages.databaseDown(), bilbo.nextComponentMessage());
        assertEquals(Messages.databaseDown(), frodo.nextComponentMessage());
        assertNull(bilbo.nextActionBar());
        assertNull(presenter.last(frodo, WelcomeView.class));
        assertEquals(List.of(), commands.commands());
    }

    @Test
    void database_down_after_the_command_lets_a_later_check_retry() throws SQLException {
        breakDatabase();
        presenter.clear();
        PlayerMock bilbo = guest("Bilbo_B");

        promotion.checkAutoPromotion(bilbo, 1.0);

        assertEquals(List.of(COMMAND), commands.commands(), "the record is written after the command");
        assertEquals(Messages.databaseDown(), bilbo.nextComponentMessage());
        assertNull(presenter.last(bilbo, WelcomeView.class));
        assertEquals(List.of(), messenger.announcements());

        promotion.checkAutoPromotion(bilbo, 1.0);

        assertEquals(List.of(COMMAND, COMMAND), commands.commands(), "the failed promotion no longer holds the player");
    }

    @Test
    void database_down_on_promote_now() throws SQLException {
        breakDatabase();
        PlayerMock elrond = staff("Elrond");
        UUID bilbo = UUID.randomUUID();
        ranks.add(bilbo, "Bilbo_B");

        promotion.promoteNow(elrond, bilbo, "Bilbo_B");

        assertEquals(Messages.databaseDown(), elrond.nextComponentMessage());
        assertNull(elrond.nextComponentMessage(), "no success line");
        assertEquals(List.of(), messenger.announcements());
    }

    @Test
    void rank_lookup_failure() throws SQLException {
        RankService failing = new FailingRanks();
        PromotionService service = new PromotionService(db, main, votes, promotions, settings, failing, messenger,
                presenter, xp, commands, logger, () -> now);
        PlayerMock elrond = staff("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");

        service.checkAutoPromotion(bilbo, 1.0);
        service.promoteNow(elrond, UUID.randomUUID(), "Frodo99");

        assertEquals(List.of(), commands.commands());
        assertEquals(Messages.databaseDown(), bilbo.nextComponentMessage());
        assertEquals(Messages.databaseDown(), elrond.nextComponentMessage());
        assertEquals(2, logger.records(Level.WARNING).size(), "nothing else logs a LuckPerms failure");
    }

    @Test
    void a_failing_step_is_logged_not_lost() {
        DialogPresenter broken = mock(DialogPresenter.class);
        doThrow(new IllegalStateException("dialog rejected")).when(broken).showWelcome(any(), any());
        PromotionService service = new PromotionService(db, main, votes, promotions, settings, ranks, messenger,
                broken, xp, commands, logger, () -> now);
        PlayerMock bilbo = guest("Bilbo_B");

        assertDoesNotThrow(() -> service.checkAutoPromotion(bilbo, 1.0));

        assertEquals(1, logger.records(Level.SEVERE).size());
    }

    /** A rank service whose answers all fail, as when LuckPerms cannot reach its storage. */
    private static final class FailingRanks implements RankService {

        private static <T> CompletableFuture<T> failed() {
            return CompletableFuture.failedFuture(new IllegalStateException("LuckPerms storage unavailable"));
        }

        @Override
        public CompletableFuture<Optional<UUID>> lookup(String name) {
            return failed();
        }

        @Override
        public CompletableFuture<Map<UUID, String>> names(Collection<UUID> players) {
            return failed();
        }

        @Override
        public CompletableFuture<Boolean> isExempt(UUID player) {
            return failed();
        }

        @Override
        public CompletableFuture<Set<String>> directGroups(UUID player) {
            return failed();
        }
    }
}
