package com.mcmiddleearth.commonervote.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The plugin's shared settings, as stored in the {@code commonervote_settings} table.
 * <p>
 * A pure value: no Bukkit and no database code. {@link #fromMap} never fails; a stored value that cannot be used
 * falls back to its default and is reported through the {@code warn} callback, so one bad row cannot disable the
 * plugin. {@link #problems()} is the separate check an editor runs before saving.
 * <p>
 * The three texts are MiniMessage strings, kept as plain strings here; nothing in this class parses them.
 *
 * @param promotableGroups lowercase LuckPerms group names, immutable
 */
public record Settings(int votesNeeded, int validityDays, int staffWeight, int teamWeight, boolean autoPromote,
                       boolean xpBar, boolean broadcastNetwork, String commonerGroup, Set<String> promotableGroups,
                       String promotionCommand, String rulesUrl, String guideUrl, String guestInfoText,
                       String welcomeText, String announceText) {

    public static final IntRange VOTES_NEEDED = new IntRange(1, 20);
    public static final IntRange VALIDITY_DAYS = new IntRange(1, 365);
    public static final IntRange STAFF_WEIGHT = new IntRange(1, 5);
    public static final IntRange TEAM_WEIGHT = new IntRange(1, 5);

    private static final String PLAYER_TAG = "<player>";

    // The names in the settings table, which are also the keys of toMap().
    private static final String VOTES_NEEDED_KEY = "votesNeeded";
    private static final String VALIDITY_DAYS_KEY = "validityDays";
    private static final String STAFF_WEIGHT_KEY = "staffWeight";
    private static final String TEAM_WEIGHT_KEY = "teamWeight";
    private static final String AUTO_PROMOTE_KEY = "autoPromote";
    private static final String XP_BAR_KEY = "xpBar";
    private static final String BROADCAST_NETWORK_KEY = "broadcastNetwork";
    private static final String COMMONER_GROUP_KEY = "commonerGroup";
    private static final String PROMOTABLE_GROUPS_KEY = "promotableGroups";
    private static final String PROMOTION_COMMAND_KEY = "promotionCommand";
    private static final String RULES_URL_KEY = "rulesUrl";
    private static final String GUIDE_URL_KEY = "guideUrl";
    private static final String GUEST_INFO_TEXT_KEY = "guestInfoText";
    private static final String WELCOME_TEXT_KEY = "welcomeText";
    private static final String ANNOUNCE_TEXT_KEY = "announceText";

    private static final Settings DEFAULTS = new Settings(5, 100, 2, 1, true, true, true,
            "commoner", Set.of(), "lp user <player> parent set <group>",
            "https://www.mcmiddleearth.com/help/terms", "",
            "Team members vote for guests whose building they've seen. "
                    + "<yellow><votes_needed> votes</yellow> make you a Commoner. Votes last <validity_days> days.",
            "The team voted you up. You're now a <aqua>Commoner</aqua> of Middle-earth."
                    + "<newline>Please read the rules before you start.",
            "<gold>★ <player> <yellow>has been promoted to <aqua>Commoner<yellow>!");

    public Settings {
        promotableGroups = Set.copyOf(promotableGroups);
    }

    public static Settings defaults() {
        return DEFAULTS;
    }

    /**
     * Reads settings from stored name/value pairs. A missing or {@code null} value gives the default silently.
     * A number that is not a whole number or lies outside its range, and a switch that is not {@code true} or
     * {@code false} (any case), gives the default and one warning that names the setting. Texts are taken as stored.
     */
    public static Settings fromMap(Map<String, String> values, Consumer<String> warn) {
        Settings d = DEFAULTS;
        return new Settings(
                intValue(values, VOTES_NEEDED_KEY, VOTES_NEEDED, d.votesNeeded, warn),
                intValue(values, VALIDITY_DAYS_KEY, VALIDITY_DAYS, d.validityDays, warn),
                intValue(values, STAFF_WEIGHT_KEY, STAFF_WEIGHT, d.staffWeight, warn),
                intValue(values, TEAM_WEIGHT_KEY, TEAM_WEIGHT, d.teamWeight, warn),
                boolValue(values, AUTO_PROMOTE_KEY, d.autoPromote, warn),
                boolValue(values, XP_BAR_KEY, d.xpBar, warn),
                boolValue(values, BROADCAST_NETWORK_KEY, d.broadcastNetwork, warn),
                text(values, COMMONER_GROUP_KEY, d.commonerGroup),
                parseGroups(values.get(PROMOTABLE_GROUPS_KEY)),
                text(values, PROMOTION_COMMAND_KEY, d.promotionCommand),
                text(values, RULES_URL_KEY, d.rulesUrl),
                text(values, GUIDE_URL_KEY, d.guideUrl),
                text(values, GUEST_INFO_TEXT_KEY, d.guestInfoText),
                text(values, WELCOME_TEXT_KEY, d.welcomeText),
                text(values, ANNOUNCE_TEXT_KEY, d.announceText));
    }

    /** A new map of all 15 settings as stored strings; the groups are written sorted and comma-joined. */
    public Map<String, String> toMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(VOTES_NEEDED_KEY, String.valueOf(votesNeeded));
        m.put(VALIDITY_DAYS_KEY, String.valueOf(validityDays));
        m.put(STAFF_WEIGHT_KEY, String.valueOf(staffWeight));
        m.put(TEAM_WEIGHT_KEY, String.valueOf(teamWeight));
        m.put(AUTO_PROMOTE_KEY, String.valueOf(autoPromote));
        m.put(XP_BAR_KEY, String.valueOf(xpBar));
        m.put(BROADCAST_NETWORK_KEY, String.valueOf(broadcastNetwork));
        m.put(COMMONER_GROUP_KEY, commonerGroup);
        m.put(PROMOTABLE_GROUPS_KEY, String.join(",", new TreeSet<>(promotableGroups)));
        m.put(PROMOTION_COMMAND_KEY, promotionCommand);
        m.put(RULES_URL_KEY, rulesUrl);
        m.put(GUIDE_URL_KEY, guideUrl);
        m.put(GUEST_INFO_TEXT_KEY, guestInfoText);
        m.put(WELCOME_TEXT_KEY, welcomeText);
        m.put(ANNOUNCE_TEXT_KEY, announceText);
        return m;
    }

    /**
     * What is wrong with these settings, one sentence per fault, each naming the setting; empty when they are
     * usable. Nothing is clamped or corrected here.
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        checkRange(problems, VOTES_NEEDED_KEY, votesNeeded, VOTES_NEEDED);
        checkRange(problems, VALIDITY_DAYS_KEY, validityDays, VALIDITY_DAYS);
        checkRange(problems, STAFF_WEIGHT_KEY, staffWeight, STAFF_WEIGHT);
        checkRange(problems, TEAM_WEIGHT_KEY, teamWeight, TEAM_WEIGHT);
        if (commonerGroup.isBlank()) {
            problems.add(COMMONER_GROUP_KEY + " must not be empty.");
        }
        if (promotionCommand.isBlank()) {
            problems.add(PROMOTION_COMMAND_KEY + " must not be empty.");
        } else if (!promotionCommand.contains(PLAYER_TAG)) {
            problems.add(PROMOTION_COMMAND_KEY + " must contain " + PLAYER_TAG + ".");
        }
        return List.copyOf(problems);
    }

    /** These settings with the numbers and switches of the form; everything else is unchanged. */
    public Settings withNumbers(SettingsForm f) {
        return new Settings(f.votesNeeded(), f.validityDays(), f.staffWeight(), f.teamWeight(), f.autoPromote(),
                f.xpBar(), f.broadcastNetwork(), commonerGroup, promotableGroups, promotionCommand, rulesUrl,
                guideUrl, guestInfoText, welcomeText, announceText);
    }

    /** These settings with the text fields of the form; the numbers and switches are unchanged. */
    public Settings withTexts(TextsForm f) {
        return new Settings(votesNeeded, validityDays, staffWeight, teamWeight, autoPromote, xpBar,
                broadcastNetwork, f.commonerGroup(), parseGroups(f.promotableGroups()), f.promotionCommand(),
                f.rulesUrl(), f.guideUrl(), f.guestInfoText(), f.welcomeText(), f.announceText());
    }

    /** Splits a comma-separated list into lowercase names, dropping blanks; {@code null} gives the empty set. */
    static Set<String> parseGroups(String csv) {
        if (csv == null) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void checkRange(List<String> problems, String name, int value, IntRange range) {
        if (!range.contains(value)) {
            problems.add(name + " must be between " + range.min() + " and " + range.max() + " but is " + value + ".");
        }
    }

    private static int intValue(Map<String, String> values, String name, IntRange range, int fallback,
                                Consumer<String> warn) {
        String raw = values.get(name);
        if (raw == null) {
            return fallback;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            warn.accept("Setting " + name + " is '" + raw + "', which is not a whole number; using " + fallback + ".");
            return fallback;
        }
        if (!range.contains(parsed)) {
            warn.accept("Setting " + name + " is " + parsed + ", outside " + range.min() + " to " + range.max()
                    + "; using " + fallback + ".");
            return fallback;
        }
        return parsed;
    }

    private static boolean boolValue(Map<String, String> values, String name, boolean fallback,
                                     Consumer<String> warn) {
        String raw = values.get(name);
        if (raw == null) {
            return fallback;
        }
        if (raw.equalsIgnoreCase("true")) {
            return true;
        }
        if (raw.equalsIgnoreCase("false")) {
            return false;
        }
        warn.accept("Setting " + name + " is '" + raw + "', which is neither true nor false; using " + fallback + ".");
        return fallback;
    }

    private static String text(Map<String, String> values, String name, String fallback) {
        String raw = values.get(name);
        return raw == null ? fallback : raw;
    }
}
