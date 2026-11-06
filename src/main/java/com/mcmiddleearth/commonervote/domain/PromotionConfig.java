package com.mcmiddleearth.commonervote.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * How a player is promoted on this server: the console command and the group it gives. Unlike {@link Settings}, these
 * two are read from this server's config.yml only.
 * <p>
 * A pure value: no Bukkit. Both values are stripped, because a 1.2.9 config.yml has a space after its command.
 *
 * @param command the console command, which may use {@code <group>} and must use {@code <player>}
 * @param group   the LuckPerms group the command gives
 */
public record PromotionConfig(String command, String group) {

    /** The keys in config.yml, which are 1.2.9's, so an existing config.yml keeps working. */
    public static final String COMMAND_KEY = "promotionCommand";
    public static final String GROUP_KEY = "commonerGroupName";

    private static final String PLAYER_TAG = "<player>";
    private static final String GROUP_TAG = "<group>";

    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern CONTROL_CHARACTER = Pattern.compile("\\p{Cc}");

    private static final PromotionConfig DEFAULTS =
            new PromotionConfig("lp user " + PLAYER_TAG + " parent set " + GROUP_TAG, "commoner");

    public PromotionConfig {
        command = command.strip();
        group = group.strip();
    }

    public static PromotionConfig defaults() {
        return DEFAULTS;
    }

    /**
     * The console command that promotes {@code player}. {@code <player>} is the UUID, never a name: a shown name can be
     * stale (a renamed guest's old name may be someone else's now), and LuckPerms takes a UUID wherever it takes a name.
     */
    public String commandFor(UUID player) {
        return command.replace(PLAYER_TAG, player.toString()).replace(GROUP_TAG, group);
    }

    /**
     * What is wrong with these values, one sentence per fault, each naming the key in config.yml; empty when they are
     * usable.
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (!command.contains(PLAYER_TAG)) {
            problems.add(COMMAND_KEY + " must be text that contains " + PLAYER_TAG + ".");
        }
        if (CONTROL_CHARACTER.matcher(command).find()) {
            problems.add(COMMAND_KEY + " must be one line.");
        }
        if (!PLAIN_NAME.matcher(group).matches()) {
            problems.add(GROUP_KEY + " must be text: one group name of letters, digits, _ and -.");
        }
        return List.copyOf(problems);
    }
}
