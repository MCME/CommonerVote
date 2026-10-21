package com.mcmiddleearth.commonervote.service;

import java.util.Locale;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Every chat line the plugin sends, with the {@code [Vote]} prefix.
 * <p>
 * The text after the prefix is plain text built from the arguments, so a player name is never parsed as MiniMessage.
 * Numbers are formatted without a locale, so they read the same on every server.
 */
public final class Messages {

    private static final Component PREFIX = MiniMessage.miniMessage().deserialize("<gray>[<gold>Vote</gold>]</gray> ");

    private Messages() {
    }

    public static Component voteCast(String name, int before, int after) {
        return chat("Your vote for %s is in. %d%% → %d%%.", name, before, after);
    }

    public static Component voteRenewed(String name, int days) {
        return chat("Your vote for %s is renewed. It lasts another %d days.", name, days);
    }

    public static Component withdrawn(String name) {
        return chat("You withdrew your vote for %s.", name);
    }

    public static Component noVoteToWithdraw(String name) {
        return chat("You have no vote for %s.", name);
    }

    public static Component selfVote() {
        return chat("You can't vote for yourself.");
    }

    public static Component targetExempt(String name) {
        return chat("%s is already a Commoner or higher.", name);
    }

    public static Component unknownPlayer(String name) {
        return chat("No player called %s has been on MCME.", name);
    }

    public static Component alreadyCommoner() {
        return chat("You're already a Commoner.");
    }

    public static Component databaseDown() {
        return chat("The vote database is unreachable. Try again in a minute.");
    }

    public static Component noGuestsOnline() {
        return chat("There are no guests on this server right now.");
    }

    public static Component noCandidates() {
        return chat("Nobody has valid votes right now.");
    }

    public static Component progressOf(String name, int percent) {
        return chat("%s has %d%% of the votes needed.", name, percent);
    }

    public static Component votesCleared(String name, int count) {
        return chat("Cleared %d votes for %s.", count, name);
    }

    public static Component promoted(String name) {
        return chat("%s is now a Commoner.", name);
    }

    public static Component promoteBlocked(String name, String group) {
        return chat("%s already has the group %s. Use LuckPerms by hand.", name, group);
    }

    public static Component settingsSaved() {
        return chat("Settings saved for all servers.");
    }

    public static Component settingsRejected(String problem) {
        return chat("Not saved: %s", problem);
    }

    public static Component noPermission() {
        return chat("You don't have permission to do that.");
    }

    /**
     * The action bar line a voted-for guest sees. It has no prefix; only the first part is yellow.
     */
    public static Component actionBar(int percent) {
        return Component.textOfChildren(
                Component.text("A team member voted for you", NamedTextColor.YELLOW),
                Component.text(String.format(Locale.ROOT, " · %d%% to Commoner", percent)));
    }

    private static Component chat(String format, Object... args) {
        return Component.textOfChildren(PREFIX, Component.text(String.format(Locale.ROOT, format, args)));
    }
}
