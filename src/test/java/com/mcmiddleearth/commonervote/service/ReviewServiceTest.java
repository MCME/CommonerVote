package com.mcmiddleearth.commonervote.service;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.permissions.PermissionAttachment;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.mcmiddleearth.commonervote.Permissions;
import com.mcmiddleearth.commonervote.network.Messenger.Update;
import com.mcmiddleearth.commonervote.rank.RankService;
import com.mcmiddleearth.commonervote.storage.PromotionRecord.Source;
import com.mcmiddleearth.commonervote.support.QueueExecutor;
import com.mcmiddleearth.commonervote.support.RecordingMessenger.Notification;
import com.mcmiddleearth.commonervote.support.ServiceTestBase;
import com.mcmiddleearth.commonervote.ui.Texts;
import com.mcmiddleearth.commonervote.ui.view.ConfirmView;
import com.mcmiddleearth.commonervote.ui.view.ReviewDetailView;
import com.mcmiddleearth.commonervote.ui.view.ReviewDetailView.Line;
import com.mcmiddleearth.commonervote.ui.view.ReviewListView;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewServiceTest extends ServiceTestBase {

    /** Somebody who gave the votes that a test seeds. */
    private static final UUID VOTER = UUID.randomUUID();

    /** An admin with the three review rights. */
    private PlayerMock admin(String name) {
        return withPermissions(name, Permissions.REVIEW, Permissions.CLEAR, Permissions.PROMOTE);
    }

    private PlayerMock withPermissions(String name, String... nodes) {
        PlayerMock player = guest(name);
        for (String node : nodes) {
            player.addAttachment(plugin, node, true);
        }
        return player;
    }

    /** What a candidate list says, as {@code "Name percent blockedByGroup"}, with {@code -} for no group. */
    private static List<String> rowsOf(ReviewListView view) {
        return view.rows().stream()
                .map(r -> r.name() + " " + r.percent() + " " + r.blockedByGroup().orElse("-"))
                .toList();
    }

    private ReviewDetailView detailOf(PlayerMock admin) {
        ReviewDetailView view = presenter.last(admin, ReviewDetailView.class);
        assertNotNull(view, "the detail dialog should be open");
        return view;
    }

    private static <T> CompletableFuture<T> failed() {
        return CompletableFuture.failedFuture(new IllegalStateException("LuckPerms down"));
    }

    // ---- the candidate list ----

    @Test
    void list_rows() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        UUID bilbo = remoteGuest("Bilbo_B");
        UUID frodo = remoteGuest("Frodo99");
        UUID sam = remoteGuest("Sam");
        ranks.setGroups(bilbo, "default");
        ranks.setGroups(frodo, "default", "badge_moderator");
        vote(VOTER, frodo, 0.4, 2);
        vote(VOTER, bilbo, 0.8, 1);
        vote(VOTER, sam, 0.6, 3);
        recordPromotion(sam, Source.AUTO, true);

        reviewing.openList(elrond);

        ReviewListView view = presenter.last(elrond, ReviewListView.class);
        assertNotNull(view);
        assertEquals(List.of("Bilbo_B 80 -", "Frodo99 40 badge_moderator"), rowsOf(view),
                "closest to 100% first, and the promoted Sam is not a candidate");
        assertEquals(bilbo, view.rows().get(0).player());
        assertEquals(frodo, view.rows().get(1).player());
        assertEquals(Optional.empty(), view.rows().get(0).blockedByGroup());
        assertEquals(Optional.of("badge_moderator"), view.rows().get(1).blockedByGroup());
        assertNull(elrond.nextComponentMessage());
    }

    @Test
    void list_empty() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        vote(VOTER, remoteGuest("Bilbo_B"), 0.4, 120); // expired

        reviewing.openList(elrond);

        assertEquals(Messages.noCandidates(), elrond.nextComponentMessage());
        assertNull(presenter.last(elrond, ReviewListView.class));
    }

    @Test
    void candidates_are_online_or_not_and_only_valid_votes_count() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        UUID frodo = remoteGuest("Frodo99");
        UUID sam = remoteGuest("Sam");
        vote(VOTER, bilbo.getUniqueId(), 0.2, 5);
        vote(UUID.randomUUID(), bilbo.getUniqueId(), 0.2, 120); // expired: does not count
        vote(VOTER, frodo, 0.6, 99);
        vote(VOTER, sam, 0.4, 101); // expired a day ago

        reviewing.openList(elrond);

        assertEquals(List.of("Frodo99 60 -", "Bilbo_B 20 -"), rowsOf(presenter.last(elrond, ReviewListView.class)));
    }

    @Test
    void a_click_on_a_candidate_opens_the_detail_for_the_one_who_clicked() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        PlayerMock galadriel = admin("Galadriel");
        UUID bilbo = remoteGuest("Bilbo_B");
        vote(VOTER, bilbo, 0.4, 1);
        reviewing.openList(elrond);
        ReviewListView list = presenter.last(elrond, ReviewListView.class);

        list.rows().get(0).onClick().accept(galadriel);

        assertEquals("Bilbo_B", detailOf(galadriel).name());
        assertNull(presenter.last(elrond, ReviewDetailView.class), "only the clicker gets the dialog");
    }

    @Test
    void a_group_in_promotableGroups_does_not_block() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        UUID bilbo = remoteGuest("Bilbo_B");
        UUID frodo = remoteGuest("Frodo99");
        ranks.setGroups(bilbo, "default", "badge_moderator");
        ranks.setGroups(frodo, "default", "artist", "badge_moderator");
        vote(VOTER, bilbo, 0.4, 1);
        vote(VOTER, frodo, 0.2, 1);
        setting("promotableGroups", "badge_moderator");

        reviewing.openList(elrond);

        assertEquals(List.of("Bilbo_B 40 -", "Frodo99 20 artist"),
                rowsOf(presenter.last(elrond, ReviewListView.class)));
    }

    @Test
    void a_blocked_candidate_at_the_top_shows_100_percent() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        UUID bilbo = remoteGuest("Bilbo_B");
        ranks.setGroups(bilbo, "default", "artist");
        vote(VOTER, bilbo, 0.99995, 1);

        reviewing.openList(elrond);

        assertEquals(List.of("Bilbo_B 100 artist"), rowsOf(presenter.last(elrond, ReviewListView.class)));
    }

    @Test
    void names_follow_the_server_then_luckperms_then_the_uuid() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        ranks.add(bilbo.getUniqueId(), "bilbo_b"); // LuckPerms keeps names in lowercase
        UUID frodo = remoteGuest("frodo99");
        cachedNames.put(frodo, "Frodo99"); // was on this server once
        UUID gimli = remoteGuest("gimli");
        UUID stranger = UUID.randomUUID(); // nobody can name them
        vote(VOTER, bilbo.getUniqueId(), 0.6, 1);
        vote(VOTER, frodo, 0.5, 1);
        vote(VOTER, gimli, 0.4, 1);
        vote(VOTER, stranger, 0.2, 1);

        reviewing.openList(elrond);

        assertEquals(List.of("Bilbo_B 60 -", "Frodo99 50 -", "gimli 40 -", stranger + " 20 -"),
                rowsOf(presenter.last(elrond, ReviewListView.class)));
        assertEquals(List.of("Bilbo_B", "Frodo99", "gimli", stranger.toString()), reviewing.candidateNames().join());
    }

    @Test
    void a_candidate_shown_by_uuid_can_be_typed_back() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        ConsoleCommandSenderMock console = server.getConsoleSender();
        UUID stranger = UUID.randomUUID(); // nobody can name them, so the list and the suggestions show the UUID
        vote(VOTER, stranger, 0.2, 1);
        String suggested = reviewing.candidateNames().join().get(0);

        reviewing.openDetail(elrond, suggested);
        reviewing.promote(console, suggested.toUpperCase(Locale.ROOT));

        assertEquals(stranger.toString(), detailOf(elrond).name());
        assertEquals(20, detailOf(elrond).percent());
        assertEquals(List.of(promotionCommand(stranger)), commands.commands());
    }

    @Test
    void the_list_shows_50_candidates_at_most() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        for (int i = 0; i < 51; i++) {
            vote(VOTER, remoteGuest(String.format("C%02d", i)), 0.1 + i * 0.01, 1);
        }

        reviewing.openList(elrond);

        List<String> rows = rowsOf(presenter.last(elrond, ReviewListView.class));
        assertEquals(50, rows.size());
        assertEquals("C50 60 -", rows.get(0));
        assertEquals("C01 11 -", rows.get(49), "C00, the farthest from 100%, is left out");
        assertEquals(50, reviewing.candidateNames().join().size());
    }

    // ---- the votes for one candidate ----

    @Test
    void detail_lines() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        UUID voter1 = remoteGuest("voter1");
        UUID voter2 = remoteGuest("voter2");
        vote(voter2, bilbo.getUniqueId(), 0.2, 30);
        vote(voter1, bilbo.getUniqueId(), 0.4, 12, "nice");
        vote(UUID.randomUUID(), bilbo.getUniqueId(), 0.2, 120); // expired: not shown

        reviewing.openDetail(elrond, bilbo.getUniqueId(), "Bilbo_B");

        ReviewDetailView view = detailOf(elrond);
        assertEquals("Bilbo_B", view.name());
        assertEquals(60, view.percent());
        assertEquals(List.of(new Line("voter1", true, 12, 88, "nice"), new Line("voter2", false, 30, 70, "")),
                view.votes(), "newest first");
        assertTrue(view.onPromote().isPresent());
        assertTrue(view.onClear().isPresent());
        assertNull(elrond.nextComponentMessage());
    }

    @Test
    void detail_by_typed_name_in_any_case() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        UUID bilbo = remoteGuest("Bilbo_B");
        vote(VOTER, bilbo, 0.2, 3);

        reviewing.openDetail(elrond, "bilbo_b");

        assertEquals("Bilbo_B", detailOf(elrond).name());
        assertEquals(20, detailOf(elrond).percent());
    }

    @Test
    void detail_of_an_unknown_name() {
        PlayerMock elrond = admin("Elrond");

        reviewing.openDetail(elrond, "nobody");

        assertEquals(Messages.unknownPlayer("nobody"), elrond.nextComponentMessage());
        assertNull(presenter.last(elrond, ReviewDetailView.class));
    }

    @Test
    void the_detail_uses_the_stored_settings() throws SQLException {
        setting("votesNeeded", "10");
        setting("staffWeight", "3");
        setting("validityDays", "30");
        PlayerMock elrond = admin("Elrond");
        UUID bilbo = remoteGuest("Bilbo_B");
        vote(remoteGuest("voter1"), bilbo, 0.3, 12);
        vote(remoteGuest("voter2"), bilbo, 0.1, 29);

        reviewing.openDetail(elrond, bilbo, "Bilbo_B");

        assertEquals(List.of(new Line("voter1", true, 12, 18, ""), new Line("voter2", false, 29, 1, "")),
                detailOf(elrond).votes());
        assertEquals(40, detailOf(elrond).percent());
    }

    @Test
    void the_buttons_follow_the_permissions() {
        PlayerMock viewer = withPermissions("Viewer", Permissions.REVIEW);
        PlayerMock promoter = withPermissions("Promoter", Permissions.REVIEW, Permissions.PROMOTE);
        PlayerMock clearer = withPermissions("Clearer", Permissions.REVIEW, Permissions.CLEAR);
        UUID bilbo = remoteGuest("Bilbo_B");

        reviewing.openDetail(viewer, bilbo, "Bilbo_B");
        reviewing.openDetail(promoter, bilbo, "Bilbo_B");
        reviewing.openDetail(clearer, bilbo, "Bilbo_B");

        assertEquals(List.of(false, false), List.of(detailOf(viewer).onPromote().isPresent(),
                detailOf(viewer).onClear().isPresent()));
        assertEquals(List.of(true, false), List.of(detailOf(promoter).onPromote().isPresent(),
                detailOf(promoter).onClear().isPresent()));
        assertEquals(List.of(false, true), List.of(detailOf(clearer).onPromote().isPresent(),
                detailOf(clearer).onClear().isPresent()));
    }

    @Test
    void back_returns_to_the_list_of_the_one_who_clicked() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        PlayerMock galadriel = admin("Galadriel");
        UUID bilbo = remoteGuest("Bilbo_B");
        vote(VOTER, bilbo, 0.4, 1);
        reviewing.openDetail(elrond, bilbo, "Bilbo_B");

        detailOf(elrond).onBack().accept(galadriel);

        assertEquals(List.of("Bilbo_B 40 -"), rowsOf(presenter.last(galadriel, ReviewListView.class)));
        assertNull(presenter.last(elrond, ReviewListView.class));
    }

    // ---- clearing votes ----

    @Test
    void clear_confirm_and_do() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        UUID bilbo = remoteGuest("Bilbo_B");
        vote(VOTER, bilbo, 0.4, 3);
        vote(UUID.randomUUID(), bilbo, 0.2, 5);
        reviewing.openDetail(elrond, bilbo, "Bilbo_B");

        detailOf(elrond).onClear().orElseThrow().accept(elrond);

        ConfirmView confirm = presenter.last(elrond, ConfirmView.class);
        assertNotNull(confirm);
        assertEquals("Clear votes for Bilbo_B?", confirm.title());
        assertEquals("This deletes all 2 votes for Bilbo_B. It can't be undone.", confirm.question());
        assertEquals("Clear votes", confirm.confirmLabel());
        assertEquals(2, votes.validVotesFor(bilbo, 0L).size(), "nothing is deleted before the confirmation");
        assertEquals(List.of(), messenger.notifications());

        confirm.onConfirm().accept(elrond);

        assertEquals(Messages.votesCleared("Bilbo_B", 2), elrond.nextComponentMessage());
        assertEquals(List.of(), votes.validVotesFor(bilbo, 0L));
        assertEquals(List.of(new Notification("Bilbo_B", Update.UPDATE)), messenger.notifications());
        assertNull(elrond.nextComponentMessage());
    }

    @Test
    void the_confirmation_counts_every_row_that_is_deleted_the_expired_ones_too() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        UUID bilbo = remoteGuest("Bilbo_B");
        vote(VOTER, bilbo, 0.4, 3);
        vote(UUID.randomUUID(), bilbo, 0.2, 200);

        reviewing.confirmClear(elrond, bilbo, "Bilbo_B");

        ConfirmView confirm = presenter.last(elrond, ConfirmView.class);
        assertEquals("This deletes all 2 votes for Bilbo_B. It can't be undone.", confirm.question());
        confirm.onConfirm().accept(elrond);
        assertEquals(Messages.votesCleared("Bilbo_B", 2), elrond.nextComponentMessage());
        assertEquals(List.of(), votes.validVotesFor(bilbo, 0L));
    }

    @Test
    void clearing_the_votes_of_somebody_online_here_empties_their_bar_and_sends_nothing() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        vote(VOTER, bilbo.getUniqueId(), 0.6, 2);
        bilbo.setExp(0.6f);

        reviewing.clear(elrond, bilbo.getUniqueId(), "Bilbo_B");

        assertEquals(Messages.votesCleared("Bilbo_B", 1), elrond.nextComponentMessage());
        assertEquals(0.0f, bilbo.getExp());
        assertEquals(List.of(), messenger.notifications());
        assertNull(bilbo.nextComponentMessage(), "the guest is not told in words");
    }

    @Test
    void clearing_leaves_other_candidates_alone() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        UUID bilbo = remoteGuest("Bilbo_B");
        UUID frodo = remoteGuest("Frodo99");
        vote(VOTER, bilbo, 0.4, 2);
        vote(VOTER, frodo, 0.4, 2);

        reviewing.clear(elrond, bilbo, "Bilbo_B");

        assertEquals(1, votes.validVotesFor(frodo, 0L).size());
    }

    @Test
    void clear_by_typed_name() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        UUID bilbo = remoteGuest("Bilbo_B");
        vote(VOTER, bilbo, 0.4, 2);

        reviewing.clear(elrond, "bilbo_b");
        reviewing.clear(elrond, "nobody");

        assertEquals(Messages.votesCleared("Bilbo_B", 1), elrond.nextComponentMessage());
        assertEquals(Messages.unknownPlayer("nobody"), elrond.nextComponentMessage());
        assertEquals(List.of(), votes.validVotesFor(bilbo, 0L));
    }

    @Test
    void typed_name_confirm() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        UUID bilbo = remoteGuest("Bilbo_B");
        vote(VOTER, bilbo, 0.4, 2);
        reviewing.confirmClear(elrond, bilbo, "Bilbo_B");
        ConfirmView byId = presenter.last(elrond, ConfirmView.class);
        presenter.clear();

        reviewing.confirmClear(elrond, "bilbo_b");

        ConfirmView byName = presenter.last(elrond, ConfirmView.class);
        assertNotNull(byName);
        assertEquals(byId.title(), byName.title());
        assertEquals(byId.question(), byName.question());
        assertEquals(byId.confirmLabel(), byName.confirmLabel());

        presenter.clear();
        reviewing.confirmClear(elrond, "bilbo_b2");
        reviewing.confirmPromote(elrond, "bilbo_b2");
        assertEquals(Messages.unknownPlayer("bilbo_b2"), elrond.nextComponentMessage());
        assertEquals(Messages.unknownPlayer("bilbo_b2"), elrond.nextComponentMessage());
        assertNull(presenter.last(elrond, ConfirmView.class));
    }

    @Test
    void the_console_clears_without_a_confirmation() throws SQLException {
        ConsoleCommandSenderMock console = server.getConsoleSender();
        UUID bilbo = remoteGuest("Bilbo_B");
        vote(VOTER, bilbo, 0.4, 2);
        vote(UUID.randomUUID(), bilbo, 0.2, 2);

        reviewing.clear(console, "Bilbo_B");

        assertEquals("[Vote] Cleared 2 votes for Bilbo_B.", Texts.plain(console.nextComponentMessage()));
        assertEquals(List.of(), votes.validVotesFor(bilbo, 0L));
        assertEquals(List.of(new Notification("Bilbo_B", Update.UPDATE)), messenger.notifications());
    }

    // ---- promoting by hand ----

    @Test
    void promote_confirm() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        UUID bilbo = remoteGuest("Bilbo_B");
        vote(VOTER, bilbo, 0.6, 2);
        reviewing.openDetail(elrond, bilbo, "Bilbo_B");

        detailOf(elrond).onPromote().orElseThrow().accept(elrond);

        ConfirmView confirm = presenter.last(elrond, ConfirmView.class);
        assertNotNull(confirm);
        assertEquals("Promote Bilbo_B now?", confirm.title());
        assertEquals("Bilbo_B becomes Commoner through LuckPerms, as if they had reached 100%.", confirm.question());
        assertEquals("Promote", confirm.confirmLabel());
        assertEquals(List.of(), commands.commands(), "nothing happens before the confirmation");

        confirm.onConfirm().accept(elrond);

        assertEquals(List.of(promotionCommand(bilbo)), commands.commands());
        assertEquals(Messages.promoted("Bilbo_B"), elrond.nextComponentMessage());
        assertEquals(Source.MANUAL, promotionOf(bilbo).orElseThrow().source());
    }

    @Test
    void promote_is_refused_for_a_blocking_group() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        UUID bilbo = remoteGuest("Bilbo_B");
        ranks.setGroups(bilbo, "default", "badge_moderator");

        reviewing.confirmPromote(elrond, bilbo, "Bilbo_B");
        presenter.last(elrond, ConfirmView.class).onConfirm().accept(elrond);

        assertEquals(Messages.promoteBlocked("Bilbo_B", "badge_moderator"), elrond.nextComponentMessage());
        assertEquals(List.of(), commands.commands());
        assertEquals(Optional.empty(), promotionOf(bilbo));
    }

    @Test
    void promote_by_typed_name() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");

        reviewing.confirmPromote(elrond, "BILBO_B");
        assertEquals("Promote Bilbo_B now?", presenter.last(elrond, ConfirmView.class).title());
        presenter.last(elrond, ConfirmView.class).onConfirm().accept(elrond);

        assertEquals(List.of(promotionCommand(bilbo.getUniqueId())), commands.commands());
        assertEquals(Messages.promoted("Bilbo_B"), elrond.nextComponentMessage());
        assertEquals(Source.MANUAL, promotionOf(bilbo.getUniqueId()).orElseThrow().source());
    }

    @Test
    void the_console_promotes_without_a_confirmation() {
        ConsoleCommandSenderMock console = server.getConsoleSender();
        UUID bilbo = remoteGuest("Bilbo_B");

        reviewing.promote(console, "bilbo_b");
        reviewing.promote(console, "nobody");

        assertEquals(List.of(promotionCommand(bilbo)), commands.commands());
        assertEquals("[Vote] Bilbo_B is now a Commoner.", Texts.plain(console.nextComponentMessage()));
        assertEquals("[Vote] No player called nobody has been on MCME.", Texts.plain(console.nextComponentMessage()));
    }

    @Test
    void the_command_names_an_offline_target_by_uuid() {
        // Renamed since: this server's profile cache still has the old name, LuckPerms knows the new one.
        ConsoleCommandSenderMock console = server.getConsoleSender();
        UUID renamed = UUID.randomUUID();
        ranks.add(renamed, "newname");
        cachedNames.put(renamed, "OldName");

        reviewing.promote(console, "newname");

        assertEquals(List.of("lp user " + renamed + " parent set commoner"), commands.commands());
        String command = commands.commands().get(0);
        assertFalse(command.contains("OldName") || command.contains("newname"), command);
        assertEquals("[Vote] OldName is now a Commoner.", Texts.plain(console.nextComponentMessage()),
                "the messages keep the name shown");
    }

    // ---- permissions ----

    @Test
    void permission_rechecked() throws SQLException {
        PlayerMock elrond = withPermissions("Elrond", Permissions.REVIEW);
        PermissionAttachment clearRight = elrond.addAttachment(plugin, Permissions.CLEAR, true);
        PermissionAttachment promoteRight = elrond.addAttachment(plugin, Permissions.PROMOTE, true);
        UUID bilbo = remoteGuest("Bilbo_B");
        vote(VOTER, bilbo, 0.4, 1);
        reviewing.openDetail(elrond, bilbo, "Bilbo_B");
        detailOf(elrond).onClear().orElseThrow().accept(elrond);
        ConfirmView clearConfirm = presenter.last(elrond, ConfirmView.class);
        detailOf(elrond).onPromote().orElseThrow().accept(elrond);
        ConfirmView promoteConfirm = presenter.last(elrond, ConfirmView.class);
        assertEquals("Promote Bilbo_B now?", promoteConfirm.title());
        clearRight.unsetPermission(Permissions.CLEAR);
        promoteRight.unsetPermission(Permissions.PROMOTE);

        clearConfirm.onConfirm().accept(elrond);
        promoteConfirm.onConfirm().accept(elrond);

        assertEquals(Messages.noPermission(), elrond.nextComponentMessage());
        assertEquals(Messages.noPermission(), elrond.nextComponentMessage());
        assertNull(elrond.nextComponentMessage());
        assertEquals(1, votes.validVotesFor(bilbo, 0L).size(), "the votes stay");
        assertEquals(List.of(), commands.commands());
        assertEquals(List.of(), messenger.notifications());
    }

    @Test
    void every_entry_point_checks_the_permission() throws SQLException {
        PlayerMock nobody = guest("Nobody");
        PlayerMock bilbo = guest("Bilbo_B");
        vote(VOTER, bilbo.getUniqueId(), 0.4, 1);
        UUID id = bilbo.getUniqueId();
        List<Runnable> actions = List.of(
                () -> reviewing.openList(nobody),
                () -> reviewing.openDetail(nobody, "Bilbo_B"),
                () -> reviewing.openDetail(nobody, id, "Bilbo_B"),
                () -> reviewing.confirmClear(nobody, "Bilbo_B"),
                () -> reviewing.confirmClear(nobody, id, "Bilbo_B"),
                () -> reviewing.clear(nobody, "Bilbo_B"),
                () -> reviewing.clear(nobody, id, "Bilbo_B"),
                () -> reviewing.confirmPromote(nobody, "Bilbo_B"),
                () -> reviewing.confirmPromote(nobody, id, "Bilbo_B"),
                () -> reviewing.promote(nobody, "Bilbo_B"),
                () -> reviewing.promote(nobody, id, "Bilbo_B"),
                () -> reviewing.printReview(nobody, Optional.empty()),
                () -> reviewing.printReview(nobody, Optional.of("Bilbo_B")));

        for (Runnable action : actions) {
            action.run();
            assertEquals(Messages.noPermission(), nobody.nextComponentMessage());
        }

        assertNull(nobody.nextComponentMessage());
        assertNull(presenter.last(nobody, ReviewListView.class));
        assertNull(presenter.last(nobody, ReviewDetailView.class));
        assertNull(presenter.last(nobody, ConfirmView.class));
        assertEquals(1, votes.validVotesFor(id, 0L).size());
        assertEquals(List.of(), commands.commands());
    }

    @Test
    void the_console_has_every_permission() throws SQLException {
        ConsoleCommandSenderMock console = server.getConsoleSender();
        UUID bilbo = remoteGuest("Bilbo_B");
        vote(VOTER, bilbo, 0.4, 1);

        reviewing.printReview(console, Optional.empty());
        reviewing.clear(console, "Bilbo_B");

        assertEquals("- Bilbo_B: 40%", Texts.plain(console.nextComponentMessage()));
        assertEquals("[Vote] Cleared 1 votes for Bilbo_B.", Texts.plain(console.nextComponentMessage()));
    }

    // ---- console text ----

    @Test
    void console_review() throws SQLException {
        ConsoleCommandSenderMock console = server.getConsoleSender();
        UUID bilbo = remoteGuest("Bilbo_B");
        UUID frodo = remoteGuest("Frodo99");
        ranks.setGroups(bilbo, "default", "badge_moderator");
        vote(VOTER, bilbo, 0.8, 1);
        vote(VOTER, frodo, 0.4, 1);

        reviewing.printReview(console, Optional.empty());

        assertEquals("- Bilbo_B: 80% (blocked: badge_moderator)", Texts.plain(console.nextComponentMessage()));
        assertEquals("- Frodo99: 40%", Texts.plain(console.nextComponentMessage()));
        assertNull(console.nextComponentMessage());
    }

    @Test
    void console_review_without_candidates() {
        ConsoleCommandSenderMock console = server.getConsoleSender();

        reviewing.printReview(console, Optional.empty());

        assertEquals("[Vote] Nobody has valid votes right now.", Texts.plain(console.nextComponentMessage()));
    }

    @Test
    void console_review_of_one_player() throws SQLException {
        ConsoleCommandSenderMock console = server.getConsoleSender();
        UUID bilbo = remoteGuest("Bilbo_B");
        UUID voter1 = remoteGuest("voter1");
        UUID voter2 = remoteGuest("voter2");
        vote(voter2, bilbo, 0.2, 30);
        vote(voter1, bilbo, 0.4, 12, "nice");
        vote(UUID.randomUUID(), bilbo, 0.2, 120); // expired

        reviewing.printReview(console, Optional.of("bilbo_b"));

        assertEquals("- voter1 (staff): 12 days ago, ends in 88 days: \"nice\"",
                Texts.plain(console.nextComponentMessage()));
        assertEquals("- voter2: 30 days ago, ends in 70 days", Texts.plain(console.nextComponentMessage()));
        assertNull(console.nextComponentMessage());
    }

    @Test
    void console_review_of_a_player_without_votes_and_of_nobody() {
        ConsoleCommandSenderMock console = server.getConsoleSender();
        remoteGuest("Bilbo_B");

        reviewing.printReview(console, Optional.of("Bilbo_B"));
        reviewing.printReview(console, Optional.of("nobody"));

        assertEquals("[Vote] Bilbo_B has 0% of the votes needed.", Texts.plain(console.nextComponentMessage()));
        assertEquals("[Vote] No player called nobody has been on MCME.", Texts.plain(console.nextComponentMessage()));
        assertNull(console.nextComponentMessage());
    }

    // ---- tab completion ----

    @Test
    void candidate_names() throws SQLException {
        UUID frodo = remoteGuest("Frodo99");
        UUID bilbo = remoteGuest("Bilbo_B");
        UUID sam = remoteGuest("Sam");
        vote(VOTER, frodo, 0.4, 1);
        vote(VOTER, bilbo, 0.8, 1);
        vote(VOTER, sam, 0.6, 1);
        recordPromotion(sam, Source.MANUAL, true);

        assertEquals(List.of("Bilbo_B", "Frodo99"), reviewing.candidateNames().join());
    }

    @Test
    void candidate_names_never_fail() throws SQLException {
        remoteGuest("Bilbo_B");
        breakDatabase();

        CompletableFuture<List<String>> names = reviewing.candidateNames();

        assertFalse(names.isCompletedExceptionally());
        assertEquals(List.of(), names.join());
    }

    // ---- failures ----

    @Test
    void database_down() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        ConsoleCommandSenderMock console = server.getConsoleSender();
        breakDatabase();
        presenter.clear();
        UUID id = bilbo.getUniqueId();
        List<Runnable> actions = List.of(
                () -> reviewing.openList(elrond),
                () -> reviewing.openDetail(elrond, "Bilbo_B"),
                () -> reviewing.openDetail(elrond, id, "Bilbo_B"),
                () -> reviewing.confirmClear(elrond, "Bilbo_B"),
                () -> reviewing.confirmClear(elrond, id, "Bilbo_B"),
                () -> reviewing.clear(elrond, "Bilbo_B"),
                () -> reviewing.clear(elrond, id, "Bilbo_B"));

        for (Runnable action : actions) {
            assertDoesNotThrow(action::run);
            assertEquals(Messages.databaseDown(), elrond.nextComponentMessage());
        }
        assertDoesNotThrow(() -> reviewing.printReview(console, Optional.empty()));
        assertEquals("[Vote] The vote database is unreachable. Try again in a minute.",
                Texts.plain(console.nextComponentMessage()));
        assertDoesNotThrow(() -> reviewing.printReview(console, Optional.of("Bilbo_B")));
        assertEquals("[Vote] The vote database is unreachable. Try again in a minute.",
                Texts.plain(console.nextComponentMessage()));

        assertNull(elrond.nextComponentMessage());
        assertNull(console.nextComponentMessage());
        assertNull(presenter.last(elrond, ReviewListView.class));
        assertNull(presenter.last(elrond, ReviewDetailView.class));
        assertNull(presenter.last(elrond, ConfirmView.class));
        assertEquals(List.of(), messenger.notifications());
    }

    @Test
    void a_luckperms_failure_is_the_same_as_a_database_failure() throws SQLException {
        RankService broken = mock(RankService.class);
        when(broken.lookup(any())).thenReturn(failed());
        when(broken.names(any())).thenReturn(failed());
        when(broken.directGroups(any())).thenReturn(failed());
        when(broken.isExempt(any())).thenReturn(failed());
        ReviewService service = new ReviewService(db, main, votes, settings, broken, profiles, messenger, presenter,
                xp, promotion, () -> now);
        PlayerMock elrond = admin("Elrond");
        ConsoleCommandSenderMock console = server.getConsoleSender();
        vote(VOTER, remoteGuest("Bilbo_B"), 0.4, 1);
        presenter.clear();

        assertDoesNotThrow(() -> service.openList(elrond));
        assertEquals(Messages.databaseDown(), elrond.nextComponentMessage());
        assertDoesNotThrow(() -> service.openDetail(elrond, "gimli"));
        assertEquals(Messages.databaseDown(), elrond.nextComponentMessage());
        assertDoesNotThrow(() -> service.confirmClear(elrond, "gimli"));
        assertEquals(Messages.databaseDown(), elrond.nextComponentMessage());
        assertDoesNotThrow(() -> service.confirmPromote(elrond, "gimli"));
        assertEquals(Messages.databaseDown(), elrond.nextComponentMessage());
        assertDoesNotThrow(() -> service.printReview(console, Optional.empty()));
        assertEquals("[Vote] The vote database is unreachable. Try again in a minute.",
                Texts.plain(console.nextComponentMessage()));
        assertEquals(List.of(), service.candidateNames().join());

        assertNull(elrond.nextComponentMessage());
        assertNull(presenter.last(elrond, ReviewListView.class));
        assertNull(presenter.last(elrond, ConfirmView.class));
        assertEquals(List.of(), commands.commands());
    }

    // ---- an admin who quits while the work is under way ----

    @Test
    void quit_before_continuation() throws SQLException {
        QueueExecutor queue = new QueueExecutor();
        main = queue;
        build();
        PlayerMock elrond = admin("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        vote(VOTER, bilbo.getUniqueId(), 0.4, 2);
        presenter.clear();

        reviewing.openList(elrond);
        reviewing.openDetail(elrond, "Bilbo_B");
        reviewing.openDetail(elrond, bilbo.getUniqueId(), "Bilbo_B");
        reviewing.confirmClear(elrond, bilbo.getUniqueId(), "Bilbo_B");
        reviewing.confirmClear(elrond, "bilbo_b");
        reviewing.confirmPromote(elrond, "bilbo_b");
        elrond.disconnect();

        assertDoesNotThrow(queue::runAll);
        assertNull(presenter.last(elrond, ReviewListView.class));
        assertNull(presenter.last(elrond, ReviewDetailView.class));
        assertNull(elrond.nextComponentMessage());
    }

    @Test
    void a_database_failure_is_not_told_to_an_admin_who_left() throws SQLException {
        QueueExecutor queue = new QueueExecutor();
        main = queue;
        connections = () -> {
            throw new SQLException("Communications link failure");
        };
        build();
        PlayerMock elrond = admin("Elrond");

        reviewing.openList(elrond);
        reviewing.clear(elrond, UUID.randomUUID(), "Bilbo_B");
        elrond.disconnect();

        assertDoesNotThrow(queue::runAll);
        assertNull(elrond.nextComponentMessage());
        assertEquals(List.of(), messenger.notifications());
    }

    @Test
    void cleared_votes_still_reach_the_recipient_when_the_admin_quit() throws SQLException {
        QueueExecutor queue = new QueueExecutor();
        main = queue;
        build();
        PlayerMock elrond = admin("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        UUID frodo = remoteGuest("Frodo99");
        vote(VOTER, bilbo.getUniqueId(), 0.6, 2);
        vote(VOTER, frodo, 0.4, 2);
        bilbo.setExp(0.6f);

        reviewing.clear(elrond, bilbo.getUniqueId(), "Bilbo_B");
        reviewing.clear(elrond, frodo, "Frodo99");
        elrond.disconnect();
        queue.runAll();

        assertEquals(0.0f, bilbo.getExp());
        assertEquals(List.of(new Notification("Frodo99", Update.UPDATE)), messenger.notifications());
        assertNull(elrond.nextComponentMessage());
    }

    @Test
    void a_recipient_who_quit_before_the_votes_were_cleared_is_notified_through_the_messenger() throws SQLException {
        QueueExecutor queue = new QueueExecutor();
        main = queue;
        build();
        PlayerMock elrond = admin("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        vote(VOTER, bilbo.getUniqueId(), 0.6, 2);

        reviewing.clear(elrond, bilbo.getUniqueId(), "Bilbo_B");
        bilbo.disconnect();
        queue.runAll();

        assertEquals(Messages.votesCleared("Bilbo_B", 1), elrond.nextComponentMessage());
        assertEquals(List.of(new Notification("Bilbo_B", Update.UPDATE)), messenger.notifications());
    }
}
