package com.mcmiddleearth.commonervote.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SettingsTest {

    /** Everything different from the defaults, so a field that was not replaced shows up. */
    private static Settings custom() {
        return Settings.defaults().withTexts(new TextsForm("citizen", "badge_a,badge_b", "promote <player> now",
                "https://example.org/rules", "https://example.org/guide", "guest text", "welcome text",
                "announce text")).withNumbers(new SettingsForm(7, 50, 3, 2, false, false, false));
    }

    private static Map<String, String> only(String name, String value) {
        Map<String, String> values = new HashMap<>();
        values.put(name, value);
        return values;
    }

    @Test
    void defaults_match_production() {
        Settings s = Settings.defaults();
        assertEquals(5, s.votesNeeded());
        assertEquals(100, s.validityDays());
        assertEquals(2, s.staffWeight());
        assertEquals(1, s.teamWeight());
        assertTrue(s.autoPromote());
        assertTrue(s.xpBar());
        assertTrue(s.broadcastNetwork());
        assertEquals("commoner", s.commonerGroup());
        assertTrue(s.promotableGroups().isEmpty());
        assertEquals("lp user <player> parent set <group>", s.promotionCommand());
        assertEquals("https://www.mcmiddleearth.com/help/terms", s.rulesUrl());
        assertEquals("", s.guideUrl());
    }

    @Test
    void default_texts() {
        Settings s = Settings.defaults();
        assertEquals("Team members vote for guests whose building they've seen. "
                + "<yellow><votes_needed> votes</yellow> make you a Commoner. Votes last <validity_days> days.",
                s.guestInfoText());
        assertEquals("The team voted you up. You're now a <aqua>Commoner</aqua> of Middle-earth."
                + "<newline>Please read the rules before you start.", s.welcomeText());
        assertEquals("<gold>★ <player> <yellow>has been promoted to <aqua>Commoner<yellow>!", s.announceText());
    }

    @Test
    void ranges_are_the_documented_ones() {
        assertEquals(new IntRange(1, 20), Settings.VOTES_NEEDED);
        assertEquals(new IntRange(1, 365), Settings.VALIDITY_DAYS);
        assertEquals(new IntRange(1, 5), Settings.STAFF_WEIGHT);
        assertEquals(new IntRange(1, 5), Settings.TEAM_WEIGHT);
    }

    @Test
    void int_range_contains_and_clamps() {
        IntRange r = new IntRange(1, 20);
        assertTrue(r.contains(1));
        assertTrue(r.contains(20));
        assertFalse(r.contains(0));
        assertFalse(r.contains(21));
        assertEquals(1, r.clamp(-5));
        assertEquals(1, r.clamp(1));
        assertEquals(7, r.clamp(7));
        assertEquals(20, r.clamp(20));
        assertEquals(20, r.clamp(99));
    }

    @Test
    void round_trip() {
        assertEquals(Settings.defaults(), Settings.fromMap(Settings.defaults().toMap(), w -> fail(w)));
        assertEquals(custom(), Settings.fromMap(custom().toMap(), w -> fail(w)));
        assertEquals(Set.of("votesNeeded", "validityDays", "staffWeight", "teamWeight", "autoPromote", "xpBar",
                "broadcastNetwork", "commonerGroup", "promotableGroups", "promotionCommand", "rulesUrl", "guideUrl",
                "guestInfoText", "welcomeText", "announceText"), Settings.defaults().toMap().keySet());
        assertEquals(15, Settings.defaults().toMap().size());
    }

    @Test
    void to_map_writes_plain_strings() {
        Map<String, String> m = custom().toMap();
        assertEquals("7", m.get("votesNeeded"));
        assertEquals("50", m.get("validityDays"));
        assertEquals("false", m.get("autoPromote"));
        assertEquals("citizen", m.get("commonerGroup"));
        assertEquals("", Settings.defaults().toMap().get("guideUrl"));
        assertEquals("", Settings.defaults().toMap().get("promotableGroups"));
    }

    @Test
    void promotable_groups_parse() {
        Settings s = Settings.fromMap(only("promotableGroups", "Badge_X, ,pp_foo "), w -> fail(w));
        assertEquals(Set.of("badge_x", "pp_foo"), s.promotableGroups());
        assertEquals("badge_x,pp_foo", s.toMap().get("promotableGroups"));
    }

    @Test
    void promotable_groups_are_written_sorted() {
        Settings s = Settings.fromMap(only("promotableGroups", "zeta,Alpha,mid"), w -> fail(w));
        assertEquals("alpha,mid,zeta", s.toMap().get("promotableGroups"));
    }

    @Test
    void promotable_groups_are_immutable_and_copied() {
        Set<String> source = new TreeSet<>(Set.of("a"));
        Settings s = new Settings(5, 100, 2, 1, true, true, true, "commoner", source, "x <player>", "", "", "", "",
                "");
        source.add("b");
        assertEquals(Set.of("a"), s.promotableGroups());
        assertThrows(UnsupportedOperationException.class, () -> s.promotableGroups().add("c"));
    }

    @Test
    void invalid_values_fall_back() {
        Map<String, String> stored = new HashMap<>();
        stored.put("votesNeeded", "0");
        stored.put("validityDays", "abc");
        stored.put("xpBar", "maybe");
        List<String> warnings = new ArrayList<>();
        Settings s = Settings.fromMap(stored, warnings::add);
        assertEquals(5, s.votesNeeded());
        assertEquals(100, s.validityDays());
        assertTrue(s.xpBar());
        assertEquals(3, warnings.size());
        for (String name : List.of("votesNeeded", "validityDays", "xpBar")) {
            assertEquals(1, warnings.stream().filter(w -> w.contains(name)).count(),
                    "exactly one warning should name " + name + ": " + warnings);
        }
    }

    @Test
    void out_of_range_ints_fall_back_at_both_ends() {
        List<String> warnings = new ArrayList<>();
        Map<String, String> stored = new HashMap<>();
        stored.put("votesNeeded", "21");
        stored.put("validityDays", "366");
        stored.put("staffWeight", "6");
        stored.put("teamWeight", "-1");
        Settings s = Settings.fromMap(stored, warnings::add);
        assertEquals(Settings.defaults(), s);
        assertEquals(4, warnings.size());
    }

    @Test
    void range_edges_are_accepted() {
        Map<String, String> stored = new HashMap<>();
        stored.put("votesNeeded", "20");
        stored.put("validityDays", "365");
        stored.put("staffWeight", "1");
        stored.put("teamWeight", "5");
        Settings s = Settings.fromMap(stored, w -> fail(w));
        assertEquals(20, s.votesNeeded());
        assertEquals(365, s.validityDays());
        assertEquals(1, s.staffWeight());
        assertEquals(5, s.teamWeight());
    }

    @Test
    void booleans_accept_only_true_or_false_in_any_case() {
        assertFalse(Settings.fromMap(only("autoPromote", "FALSE"), w -> fail(w)).autoPromote());
        assertTrue(Settings.fromMap(only("autoPromote", "True"), w -> fail(w)).autoPromote());
        for (String bad : List.of("yes", "1", "on", "")) {
            List<String> warnings = new ArrayList<>();
            Settings s = Settings.fromMap(only("broadcastNetwork", bad), warnings::add);
            assertTrue(s.broadcastNetwork(), "falls back to the default for \"" + bad + "\"");
            assertEquals(1, warnings.size(), "\"" + bad + "\" should warn");
            assertTrue(warnings.get(0).contains("broadcastNetwork"));
        }
    }

    @Test
    void missing_and_null_values_give_defaults_without_warning() {
        assertEquals(Settings.defaults(), Settings.fromMap(Map.of(), w -> fail(w)));
        Map<String, String> nulls = new HashMap<>();
        for (String name : Settings.defaults().toMap().keySet()) {
            nulls.put(name, null);
        }
        assertEquals(Settings.defaults(), Settings.fromMap(nulls, w -> fail(w)));
    }

    @Test
    void strings_are_taken_as_stored() {
        Settings s = Settings.fromMap(Map.of("rulesUrl", "", "commonerGroup", " Member ",
                "guestInfoText", "plain"), w -> fail(w));
        assertEquals("", s.rulesUrl());
        assertEquals(" Member ", s.commonerGroup());
        assertEquals("plain", s.guestInfoText());
    }

    @Test
    void problems() {
        assertTrue(Settings.defaults().problems().isEmpty());
        assertFalse(texts(Settings.defaults(), "commoner", "", "").problems().isEmpty());
        assertFalse(texts(Settings.defaults(), "commoner", "", "lp user someone parent set x").problems().isEmpty());
        assertFalse(texts(Settings.defaults(), "  ", "", "lp user <player> parent set x").problems().isEmpty());
        assertFalse(texts(Settings.defaults(), "", "", "lp user <player> parent set x").problems().isEmpty());
        assertFalse(Settings.defaults().withNumbers(new SettingsForm(21, 100, 2, 1, true, true, true))
                .problems().isEmpty());
        assertFalse(Settings.defaults().withNumbers(new SettingsForm(5, 0, 2, 1, true, true, true))
                .problems().isEmpty());
        assertFalse(Settings.defaults().withNumbers(new SettingsForm(5, 100, 6, 1, true, true, true))
                .problems().isEmpty());
        assertFalse(Settings.defaults().withNumbers(new SettingsForm(5, 100, 2, 0, true, true, true))
                .problems().isEmpty());
    }

    @Test
    void problems_name_the_setting() {
        List<String> problems = texts(Settings.defaults(), "", "", "").problems();
        assertTrue(problems.stream().anyMatch(p -> p.contains("commonerGroup")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("promotionCommand")), problems.toString());
        assertTrue(Settings.defaults().withNumbers(new SettingsForm(21, 100, 2, 1, true, true, true))
                .problems().get(0).contains("votesNeeded"));
    }

    /** Defaults with only the three promotion strings replaced. */
    private static Settings texts(Settings base, String group, String groups, String command) {
        return base.withTexts(new TextsForm(group, groups, command, base.rulesUrl(), base.guideUrl(),
                base.guestInfoText(), base.welcomeText(), base.announceText()));
    }

    @Test
    void with_numbers() {
        Settings before = custom();
        Settings after = before.withNumbers(new SettingsForm(9, 30, 4, 5, true, true, true));
        assertEquals(9, after.votesNeeded());
        assertEquals(30, after.validityDays());
        assertEquals(4, after.staffWeight());
        assertEquals(5, after.teamWeight());
        assertTrue(after.autoPromote());
        assertTrue(after.xpBar());
        assertTrue(after.broadcastNetwork());
        assertEquals(before.commonerGroup(), after.commonerGroup());
        assertEquals(before.promotableGroups(), after.promotableGroups());
        assertEquals(before.promotionCommand(), after.promotionCommand());
        assertEquals(before.rulesUrl(), after.rulesUrl());
        assertEquals(before.guideUrl(), after.guideUrl());
        assertEquals(before.guestInfoText(), after.guestInfoText());
        assertEquals(before.welcomeText(), after.welcomeText());
        assertEquals(before.announceText(), after.announceText());
    }

    @Test
    void with_texts() {
        Settings before = custom();
        Settings after = before.withTexts(new TextsForm("member", "Badge_Z, pp_y", "grant <player>", "r2", "g2",
                "gi2", "w2", "an2"));
        assertEquals("member", after.commonerGroup());
        assertEquals(Set.of("badge_z", "pp_y"), after.promotableGroups());
        assertEquals("grant <player>", after.promotionCommand());
        assertEquals("r2", after.rulesUrl());
        assertEquals("g2", after.guideUrl());
        assertEquals("gi2", after.guestInfoText());
        assertEquals("w2", after.welcomeText());
        assertEquals("an2", after.announceText());
        assertEquals(before.votesNeeded(), after.votesNeeded());
        assertEquals(before.validityDays(), after.validityDays());
        assertEquals(before.staffWeight(), after.staffWeight());
        assertEquals(before.teamWeight(), after.teamWeight());
        assertEquals(before.autoPromote(), after.autoPromote());
        assertEquals(before.xpBar(), after.xpBar());
        assertEquals(before.broadcastNetwork(), after.broadcastNetwork());
    }
}
