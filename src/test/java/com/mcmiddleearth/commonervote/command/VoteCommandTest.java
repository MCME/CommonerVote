package com.mcmiddleearth.commonervote.command;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.command.CommandSender;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.MessageTarget;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.mcmiddleearth.commonervote.Permissions;
import com.mcmiddleearth.commonervote.service.Messages;
import com.mcmiddleearth.commonervote.support.RecordingPresenter;
import com.mcmiddleearth.commonervote.support.TestCommonerVotePlugin;
import com.mcmiddleearth.commonervote.ui.Texts;
import com.mcmiddleearth.commonervote.ui.view.CandidateListView;
import com.mcmiddleearth.commonervote.ui.view.ConfirmView;
import com.mcmiddleearth.commonervote.ui.view.GuestInfoView;
import com.mcmiddleearth.commonervote.ui.view.ReviewDetailView;
import com.mcmiddleearth.commonervote.ui.view.ReviewListView;
import com.mcmiddleearth.commonervote.ui.view.SettingsView;
import com.mcmiddleearth.commonervote.ui.view.VoteView;

import net.kyori.adventure.text.Component;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code /vote} command tree on a MockBukkit server, with the real plugin and its services on the fakes of
 * {@link TestCommonerVotePlugin}.
 * <p>
 * When a {@code requires} fails, MockBukkit answers the player with an error and still reports the command as handled,
 * so the tests look for the view or the effect the command would have had, never at the boolean.
 */
class VoteCommandTest {

    private ServerMock server;
    private TestCommonerVotePlugin plugin;
    private RecordingPresenter presenter;

    @BeforeEach
    void setUp() throws IOException, InvalidDescriptionException {
        server = MockBukkit.mock();
        PluginDescriptionFile description;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            description = new PluginDescriptionFile(in);
        }
        plugin = MockBukkit.loadWith(TestCommonerVotePlugin.class, description);
        presenter = plugin.presenter();
        // The first dispatch fires the COMMANDS lifecycle event; before it, no Brigadier command exists.
        server.dispatchCommand(server.getConsoleSender(), "help");
        drain(server.getConsoleSender());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ---- /vote ----

    @Test
    void vote_alone_team() {
        PlayerMock team = teamMember("Gandalf");
        guest("Bilbo_B");

        team.performCommand("vote");

        CandidateListView list = presenter.last(team, CandidateListView.class);
        assertNotNull(list, "a voter's /vote shows the guests online");
        assertEquals(List.of("Bilbo_B"), list.rows().stream().map(CandidateListView.Row::name).toList());
    }

    @Test
    void vote_alone_guest() {
        PlayerMock guest = guest("Bilbo_B");

        guest.performCommand("vote");

        assertNotNull(presenter.last(guest, GuestInfoView.class), "/vote is open to a player without permissions");
    }

    @Test
    void vote_player_requires_vote() {
        PlayerMock bilbo = guest("Bilbo_B");
        PlayerMock team = teamMember("Gandalf");
        guest("Frodo99");
        drain(bilbo);

        bilbo.performCommand("vote Frodo99");
        team.performCommand("vote Frodo99");

        assertRefusedByTheTree(bilbo, "vote Frodo99");
        assertNull(presenter.last(bilbo, VoteView.class), "a guest may not vote");
        VoteView view = presenter.last(team, VoteView.class);
        assertNotNull(view, "a team member's /vote <player> opens the vote dialog");
        assertEquals("Frodo99", view.name());
    }

    @Test
    void namespaced() {
        PlayerMock guest = guest("Bilbo_B");
        PlayerMock team = teamMember("Gandalf");
        guest("Frodo99");

        guest.performCommand("commonervote:vote");
        team.performCommand("commonervote:vote Frodo99");

        assertNotNull(presenter.last(guest, GuestInfoView.class));
        assertNotNull(presenter.last(team, VoteView.class));
    }

