package com.mcmiddleearth.commonervote.service;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.OfflinePlayerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.mcmiddleearth.commonervote.Permissions;
import com.mcmiddleearth.commonervote.domain.Vote;
import com.mcmiddleearth.commonervote.network.Messenger.Update;
import com.mcmiddleearth.commonervote.rank.RankService;
import com.mcmiddleearth.commonervote.support.QueueExecutor;
import com.mcmiddleearth.commonervote.support.RecordingMessenger.Notification;
import com.mcmiddleearth.commonervote.support.ServiceTestBase;
import com.mcmiddleearth.commonervote.ui.Texts;
import com.mcmiddleearth.commonervote.ui.view.CandidateListView;
import com.mcmiddleearth.commonervote.ui.view.GuestInfoView;
import com.mcmiddleearth.commonervote.ui.view.VoteView;
import com.mcmiddleearth.commonervote.ui.view.WelcomeView;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VoteServiceTest extends ServiceTestBase {

    /** Somebody else, who gave the votes that a test seeds. */
    private static final UUID OTHER_VOTER = UUID.randomUUID();

    private static final String PROMOTE_BILBO = "lp user Bilbo_B parent set commoner";

    /** The votes stored for {@code target}, expired ones included. */
    private List<Vote> stored(Player target) throws SQLException {
        return votes.validVotesFor(target.getUniqueId(), 0L);
    }

    /** What a candidate list says, as {@code "Name percent votedByViewer"}. */
    private static List<String> rowsOf(CandidateListView view) {
        return view.rows().stream().map(r -> r.name() + " " + r.percent() + " " + r.votedByViewer()).toList();
    }

    /** Opens the vote dialog for {@code target} and returns it. */
    private VoteView dialogFor(Player voter, Player target) {
        voting.openVote(voter, target.getUniqueId(), target.getName());
        VoteView view = presenter.last(voter, VoteView.class);
        assertNotNull(view, "the vote dialog should be open");
        return view;
    }

    // ---- /vote with no argument ----

    @Test
    void menu_for_team() throws SQLException {
        PlayerMock elrond = teamMember("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        PlayerMock frodo = guest("Frodo99");
        teamMember("Gandalf");
        exempt("Aragorn");
        vote(OTHER_VOTER, bilbo.getUniqueId(), 0.8, 1);
        vote(elrond.getUniqueId(), frodo.getUniqueId(), 0.4, 3);

        voting.openMenu(elrond);

        CandidateListView view = presenter.last(elrond, CandidateListView.class);
        assertNotNull(view);
        assertEquals(List.of("Bilbo_B 80 false", "Frodo99 40 true"), rowsOf(view),
                "the viewer, the team member and the exempt player are not candidates");
        assertEquals(bilbo.getUniqueId(), view.rows().get(0).player());
        assertEquals(frodo.getUniqueId(), view.rows().get(1).player());
        assertNull(elrond.nextComponentMessage());
    }

    @Test
    void the_menu_lists_guests_without_votes_and_breaks_ties_by_name() {
        PlayerMock elrond = teamMember("Elrond");
        guest("Zed");
        guest("amy");
        guest("Bob");

        voting.openMenu(elrond);

        assertEquals(List.of("amy 0 false", "Bob 0 false", "Zed 0 false"),
                rowsOf(presenter.last(elrond, CandidateListView.class)));
    }

    @Test
    void only_valid_votes_count_in_the_menu() throws SQLException {
        PlayerMock elrond = teamMember("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        vote(elrond.getUniqueId(), bilbo.getUniqueId(), 0.2, 120); // expired after 100 days
        vote(OTHER_VOTER, bilbo.getUniqueId(), 0.2, 5);

        voting.openMenu(elrond);

        assertEquals(List.of("Bilbo_B 20 false"), rowsOf(presenter.last(elrond, CandidateListView.class)));
    }

    @Test
    void a_click_on_a_candidate_opens_the_vote_dialog_for_the_one_who_clicked() {
        PlayerMock elrond = teamMember("Elrond");
        PlayerMock galadriel = teamMember("Galadriel");
        guest("Bilbo_B");
        voting.openMenu(elrond);
        CandidateListView list = presenter.last(elrond, CandidateListView.class);

        list.rows().get(0).onClick().accept(galadriel);

        assertEquals("Bilbo_B", presenter.last(galadriel, VoteView.class).name());
        assertNull(presenter.last(elrond, VoteView.class), "only the clicker gets the dialog");
    }

    @Test
    void a_team_member_who_is_also_exempt_gets_the_list() {
        PlayerMock elrond = teamMember("Elrond");
        elrond.addAttachment(plugin, Permissions.EXEMPT, true);
        guest("Bilbo_B");

        voting.openMenu(elrond);

        assertEquals(List.of("Bilbo_B 0 false"), rowsOf(presenter.last(elrond, CandidateListView.class)));
    }

    @Test
    void menu_no_guests() {
        PlayerMock elrond = teamMember("Elrond");
        teamMember("Gandalf");
        exempt("Aragorn");
        presenter.clear();

        voting.openMenu(elrond);

        assertEquals(Messages.noGuestsOnline(), elrond.nextComponentMessage());
        assertNull(presenter.last(elrond, CandidateListView.class));
    }

    @Test
    void menu_for_guest() throws SQLException {
        PlayerMock bilbo = guest("Bilbo_B");
        vote(OTHER_VOTER, bilbo.getUniqueId(), 0.6, 1);

        voting.openMenu(bilbo);

        GuestInfoView view = presenter.last(bilbo, GuestInfoView.class);
        assertNotNull(view);
        assertEquals(60, view.percent());
        assertEquals(Texts.plain(Texts.mini(settings.get().guestInfoText(),
                Texts.placeholders(settings.get(), "Bilbo_B"))), Texts.plain(view.infoText()));
        assertEquals("", view.guideUrl());
        assertNull(bilbo.nextComponentMessage());
    }

    @Test
    void the_guest_dialog_carries_the_guide_link() throws SQLException {
        setting("guideUrl", "https://example.org/guide");
        PlayerMock bilbo = guest("Bilbo_B");

        voting.showOwnProgress(bilbo);

        GuestInfoView view = presenter.last(bilbo, GuestInfoView.class);
        assertEquals(0, view.percent());
        assertEquals("https://example.org/guide", view.guideUrl());
    }

    @Test
    void the_guest_dialog_counts_valid_votes_only() throws SQLException {
        PlayerMock bilbo = guest("Bilbo_B");
        vote(OTHER_VOTER, bilbo.getUniqueId(), 0.4, 30);
        vote(UUID.randomUUID(), bilbo.getUniqueId(), 0.4, 101);

        voting.showOwnProgress(bilbo);

        assertEquals(40, presenter.last(bilbo, GuestInfoView.class).percent());
    }

    @Test
    void menu_for_commoner() {
        PlayerMock frodo = exempt("Frodo99");
        presenter.clear();

        voting.openMenu(frodo);

        assertEquals(Messages.alreadyCommoner(), frodo.nextComponentMessage());
        assertNull(presenter.last(frodo, GuestInfoView.class));
        assertNull(presenter.last(frodo, CandidateListView.class));
    }

    // ---- /vote <player> ----

    @Test
    void open_vote_case_insensitive() {
        PlayerMock elrond = teamMember("Elrond");
        guest("Bilbo_B");

        voting.openVote(elrond, "bilbo_b");

        VoteView view = presenter.last(elrond, VoteView.class);
        assertNotNull(view);
        assertEquals("Bilbo_B", view.name());
        assertNull(elrond.nextComponentMessage());
    }

    @Test
    void open_vote_offline_via_ranks() {
        PlayerMock elrond = teamMember("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        ranks.add(bilbo.getUniqueId(), "bilbo_b"); // LuckPerms keeps names in lowercase
        bilbo.disconnect();

        voting.openVote(elrond, "BILBO_B");

        VoteView view = presenter.last(elrond, VoteView.class);
        assertNotNull(view, "a player on another server is found through the rank service");
        assertEquals("Bilbo_B", view.name(), "this server's spelling wins over the LuckPerms one");
    }

    @Test
    void a_player_this_server_never_saw_shows_under_the_luckperms_name() {
        PlayerMock elrond = teamMember("Elrond");
        remoteGuest("gimli");

        voting.openVote(elrond, "Gimli");

        assertEquals("gimli", presenter.last(elrond, VoteView.class).name());
    }

    @Test
    void a_player_the_rank_service_cannot_name_shows_under_the_typed_name() {
        UUID gimli = UUID.randomUUID();
        server.getPlayerList().addOfflinePlayer(new OfflinePlayerMock(gimli, null));
        RankService unnamed = mock(RankService.class);
        when(unnamed.lookup(any())).thenReturn(CompletableFuture.completedFuture(Optional.of(gimli)));
        when(unnamed.names(any())).thenReturn(CompletableFuture.completedFuture(Map.of()));
        when(unnamed.isExempt(any())).thenReturn(CompletableFuture.completedFuture(false));
        VoteService service = new VoteService(db, main, votes, settings, unnamed, messenger, presenter, xp, promotion,
                () -> now);
        PlayerMock elrond = teamMember("Elrond");

        service.openVote(elrond, "Gimli");

        assertEquals("Gimli", presenter.last(elrond, VoteView.class).name());
    }

    @Test
    void open_vote_unknown() {
        PlayerMock elrond = teamMember("Elrond");
        presenter.clear();

        voting.openVote(elrond, "nobody");

        assertEquals(Messages.unknownPlayer("nobody"), elrond.nextComponentMessage());
        assertNull(presenter.last(elrond, VoteView.class));
    }

    @Test
    void refusals() {
        PlayerMock elrond = teamMember("Elrond");
        exempt("Frodo99");
        ranks.setExempt(remoteGuest("Sam"), true);
        presenter.clear();

        voting.openVote(elrond, "elrond");
        assertEquals(Messages.selfVote(), elrond.nextComponentMessage());
        voting.openVote(elrond, elrond.getUniqueId(), "Elrond");
        assertEquals(Messages.selfVote(), elrond.nextComponentMessage());
        voting.openVote(elrond, "frodo99");
        assertEquals(Messages.targetExempt("Frodo99"), elrond.nextComponentMessage());
        voting.openVote(elrond, "sam");
        assertEquals(Messages.targetExempt("Sam"), elrond.nextComponentMessage());

        assertNull(elrond.nextComponentMessage());
        assertNull(presenter.last(elrond, VoteView.class));
    }

    @Test
    void only_voters_open_the_vote_dialog() {
        PlayerMock bilbo = guest("Bilbo_B");
        PlayerMock frodo = guest("Frodo99");
        presenter.clear();

        voting.openVote(bilbo, "Frodo99");
        voting.openVote(bilbo, frodo.getUniqueId(), "Frodo99");

        assertEquals(Messages.noPermission(), bilbo.nextComponentMessage());
        assertEquals(Messages.noPermission(), bilbo.nextComponentMessage());
        assertNull(presenter.last(bilbo, VoteView.class));
    }

    @Test
    void vote_view_states() throws SQLException {
        PlayerMock elrond = teamMember("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        vote(OTHER_VOTER, bilbo.getUniqueId(), 0.4, 2);

        VoteView fresh = dialogFor(elrond, bilbo);

        assertEquals(OptionalInt.empty(), fresh.votedDaysAgo());
        assertEquals(Optional.empty(), fresh.onWithdraw());
        assertEquals("", fresh.reason());
        assertEquals(40, fresh.percent());
        assertEquals(2, fresh.points());
        assertEquals(5, fresh.votesNeeded());
        assertEquals(100, fresh.validityDays());

        vote(elrond.getUniqueId(), bilbo.getUniqueId(), 0.2, 12, "nice");
        VoteView voted = dialogFor(elrond, bilbo);

        assertEquals(OptionalInt.of(12), voted.votedDaysAgo());
        assertEquals("nice", voted.reason());
        assertTrue(voted.onWithdraw().isPresent());
        assertEquals(60, voted.percent());
        assertEquals(3, voted.points());
    }

    @Test
    void an_expired_vote_is_not_a_vote_in_the_dialog() throws SQLException {
        PlayerMock elrond = teamMember("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        vote(elrond.getUniqueId(), bilbo.getUniqueId(), 0.2, 150, "old");

        VoteView view = dialogFor(elrond, bilbo);

        assertEquals(OptionalInt.empty(), view.votedDaysAgo());
        assertEquals("", view.reason());
        assertEquals(Optional.empty(), view.onWithdraw());
        assertEquals(0, view.percent());
    }

    @Test
    void the_dialog_uses_the_stored_settings() throws SQLException {
        setting("votesNeeded", "10");
        setting("validityDays", "30");
        PlayerMock elrond = teamMember("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        vote(OTHER_VOTER, bilbo.getUniqueId(), 0.3, 1);

        VoteView view = dialogFor(elrond, bilbo);

        assertEquals(10, view.votesNeeded());
        assertEquals(30, view.validityDays());
        assertEquals(30, view.percent());
        assertEquals(3, view.points());
    }

    // ---- casting ----

    @Test
    void cast_flow() throws SQLException {
        PlayerMock elrond = teamMember("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        vote(OTHER_VOTER, bilbo.getUniqueId(), 0.6, 1);
        VoteView view = dialogFor(elrond, bilbo);

        view.onCast().accept(elrond, "Great work");

        List<Vote> rows = stored(bilbo);
        Vote mine = rows.stream().filter(v -> v.voter().equals(elrond.getUniqueId())).findFirst().orElseThrow();
        assertEquals("Great work", mine.reason());
        assertEquals(0.2, mine.weight(), 1e-9);
        assertEquals(NOW, mine.timestamp());
        assertEquals(Messages.voteCast("Bilbo_B", 60, 80), elrond.nextComponentMessage());
        assertNull(elrond.nextComponentMessage());
        assertEquals("A team member voted for you · 80% to Commoner", Texts.plain(bilbo.nextActionBar()));
        assertEquals(0.8f, bilbo.getExp(), 1e-6);
        assertEquals(List.of(), messenger.notifications(), "a recipient on this server needs no message");
        assertEquals(List.of(), commands.commands());
    }

    @Test
    void a_staff_vote_counts_double() throws SQLException {
        PlayerMock galadriel = staff("Galadriel");
        PlayerMock bilbo = guest("Bilbo_B");

        voting.cast(galadriel, bilbo.getUniqueId(), "Bilbo_B", "");

        assertEquals(0.4, stored(bilbo).get(0).weight(), 1e-9);
        assertEquals(Messages.voteCast("Bilbo_B", 0, 40), galadriel.nextComponentMessage());
    }

    @Test
    void cast_remote() throws SQLException {
        PlayerMock elrond = teamMember("Elrond");
        UUID bilbo = remoteGuest("Bilbo_B");
        voting.openVote(elrond, "bilbo_b");
        VoteView view = presenter.last(elrond, VoteView.class);

        view.onCast().accept(elrond, "");

        assertEquals(List.of(new Notification("Bilbo_B", Update.VOTED)), messenger.notifications());
        assertEquals(Messages.voteCast("Bilbo_B", 0, 20), elrond.nextComponentMessage());
        assertEquals(1, votes.validVotesFor(bilbo, 0L).size());
    }

    @Test
    void renew_flow() throws SQLException {
        PlayerMock elrond = teamMember("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        vote(elrond.getUniqueId(), bilbo.getUniqueId(), 0.2, 40, "first");
        VoteView view = dialogFor(elrond, bilbo);

        view.onCast().accept(elrond, "still good");

        assertEquals(Messages.voteRenewed("Bilbo_B", 100), elrond.nextComponentMessage());
        List<Vote> rows = stored(bilbo);
        assertEquals(1, rows.size(), "one row per voter and guest");
        assertEquals("still good", rows.get(0).reason());
        assertEquals(NOW, rows.get(0).timestamp(), "renewing restarts the validity");
        assertEquals("A team member voted for you · 20% to Commoner", Texts.plain(bilbo.nextActionBar()));
    }

    @Test
    void a_second_cast_through_the_dialog_renews() {
        PlayerMock elrond = teamMember("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        VoteView view = dialogFor(elrond, bilbo);

        view.onCast().accept(elrond, "");
        view.onCast().accept(elrond, "");

        assertEquals(Messages.voteCast("Bilbo_B", 0, 20), elrond.nextComponentMessage());
        assertEquals(Messages.voteRenewed("Bilbo_B", 100), elrond.nextComponentMessage());
    }

    @Test
    void the_renew_line_names_the_stored_validity() throws SQLException {
        setting("validityDays", "30");
        PlayerMock elrond = teamMember("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        vote(elrond.getUniqueId(), bilbo.getUniqueId(), 0.2, 3);

        voting.cast(elrond, bilbo.getUniqueId(), "Bilbo_B", "");

        assertEquals(Messages.voteRenewed("Bilbo_B", 30), elrond.nextComponentMessage());
    }

    @Test
    void cast_promotes() throws SQLException {
        PlayerMock elrond = teamMember("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        vote(OTHER_VOTER, bilbo.getUniqueId(), 0.8, 1);

        voting.cast(elrond, bilbo.getUniqueId(), "Bilbo_B", "");

        assertEquals(List.of(PROMOTE_BILBO), commands.commands());
        assertNotNull(presenter.last(bilbo, WelcomeView.class));
        assertEquals(Messages.voteCast("Bilbo_B", 80, 100), elrond.nextComponentMessage());
    }

    @Test
    void cast_requires_permission() throws SQLException {
        PlayerMock elrond = teamMember("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        VoteView view = dialogFor(elrond, bilbo);
        elrond.addAttachment(plugin, Permissions.VOTE, false); // the voter lost the permission meanwhile
        assertFalse(elrond.hasPermission(Permissions.VOTE));

        view.onCast().accept(elrond, "too late");

        assertEquals(Messages.noPermission(), elrond.nextComponentMessage());
        assertEquals(List.of(), stored(bilbo));
        assertNull(bilbo.nextActionBar());
        assertEquals(List.of(), messenger.notifications());
    }

    @Test
    void a_cast_for_somebody_who_became_a_commoner_is_refused() throws SQLException {
        PlayerMock elrond = teamMember("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        VoteView view = dialogFor(elrond, bilbo);
        bilbo.addAttachment(plugin, Permissions.EXEMPT, true); // promoted while the dialog was open

        view.onCast().accept(elrond, "");

        assertEquals(Messages.targetExempt("Bilbo_B"), elrond.nextComponentMessage());
        assertEquals(List.of(), stored(bilbo));
        assertNull(bilbo.nextActionBar());
    }

    @Test
    void a_cast_for_an_offline_commoner_is_refused() throws SQLException {
        PlayerMock elrond = teamMember("Elrond");
        UUID sam = remoteGuest("Sam");
        ranks.setExempt(sam, true);

        voting.cast(elrond, sam, "Sam", "");

        assertEquals(Messages.targetExempt("Sam"), elrond.nextComponentMessage());
        assertEquals(List.of(), votes.validVotesFor(sam, 0L));
        assertEquals(List.of(), messenger.notifications());
    }

    @Test
    void nobody_votes_for_themselves() throws SQLException {
        PlayerMock elrond = teamMember("Elrond");

        voting.cast(elrond, elrond.getUniqueId(), "Elrond", "");

        assertEquals(Messages.selfVote(), elrond.nextComponentMessage());
        assertEquals(List.of(), stored(elrond));
    }

    // ---- withdrawing ----

    @Test
    void withdraw_flow() throws SQLException {
        PlayerMock elrond = teamMember("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        vote(elrond.getUniqueId(), bilbo.getUniqueId(), 0.2, 4);
        vote(OTHER_VOTER, bilbo.getUniqueId(), 0.4, 4);
        bilbo.setExp(0.6f);
        VoteView view = dialogFor(elrond, bilbo);

        view.onWithdraw().orElseThrow().accept(elrond);

        assertEquals(Messages.withdrawn("Bilbo_B"), elrond.nextComponentMessage());
        assertEquals(1, stored(bilbo).size());
        assertEquals(0.4f, bilbo.getExp(), 1e-6, "the guest's bar follows");
        assertNull(bilbo.nextActionBar(), "a withdrawal is silent");
        assertEquals(List.of(), messenger.notifications(), "a recipient on this server needs no message");

        voting.withdraw(elrond, bilbo.getUniqueId(), "Bilbo_B");

        assertEquals(Messages.noVoteToWithdraw("Bilbo_B"), elrond.nextComponentMessage());
        assertEquals(List.of(), messenger.notifications(), "nothing changed, so nobody is told");
    }

    @Test
    void withdraw_remote() throws SQLException {
        PlayerMock elrond = teamMember("Elrond");
        UUID bilbo = remoteGuest("Bilbo_B");
        vote(elrond.getUniqueId(), bilbo, 0.2, 4);

        voting.withdraw(elrond, "bilbo_b");

        assertEquals(Messages.withdrawn("Bilbo_B"), elrond.nextComponentMessage());
        assertEquals(List.of(new Notification("Bilbo_B", Update.UPDATE)), messenger.notifications());
        assertEquals(List.of(), votes.validVotesFor(bilbo, 0L));
    }

    @Test
    void withdraw_by_name_of_somebody_unknown() {
        PlayerMock elrond = teamMember("Elrond");

        voting.withdraw(elrond, "nobody");

        assertEquals(Messages.unknownPlayer("nobody"), elrond.nextComponentMessage());
    }

    @Test
    void withdraw_requires_permission() throws SQLException {
        PlayerMock elrond = teamMember("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        vote(elrond.getUniqueId(), bilbo.getUniqueId(), 0.2, 4);
        elrond.addAttachment(plugin, Permissions.VOTE, false);

        voting.withdraw(elrond, bilbo.getUniqueId(), "Bilbo_B");

        assertEquals(Messages.noPermission(), elrond.nextComponentMessage());
        assertEquals(1, stored(bilbo).size());
    }

    // ---- progress ----

    @Test
    void progress_of() throws SQLException {
        PlayerMock bilbo = guest("Bilbo_B");
        vote(OTHER_VOTER, bilbo.getUniqueId(), 0.8, 1);
        ConsoleCommandSenderMock console = server.getConsoleSender();

        voting.showProgressOf(console, "bilbo_b");

        assertEquals("[Vote] Bilbo_B has 80% of the votes needed.", Texts.plain(console.nextComponentMessage()));
        assertNull(console.nextComponentMessage());
    }

    @Test
    void progress_of_somebody_on_another_server_and_of_nobody() throws SQLException {
        UUID bilbo = remoteGuest("Bilbo_B");
        vote(OTHER_VOTER, bilbo, 0.4, 1);
        vote(UUID.randomUUID(), bilbo, 0.2, 200); // expired
        ConsoleCommandSenderMock console = server.getConsoleSender();

        voting.showProgressOf(console, "Bilbo_B");
        voting.showProgressOf(console, "nobody");

        assertEquals("[Vote] Bilbo_B has 40% of the votes needed.", Texts.plain(console.nextComponentMessage()));
        assertEquals("[Vote] No player called nobody has been on MCME.", Texts.plain(console.nextComponentMessage()));
    }

    // ---- failures ----

    @Test
    void database_down() throws SQLException {
        PlayerMock elrond = teamMember("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        remoteGuest("Frodo99");
        ConsoleCommandSenderMock console = server.getConsoleSender();
        breakDatabase();
        presenter.clear();

        assertDoesNotThrow(() -> voting.openMenu(elrond));
        assertEquals(Messages.databaseDown(), elrond.nextComponentMessage());
        assertDoesNotThrow(() -> voting.cast(elrond, bilbo.getUniqueId(), "Bilbo_B", "x"));
        assertEquals(Messages.databaseDown(), elrond.nextComponentMessage());
        assertDoesNotThrow(() -> voting.showOwnProgress(bilbo));
        assertEquals(Messages.databaseDown(), bilbo.nextComponentMessage());
        assertDoesNotThrow(() -> voting.openVote(elrond, "Bilbo_B"));
        assertEquals(Messages.databaseDown(), elrond.nextComponentMessage());
        assertDoesNotThrow(() -> voting.withdraw(elrond, bilbo.getUniqueId(), "Bilbo_B"));
        assertEquals(Messages.databaseDown(), elrond.nextComponentMessage());
        assertDoesNotThrow(() -> voting.showProgressOf(console, "Frodo99"));
        assertEquals(Texts.plain(Messages.databaseDown()), Texts.plain(console.nextComponentMessage()));

        assertNull(elrond.nextComponentMessage());
        assertNull(presenter.last(elrond, CandidateListView.class));
        assertNull(presenter.last(elrond, VoteView.class));
        assertNull(presenter.last(bilbo, GuestInfoView.class));
        assertNull(bilbo.nextActionBar());
        assertEquals(List.of(), messenger.notifications());
        assertEquals(List.of(), commands.commands());
    }

    @Test
    void a_luckperms_failure_is_the_same_as_a_database_failure() {
        RankService broken = mock(RankService.class);
        CompletableFuture<Object> failure = CompletableFuture.failedFuture(new IllegalStateException("LuckPerms down"));
        when(broken.lookup(any())).thenReturn(failing(failure));
        when(broken.isExempt(any())).thenReturn(failing(failure));
        when(broken.names(any())).thenReturn(failing(failure));
        VoteService service = new VoteService(db, main, votes, settings, broken, messenger, presenter, xp, promotion,
                () -> now);
        PlayerMock elrond = teamMember("Elrond");
        UUID offline = UUID.randomUUID();
        presenter.clear();

        assertDoesNotThrow(() -> service.openVote(elrond, "gimli"));
        assertEquals(Messages.databaseDown(), elrond.nextComponentMessage());
        assertDoesNotThrow(() -> service.cast(elrond, offline, "Gimli", ""));
        assertEquals(Messages.databaseDown(), elrond.nextComponentMessage());
        assertDoesNotThrow(() -> service.openVote(elrond, offline, "Gimli"));
        assertEquals(Messages.databaseDown(), elrond.nextComponentMessage());
        assertDoesNotThrow(() -> service.withdraw(elrond, "gimli"));
        assertEquals(Messages.databaseDown(), elrond.nextComponentMessage());

        assertNull(elrond.nextComponentMessage());
        assertNull(presenter.last(elrond, VoteView.class));
        assertEquals(List.of(), messenger.notifications());
    }

    @SuppressWarnings("unchecked")
    private static <T> CompletableFuture<T> failing(CompletableFuture<?> future) {
        return (CompletableFuture<T>) future;
    }

    // ---- a player who quits while the work is under way ----

    @Test
    void quit_before_continuation() throws SQLException {
        QueueExecutor queue = new QueueExecutor();
        main = queue;
        build();
        PlayerMock elrond = teamMember("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        guest("Frodo99");
        presenter.clear();

        voting.openMenu(elrond);
        voting.openVote(elrond, "Bilbo_B");
        voting.openVote(elrond, "frodo99");
        voting.showOwnProgress(bilbo);
        voting.showProgressOf(bilbo, "Frodo99");
        elrond.disconnect();
        bilbo.disconnect();

        assertDoesNotThrow(queue::runAll);
        assertNull(presenter.last(elrond, CandidateListView.class));
        assertNull(presenter.last(elrond, VoteView.class));
        assertNull(presenter.last(bilbo, GuestInfoView.class));
        assertNull(elrond.nextComponentMessage());
        assertNull(bilbo.nextComponentMessage());
    }

    @Test
    void a_database_failure_is_not_told_to_a_player_who_left() throws SQLException {
        QueueExecutor queue = new QueueExecutor();
        main = queue;
        connections = () -> {
            throw new SQLException("Communications link failure");
        };
        build();
        PlayerMock elrond = teamMember("Elrond");
        guest("Bilbo_B");

        voting.openMenu(elrond);
        elrond.disconnect();

        assertDoesNotThrow(queue::runAll);
        assertNull(elrond.nextComponentMessage());
    }

    @Test
    void a_vote_that_was_stored_still_reaches_the_guest_when_the_voter_quit() throws SQLException {
        QueueExecutor queue = new QueueExecutor();
        main = queue;
        build();
        PlayerMock elrond = teamMember("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");
        vote(OTHER_VOTER, bilbo.getUniqueId(), 0.8, 1);

        voting.cast(elrond, bilbo.getUniqueId(), "Bilbo_B", "");
        elrond.disconnect();
        assertDoesNotThrow(queue::runAll);

        assertEquals(2, stored(bilbo).size(), "the vote is in the database");
        assertEquals(List.of(PROMOTE_BILBO), commands.commands(), "so the guest is promoted all the same");
        assertNull(elrond.nextComponentMessage(), "nothing goes to the player who left");
    }

    @Test
    void a_guest_who_left_before_the_vote_lands_is_told_through_the_network() throws SQLException {
        QueueExecutor queue = new QueueExecutor();
        main = queue;
        build();
        PlayerMock elrond = teamMember("Elrond");
        PlayerMock bilbo = guest("Bilbo_B");

        voting.cast(elrond, bilbo.getUniqueId(), "Bilbo_B", "");
        bilbo.disconnect(); // moved to another server, say
        assertDoesNotThrow(queue::runAll);

        assertEquals(List.of(new Notification("Bilbo_B", Update.VOTED)), messenger.notifications());
        assertEquals(Messages.voteCast("Bilbo_B", 0, 20), elrond.nextComponentMessage());
    }
}
