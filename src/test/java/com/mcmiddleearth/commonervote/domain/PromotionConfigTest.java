package com.mcmiddleearth.commonervote.domain;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromotionConfigTest {

    private static final UUID BILBO = UUID.fromString("00000000-0000-0000-0000-00000000b11b");

    private static final String COMMAND = "lp user <player> parent set <group>";

    @Test
    void the_command_names_the_player_by_uuid_and_the_group() {
        PromotionConfig config = new PromotionConfig("lp user <player> parent add <group>", "citizen");

        assertEquals("lp user 00000000-0000-0000-0000-00000000b11b parent add citizen", config.commandFor(BILBO));
    }

    @Test
    void the_defaults_set_the_commoner_group() {
        assertEquals("lp user 00000000-0000-0000-0000-00000000b11b parent set commoner",
                PromotionConfig.defaults().commandFor(BILBO));
        assertEquals(List.of(), PromotionConfig.defaults().problems());
    }

    @Test
    void the_space_at_the_end_of_a_1_2_9_config_is_dropped() {
        PromotionConfig config = new PromotionConfig("lp user <player> parent set <group> ", " commoner ");

        assertEquals("lp user 00000000-0000-0000-0000-00000000b11b parent set commoner", config.commandFor(BILBO));
        assertEquals(List.of(), config.problems());
    }

    @Test
    void a_command_needs_the_player() {
        for (String bad : List.of("", "   ", "lp user someone parent set commoner")) {
            List<String> problems = new PromotionConfig(bad, "commoner").problems();
            assertEquals(1, problems.size(), "\"" + bad + "\": " + problems);
            assertTrue(problems.getFirst().contains("promotionCommand"), problems.toString());
        }
    }

    @Test
    void a_command_is_one_line() {
        List<String> problems = new PromotionConfig("lp user <player>\nparent set <group>", "commoner").problems();
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().contains("promotionCommand"), problems.toString());
    }

    @Test
    void the_line_break_at_the_end_of_a_yaml_block_is_dropped() {
        assertEquals(List.of(), new PromotionConfig("lp user <player> parent set <group>\n", "commoner").problems());
    }

    @Test
    void a_command_without_the_group_is_fine() {
        assertEquals(List.of(), new PromotionConfig("lp user <player> parent set commoner", "commoner").problems());
    }

    @Test
    void a_group_is_a_plain_name() {
        for (String good : List.of("commoner", "Commoner", "pp_team-2", "7")) {
            assertEquals(List.of(), new PromotionConfig(COMMAND, good).problems(), good);
        }
        for (String bad : List.of("", "  ", "two words", "two\nlines", "a;b", "a.b", "<player>", "citizén")) {
            List<String> problems = new PromotionConfig(COMMAND, bad).problems();
            assertEquals(1, problems.size(), "\"" + bad + "\": " + problems);
            assertTrue(problems.getFirst().contains("commonerGroupName"), problems.toString());
        }
    }

    @Test
    void every_problem_is_named() {
        assertEquals(2, new PromotionConfig("", "").problems().size());
    }
}