    @Test
    void literal_wins() {
        PlayerMock team = teamMember("Gandalf");
        guest("review"); // a guest whose name is the subcommand's
        drain(team);

        team.performCommand("vote review");
        assertRefusedByTheTree(team, "vote review");
        team.performCommand("commonervote:vote review");
        assertRefusedByTheTree(team, "commonervote:vote review");

        assertNull(presenter.last(team, ReviewListView.class), "review needs commonervote.review");
        assertNull(presenter.last(team, VoteView.class), "not a vote for the player called review");
    }

    @Test
    void withdraw_requires_vote() throws SQLException {
        PlayerMock bilbo = guest("Bilbo_B");
        PlayerMock frodo = guest("Frodo99");
        vote(bilbo.getUniqueId(), frodo.getUniqueId()); // cast while Bilbo was still on the team
        drain(bilbo);

        bilbo.performCommand("vote withdraw Frodo99");

        assertRefusedByTheTree(bilbo, "vote withdraw Frodo99");
        assertEquals(1, votesFor(frodo.getUniqueId()), "the vote stays");
    }

    // ---- the other subcommands ----

    @Test
    void withdraw_and_score() throws SQLException {
        PlayerMock team = teamMember("Gandalf");
        PlayerMock frodo = guest("Frodo99");
        vote(team.getUniqueId(), frodo.getUniqueId());
        drain(team);

        team.performCommand("vote withdraw frodo99");
        assertEquals(List.of("[Vote] You withdrew your vote for Frodo99."), plainMessages(team));

        frodo.performCommand("vote score");
        assertNotNull(presenter.last(frodo, GuestInfoView.class), "/vote score shows a guest's own progress");
    }

    @Test
    void score_of_another_player_needs_score_other_or_review() throws SQLException {
        PlayerMock bilbo = guest("Bilbo_B");
        PlayerMock frodo = guest("Frodo99");
        vote(UUID.randomUUID(), frodo.getUniqueId());
        PlayerMock scorer = withPermissions(guest("Sam_G"), Permissions.SCORE_OTHER);
        PlayerMock reviewer = withPermissions(guest("Elrond"), Permissions.REVIEW);
        drain(bilbo);
        drain(scorer);
        drain(reviewer);

        bilbo.performCommand("vote score Frodo99");
        scorer.performCommand("vote score Frodo99");
        reviewer.performCommand("vote score Frodo99");

        assertTrue(plainMessages(bilbo).stream().noneMatch(m -> m.contains("20%")),
                "a guest does not see another player's progress");
        assertEquals(List.of("[Vote] Frodo99 has 20% of the votes needed."), plainMessages(scorer));
        assertEquals(List.of("[Vote] Frodo99 has 20% of the votes needed."), plainMessages(reviewer));
    }

    @Test
    void admin_subcommands_open_their_dialogs() throws SQLException {
        PlayerMock admin = admin("Elrond");
        PlayerMock frodo = guest("Frodo99");
        vote(UUID.randomUUID(), frodo.getUniqueId());

        admin.performCommand("vote review");
        assertNotNull(presenter.last(admin, ReviewListView.class));

        admin.performCommand("vote review frodo99");
        ReviewDetailView detail = presenter.last(admin, ReviewDetailView.class);
        assertNotNull(detail);
        assertEquals("Frodo99", detail.name());

        admin.performCommand("vote clear Frodo99");
        assertEquals("Clear votes for Frodo99?", presenter.last(admin, ConfirmView.class).title());

        admin.performCommand("vote promote Frodo99");
        assertEquals("Promote Frodo99 now?", presenter.last(admin, ConfirmView.class).title());

        admin.performCommand("vote config");
        assertNotNull(presenter.last(admin, SettingsView.class));

        assertEquals(1, votesFor(frodo.getUniqueId()), "a player's clear only asks to confirm");
        assertEquals(List.of(), plugin.commands().commands(), "a player's promote only asks to confirm");
    }

