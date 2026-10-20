package com.mcmiddleearth.commonervote.service;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;

import static com.mcmiddleearth.commonervote.ui.Texts.plain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MessagesTest {

    private static void assertChat(String expectedAfterPrefix, Component actual) {
        assertEquals("[Vote] " + expectedAfterPrefix, plain(actual));
    }

    @Test
    void messages_plain() {
        assertChat("Your vote for Bilbo_B is in. 40% → 60%.", Messages.voteCast("Bilbo_B", 40, 60));
        assertChat("Your vote for Bilbo_B is renewed. It lasts another 100 days.",
                Messages.voteRenewed("Bilbo_B", 100));
        assertChat("You withdrew your vote for Bilbo_B.", Messages.withdrawn("Bilbo_B"));
        assertChat("You have no vote for Bilbo_B.", Messages.noVoteToWithdraw("Bilbo_B"));
        assertChat("You can't vote for yourself.", Messages.selfVote());
        assertChat("Gandalf is already a Commoner or higher.", Messages.targetExempt("Gandalf"));
        assertChat("No player called bilbo_b has been on MCME.", Messages.unknownPlayer("bilbo_b"));
        assertChat("You're already a Commoner.", Messages.alreadyCommoner());
        assertChat("The vote database is unreachable. Try again in a minute.", Messages.databaseDown());
        assertChat("There are no guests on this server right now.", Messages.noGuestsOnline());
        assertChat("Nobody has valid votes right now.", Messages.noCandidates());
        assertChat("Bilbo_B has 80% of the votes needed.", Messages.progressOf("Bilbo_B", 80));
        assertChat("Cleared 3 votes for Bilbo_B.", Messages.votesCleared("Bilbo_B", 3));
        assertChat("Bilbo_B is now a Commoner.", Messages.promoted("Bilbo_B"));
        assertChat("Bilbo_B already has the group builder. Use LuckPerms by hand.",
                Messages.promoteBlocked("Bilbo_B", "builder"));
        assertChat("Settings saved for all servers.", Messages.settingsSaved());
        assertChat("Not saved: votesNeeded must be between 1 and 20 but is 99.",
                Messages.settingsRejected("votesNeeded must be between 1 and 20 but is 99."));
        assertChat("You don't have permission to do that.", Messages.noPermission());
    }

    @Test
    void chat_lines_carry_the_gray_gold_prefix() {
        assertEquals("<gray>[<gold>Vote</gold>]</gray> You can't vote for yourself.",
                MiniMessage.miniMessage().serialize(Messages.selfVote()));
    }

    @Test
    void numbers_read_the_same_in_every_locale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"));
            assertChat("Cleared 1234 votes for Bilbo_B.", Messages.votesCleared("Bilbo_B", 1234));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void names_are_text_never_parsed() {
        String hostile = "<red>Evil</red>";
        assertChat("You withdrew your vote for <red>Evil</red>.", Messages.withdrawn(hostile));
        assertChat("No player called <red>Evil</red> has been on MCME.", Messages.unknownPlayer(hostile));
        assertChat("Not saved: <bold>x", Messages.settingsRejected("<bold>x"));
        // A parsed name would have coloured the text; as plain text the serializer has to escape it.
        assertEquals("<gray>[<gold>Vote</gold>]</gray> You withdrew your vote for \\<red>Evil\\</red>.",
                MiniMessage.miniMessage().serialize(Messages.withdrawn(hostile)));
    }

    @Test
    void action_bar_has_no_prefix_and_only_the_first_part_is_yellow() {
        Component bar = Messages.actionBar(40);
        assertEquals("A team member voted for you · 40% to Commoner", plain(bar));
        assertEquals(2, bar.children().size());
        assertNull(bar.color());
        assertEquals(NamedTextColor.YELLOW, bar.children().get(0).color());
        assertNull(bar.children().get(1).color());
    }
}
