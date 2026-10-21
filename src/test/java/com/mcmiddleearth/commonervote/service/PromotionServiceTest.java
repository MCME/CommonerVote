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
import com.mcmiddleearth.commonervote.network.Messenger;
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

    private static final UUID VOTER = UUID.randomUUID();

    /** Gives {@code player} valid votes adding up to {@code score}. */
    private void scoreOf(Player player, double score) throws SQLException {
        vote(VOTER, player.getUniqueId(), score, 1);
    }

    private List<LogRecord> infoNaming(String text) {
        return logger.records(Level.INFO).stream().filter(r -> r.getMessage().contains(text)).toList();
    }

    /** The WARNING lines the database executor writes for a failed database task. */
    private List<LogRecord> unavailableWarnings() {
        return logger.records(Level.WARNING).stream()
                .filter(r -> r.getMessage().startsWith("The vote database is unavailable")).toList();
    }

    // ---- auto-promotion ----

    @Test
    void auto_promotes_default_only() throws SQLException {
        PlayerMock bilbo = guest("Bilbo_B");
        bilbo.setLevel(3);
        bilbo.setExp(0.8f);

        promotion.checkAutoPromotion(bilbo, 1.0);

        assertEquals(List.of(promotionCommand(bilbo.getUniqueId())), commands.commands());
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

        assertEquals(List.of("lp user " + bilbo.getUniqueId() + " parent add citizen"), commands.commands());
    }

    @Test
    void the_command_names_the_player_by_uuid_whatever_name_is_shown() {
        // A renamed guest: the name shown here is the old one, LuckPerms knows the new one, and the old one may be
        // somebody else's by now. Only the UUID is sure to be this player.
        PlayerMock elrond = staff("Elrond");
        UUID renamed = UUID.randomUUID();
        ranks.add(renamed, "newname");
        PlayerMock bilbo = guest("Bilbo_B");

        promotion.promoteNow(elrond, renamed, "OldName");
        promotion.checkAutoPromotion(bilbo, 1.0);

        assertEquals(List.of("lp user " + renamed + " parent set commoner",
                "lp user " + bilbo.getUniqueId() + " parent set commoner"), commands.commands());
        assertTrue(commands.commands().stream().noneMatch(c -> c.contains("OldName") || c.contains("newname")
                || c.contains("Bilbo_B")), commands.commands().toString());
        assertEquals(Messages.promoted("OldName"), elrond.nextComponentMessage(), "the messages keep the name");
        assertEquals("★ OldName has been promoted to Commoner!", Texts.plain(messenger.announcements().get(0)));
        assertEquals("★ Bilbo_B has been promoted to Commoner!", Texts.plain(messenger.announcements().get(1)));
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

        assertEquals(List.of(promotionCommand(bilbo.getUniqueId())), commands.commands());
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

        assertEquals(List.of(promotionCommand(bilbo.getUniqueId())), commands.commands());
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

        assertEquals(List.of(promotionCommand(bilbo.getUniqueId())), commands.commands(),
                "a block does not hold the player for the session");
    }

    @Test
    void no_double_promotion() {
        PlayerMock bilbo = guest("Bilbo_B");

        promotion.checkAutoPromotion(bilbo, 1.0);
        promotion.checkAutoPromotion(bilbo, 1.0);

        assertEquals(List.of(promotionCommand(bilbo.getUniqueId())), commands.commands());
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

        assertEquals(List.of(promotionCommand(bilbo.getUniqueId())), commands.commands());
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

        assertEquals(List.of(promotionCommand(bilbo.getUniqueId())), commands.commands());
    }

    @Test
    void auto_off() throws SQLException {
        setting("autoPromote", "false");
        PlayerMock bilbo = guest("Bilbo_B");

        promotion.checkAutoPromotion(bilbo, 1.0);

        assertEquals(List.of(), commands.commands());
    }

    @Test
    void no_promotion_before_the_settings_have_loaded() throws SQLException {
        // The database has been down since startup: the cache holds the defaults, which may not be what is stored
        // (autoPromote off, another command or group), so they must not promote anyone.
        neverLoadSettings();
        PlayerMock elrond = staff("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        scoreOf(bilbo, 1.0);
        UUID frodo = UUID.randomUUID();
        ranks.add(frodo, "Frodo99");
        presenter.clear();

        promotion.checkAutoPromotion(bilbo, 1.0);
        promotion.onJoin(bilbo);
        promotion.promoteNow(elrond, frodo, "Frodo99");

        assertEquals(List.of(), commands.commands());
        assertEquals(Optional.empty(), promotionOf(bilbo.getUniqueId()));
        assertEquals(Optional.empty(), promotionOf(frodo));
        assertNull(presenter.last(bilbo, WelcomeView.class));
        assertEquals(List.of(), messenger.announcements());
        assertNull(bilbo.nextComponentMessage(), "the auto-check is silent");
        assertEquals(Messages.databaseDown(), elrond.nextComponentMessage(), "the admin is told");
        assertNull(elrond.nextComponentMessage());

        settings.reload().join();
        promotion.checkAutoPromotion(bilbo, 1.0);
        promotion.promoteNow(elrond, frodo, "Frodo99");

        assertEquals(List.of(promotionCommand(bilbo.getUniqueId()), promotionCommand(frodo)), commands.commands(),
                "once loaded, the same calls promote");
    }

    // ---- promote now ----

    @Test
    void promote_now_offline() throws SQLException {
        PlayerMock elrond = staff("Elrond");
        UUID bilbo = UUID.randomUUID();
        ranks.add(bilbo, "Bilbo_B");

        promotion.promoteNow(elrond, bilbo, "Bilbo_B");

        assertEquals(List.of(promotionCommand(bilbo)), commands.commands());
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

        assertEquals(List.of(promotionCommand(bilbo.getUniqueId())), commands.commands());
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

        assertEquals(List.of(promotionCommand(bilbo)), commands.commands());
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

        assertEquals(List.of(promotionCommand(bilbo.getUniqueId())), commands.commands());
        assertEquals(Messages.targetExempt("Bilbo_B"), elrond.nextComponentMessage());
    }

    @Test
    void promote_now_after_an_auto_promotion_in_this_run() {
        PlayerMock elrond = staff("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        promotion.checkAutoPromotion(bilbo, 1.0);
        bilbo.disconnect();
        promotion.onQuit(bilbo); // drops the session's claim; LuckPerms has not applied the command yet

        promotion.promoteNow(elrond, bilbo.getUniqueId(), "Bilbo_B");

        assertEquals(List.of(promotionCommand(bilbo.getUniqueId())), commands.commands());
        assertEquals(Messages.targetExempt("Bilbo_B"), elrond.nextComponentMessage());
        assertEquals(1, messenger.announcements().size());
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

        assertEquals(List.of(promotionCommand(bilbo.getUniqueId())), commands.commands());
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
    void join_pending_welcome_keeps_real_xp() throws SQLException {
        // Promoted while away, and has earned levels since: the bar is no longer the meter.
        PlayerMock frodo = exempt("Frodo99");
        frodo.setLevel(5);
        frodo.setExp(0.4f);
        recordPromotion(frodo.getUniqueId(), Source.MANUAL, false);

        promotion.onJoin(frodo);

        assertNotNull(presenter.last(frodo, WelcomeView.class));
        assertEquals(5, frodo.getLevel(), "the player's own levels stay");
        assertEquals(0.4f, frodo.getExp());
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
        assertEquals(List.of(promotionCommand(bilbo.getUniqueId())), commands.commands());
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

        assertEquals(List.of(promotionCommand(bilbo.getUniqueId())), commands.commands());
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

        assertEquals(List.of(promotionCommand(bilbo.getUniqueId())), commands.commands());
    }

    @Test
    void a_continuation_from_the_last_session_does_nothing_after_a_rejoin() throws SQLException {
        QueueExecutor queue = new QueueExecutor();
        main = queue;
        build();
        PlayerMock bilbo = guest("Bilbo_B"); // no votes left by the time of the new session

        promotion.checkAutoPromotion(bilbo, 1.0); // a score seen in the last session
        bilbo.disconnect();
        promotion.onQuit(bilbo);
        bilbo.reconnect(); // the same Player object reads as online again, as in Paper
        promotion.onJoin(bilbo);
        queue.runAll();

        assertEquals(List.of(), commands.commands(), "the last session's check does not promote in this one");
    }

    @Test
    void a_rejoin_before_luckperms_applied_does_not_promote_again() throws SQLException {
        PlayerMock bilbo = guest("Bilbo_B");
        scoreOf(bilbo, 1.0);
        promotion.checkAutoPromotion(bilbo, 1.0);

        bilbo.disconnect();
        promotion.onQuit(bilbo);
        bilbo.reconnect(); // LuckPerms has not applied the command yet: still a guest in {default}
        promotion.onJoin(bilbo);
        promotion.checkAutoPromotion(bilbo, 1.0);

        assertEquals(List.of(promotionCommand(bilbo.getUniqueId())), commands.commands(),
                "the command runs once per player per server run");
        assertEquals(1, messenger.announcements().size());
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
        assertEquals(List.of(promotionCommand(bilbo.getUniqueId())), commands.commands());
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
        assertEquals(List.of(promotionCommand(bilbo.getUniqueId())), commands.commands());
    }

    // ---- failures ----

    @Test
    void database_down_on_join_and_update_is_silent() throws SQLException {
        breakDatabase();
        presenter.clear();
        PlayerMock bilbo = guest("Bilbo_B");
        PlayerMock frodo = exempt("Frodo99");

        assertDoesNotThrow(() -> promotion.onJoin(bilbo));
        assertDoesNotThrow(() -> promotion.onRemoteUpdate(bilbo, Update.VOTED));
        assertDoesNotThrow(() -> promotion.onJoin(frodo));

        assertNull(bilbo.nextComponentMessage(), "the player did not start these flows");
        assertNull(frodo.nextComponentMessage());
        assertNull(bilbo.nextActionBar());
        assertNull(presenter.last(bilbo, WelcomeView.class));
        assertNull(presenter.last(frodo, WelcomeView.class));
        assertEquals(List.of(), commands.commands());
        assertFalse(unavailableWarnings().isEmpty(), "the database executor logs the failures");
    }

    @Test
    void database_down_after_the_command_still_finishes_the_promotion() throws SQLException {
        breakDatabase();
        presenter.clear();
        PlayerMock bilbo = guest("Bilbo_B");
        bilbo.setLevel(3);
        bilbo.setExp(0.8f);

        promotion.checkAutoPromotion(bilbo, 1.0);

        assertEquals(List.of(promotionCommand(bilbo.getUniqueId())), commands.commands());
        assertNotNull(presenter.last(bilbo, WelcomeView.class), "the command ran, so the promotion goes on");
        assertEquals(0, bilbo.getLevel());
        assertEquals(0.0f, bilbo.getExp());
        assertEquals("★ Bilbo_B has been promoted to Commoner!", Texts.plain(messenger.announcements().get(0)));
        assertNull(bilbo.nextComponentMessage(), "no database line next to the welcome");
        assertFalse(unavailableWarnings().isEmpty(), "the database executor logs the failed save");

        // A later check, in a new session, before LuckPerms has applied the command.
        promotion.onQuit(bilbo);
        promotion.onJoin(bilbo);
        promotion.checkAutoPromotion(bilbo, 1.0);

        assertEquals(List.of(promotionCommand(bilbo.getUniqueId())), commands.commands(), "never a second command");
        assertEquals(1, messenger.announcements().size());
    }

    @Test
    void database_down_on_promote_now_still_confirms() throws SQLException {
        breakDatabase();
        PlayerMock elrond = staff("Elrond");
        UUID bilbo = UUID.randomUUID();
        ranks.add(bilbo, "Bilbo_B");

        promotion.promoteNow(elrond, bilbo, "Bilbo_B");

        assertEquals(List.of(promotionCommand(bilbo)), commands.commands());
        assertEquals(Messages.promoted("Bilbo_B"), elrond.nextComponentMessage());
        assertNull(elrond.nextComponentMessage(), "no database line next to the confirmation");
        assertEquals(1, messenger.announcements().size());
    }

    @Test
    void a_failed_announcement_comes_after_the_local_steps() throws SQLException {
        Messenger broken = mock(Messenger.class);
        doThrow(new IllegalStateException("channel not registered")).when(broken).announce(any());
        PromotionService service = new PromotionService(db, main, votes, promotions, settings, ranks, broken,
                presenter, xp, commands, logger, () -> now);
        PlayerMock elrond = staff("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        bilbo.setExp(0.8f);

        assertDoesNotThrow(() -> service.promoteNow(elrond, bilbo.getUniqueId(), "Bilbo_B"));

        assertNotNull(presenter.last(bilbo, WelcomeView.class));
        assertTrue(promotionOf(bilbo.getUniqueId()).orElseThrow().welcomed());
        assertEquals(Messages.promoted("Bilbo_B"), elrond.nextComponentMessage());
        assertEquals(0.0f, bilbo.getExp());
        List<LogRecord> warnings = logger.records(Level.WARNING);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).getMessage().contains("Bilbo_B"));
        assertEquals(List.of(), logger.records(Level.SEVERE));
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
        assertNull(bilbo.nextComponentMessage(), "the guest did not start the auto-check");
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