    @Test
    void admin_subcommands_need_their_permission() throws SQLException {
        PlayerMock team = teamMember("Gandalf");
        PlayerMock frodo = guest("Frodo99");
        vote(UUID.randomUUID(), frodo.getUniqueId());
        drain(team);

        for (String command : List.of("vote review Frodo99", "vote clear Frodo99", "vote promote Frodo99",
                "vote config")) {
            team.performCommand(command);
            assertRefusedByTheTree(team, command);
        }

        assertNull(presenter.last(team, ReviewDetailView.class));
        assertNull(presenter.last(team, ConfirmView.class));
        assertNull(presenter.last(team, SettingsView.class));
        assertNull(presenter.last(team, VoteView.class));
    }

    // ---- the console ----

    @Test
    void console_review() throws SQLException {
        PlayerMock frodo = guest("Frodo99");
        vote(UUID.randomUUID(), frodo.getUniqueId());

        server.dispatchCommand(server.getConsoleSender(), "vote review");

        assertEquals(List.of("- Frodo99: 20%"), plainMessages(server.getConsoleSender()));
    }

    @Test
    void console_review_of_one_player_and_clear() throws SQLException {
        PlayerMock frodo = guest("Frodo99");
        UUID gandalf = guest("Gandalf").getUniqueId();
        vote(gandalf, frodo.getUniqueId());

        server.dispatchCommand(server.getConsoleSender(), "vote review frodo99");
        List<String> review = plainMessages(server.getConsoleSender());
        assertEquals(1, review.size(), review.toString());
        assertTrue(review.getFirst().startsWith("- Gandalf: 0 days ago"), review.getFirst());

        server.dispatchCommand(server.getConsoleSender(), "vote clear Frodo99");
        assertEquals(List.of("[Vote] Cleared 1 votes for Frodo99."), plainMessages(server.getConsoleSender()),
                "the console clears without a confirmation");
        assertEquals(0, votesFor(frodo.getUniqueId()));
    }

    @Test
    void console_vote_and_help_print_the_console_lines() {
        server.dispatchCommand(server.getConsoleSender(), "vote");
        List<String> alone = plainMessages(server.getConsoleSender());
        server.dispatchCommand(server.getConsoleSender(), "vote help");
        List<String> help = plainMessages(server.getConsoleSender());

        assertEquals(help, alone, "/vote from the console prints the help lines");
        assertTrue(help.stream().anyMatch(l -> l.startsWith("/vote review")), help.toString());
        assertTrue(help.stream().anyMatch(l -> l.startsWith("/vote clear")), help.toString());
        assertTrue(help.stream().noneMatch(l -> l.startsWith("/vote config")), "config is for players only");
        assertTrue(help.stream().noneMatch(l -> l.startsWith("[Vote]")), "help lines have no chat prefix");
    }

    // ---- help ----

    @Test
    void help_lists_allowed_only() {
        PlayerMock guest = guest("Bilbo_B");
        drain(guest);

        guest.performCommand("vote help");

        List<String> lines = plainMessages(guest);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("/vote score ")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("/vote help ")), lines.toString());
        assertTrue(lines.stream().noneMatch(l -> l.contains("review")), lines.toString());
        assertTrue(lines.stream().noneMatch(l -> l.startsWith("[Vote]")), "help lines have no chat prefix");
        assertEquals(2, lines.size(), "a guest may use score and help only: " + lines);
    }

    @Test
    void help_for_an_admin_lists_every_subcommand() {
        PlayerMock admin = admin("Elrond");
        drain(admin);

        admin.performCommand("vote help");

        assertEquals(10, plainMessages(admin).size(), "one line per subcommand");
    }

    // ---- suggestions ----

    @Test
    void suggestions() throws SQLException {
        PlayerMock team = teamMember("Gandalf");
        guest("Bilbo_B");
        PlayerMock frodo = guest("Frodo99");
        vote(UUID.randomUUID(), frodo.getUniqueId());
        PlayerMock admin = admin("Elrond");

        List<String> names = tabComplete(team, "vote bi");
        assertTrue(names.contains("Bilbo_B"), names.toString());
        assertFalse(names.contains("Frodo99"), names.toString());

        List<String> candidates = tabComplete(admin, "vote review fr");
        assertTrue(candidates.contains("Frodo99"), candidates.toString());
        assertEquals(List.of("Frodo99"), tabComplete(admin, "vote clear F"));
        assertEquals(List.of("Frodo99"), tabComplete(admin, "vote promote f"));
        assertEquals(List.of(), tabComplete(admin, "vote promote b"), "Bilbo_B has no votes");
    }

    @Test
    void no_suggestions_without_permission() throws SQLException {
        PlayerMock guest = guest("Bilbo_B");
        PlayerMock team = teamMember("Gandalf");
        PlayerMock frodo = guest("Frodo99");
        vote(UUID.randomUUID(), frodo.getUniqueId());

        assertEquals(List.of(), tabComplete(guest, "vote fr"), "a guest may not vote");
        assertEquals(List.of(), tabComplete(guest, "vote score fr"));
        assertEquals(List.of(), tabComplete(team, "vote review fr"));
    }

    // ---- a disabled plugin ----

    @Test
    void a_disabled_plugin_does_nothing() {
        PlayerMock team = teamMember("Gandalf");
        guest("Bilbo_B");
        server.getPluginManager().disablePlugin(plugin);

        assertDoesNotThrow(() -> team.performCommand("vote"), "the database executor has stopped");

        assertNull(presenter.last(team, CandidateListView.class));
        assertEquals(List.of(), tabComplete(team, "vote bi"));
    }

    // ---- helpers ----

    /** A player online here with no permissions, known to the rank service under this name. */
    private PlayerMock guest(String name) {
        PlayerMock player = server.addPlayer(name);
        plugin.ranks().add(player.getUniqueId(), name);
        return player;
    }

    private PlayerMock teamMember(String name) {
        return withPermissions(guest(name), Permissions.VOTE);
    }

    /** A player with every permission of the command tree. */
    private PlayerMock admin(String name) {
        return withPermissions(guest(name), Permissions.VOTE, Permissions.SCORE_OTHER, Permissions.REVIEW,
                Permissions.CLEAR, Permissions.PROMOTE, Permissions.CONFIG);
    }

    private PlayerMock withPermissions(PlayerMock player, String... permissions) {
        for (String permission : permissions) {
            player.addAttachment(plugin, permission, true);
        }
        return player;
    }

    /** Stores a team vote cast now, with the weight of a team vote under the default settings. */
    private void vote(UUID voter, UUID recipient) throws SQLException {
        try (Connection c = plugin.database().open();
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

    private int votesFor(UUID recipient) throws SQLException {
        try (Connection c = plugin.database().open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM commonervote_votes WHERE recipient = ?")) {
            ps.setString(1, recipient.toString());
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * What tab completion offers the sender for this command line. MockBukkit 4.116.1's
     * {@code server.getCommandTabComplete} calls the three-argument {@code Command.tabComplete}, which for a Brigadier
     * command ends in Bukkit's default player-name completion (unimplemented there). The command map calls the
     * four-argument one, which MockBukkit's Brigadier wrapper answers from the dispatcher's suggestions.
     */
    private List<String> tabComplete(CommandSender sender, String commandLine) {
        return server.getCommandMap().tabComplete(sender, commandLine);
    }

    /**
     * Asserts that the tree's {@code requires} refused the command, not the service behind it. Both refuse a sender
     * without the permission, but differently: the tree with Brigadier's error, the service with
     * {@link Messages#noPermission()}. So the sender must not have been sent that line. Reads the sender's messages.
     */
    private static void assertRefusedByTheTree(PlayerMock sender, String command) {
        List<String> lines = plainMessages(sender);
        assertFalse(lines.contains(Texts.plain(Messages.noPermission())),
                command + " reached the service, which refused it: " + lines);
    }

    /** Every message the target has received and not yet read, as plain text. */
    private static List<String> plainMessages(MessageTarget target) {
        List<String> lines = new ArrayList<>();
        for (Component c = target.nextComponentMessage(); c != null; c = target.nextComponentMessage()) {
            lines.add(Texts.plain(c));
        }
        return lines;
    }

    private static void drain(MessageTarget target) {
        plainMessages(target);
    }
}
