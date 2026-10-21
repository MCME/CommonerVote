package com.mcmiddleearth.commonervote.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.mcmiddleearth.commonervote.domain.Settings;
import com.mcmiddleearth.commonervote.domain.SettingsForm;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import static com.mcmiddleearth.commonervote.ui.Texts.bar;
import static com.mcmiddleearth.commonervote.ui.Texts.fit;
import static com.mcmiddleearth.commonervote.ui.Texts.mini;
import static com.mcmiddleearth.commonervote.ui.Texts.plain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextsTest {

    private static List<TextColor> segmentColors(Component bar) {
        return bar.children().stream().map(Component::color).toList();
    }

    private static List<TextColor> segments(int green) {
        return IntStream.range(0, 5)
                .<TextColor>mapToObj(i -> i < green ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY)
                .toList();
    }

    @Test
    void bar_segments() {
        Component eighty = bar(80);
        assertEquals("■■■■■", plain(eighty));
        assertEquals(5, eighty.children().size());
        for (int i = 0; i < 4; i++) {
            assertEquals(NamedTextColor.GREEN, eighty.children().get(i).color(), "segment " + i);
        }
        assertEquals(NamedTextColor.DARK_GRAY, eighty.children().get(4).color());

        Component zero = bar(0);
        assertEquals("■■■■■", plain(zero));
        assertEquals(segments(0), segmentColors(zero));
    }

    @Test
    void bar_fills_by_rounding_a_fifth_of_the_percent() {
        // Math.round(percent / 20.0): halves round up.
        assertEquals(segments(0), segmentColors(bar(9)));
        assertEquals(segments(1), segmentColors(bar(10)));
        assertEquals(segments(2), segmentColors(bar(30)));
        assertEquals(segments(2), segmentColors(bar(49)));
        assertEquals(segments(3), segmentColors(bar(50)));
        assertEquals(segments(5), segmentColors(bar(100)));
    }

    @Test
    void bar_clamps_to_zero_through_five_segments() {
        assertEquals(segments(0), segmentColors(bar(-40)));
        assertEquals(segments(5), segmentColors(bar(250)));
        assertEquals(segments(5), segmentColors(bar(Integer.MAX_VALUE)));
        assertEquals(segments(0), segmentColors(bar(Integer.MIN_VALUE)));
        assertEquals(5, bar(250).children().size());
    }

    @Test
    void placeholders() {
        Settings defaults = Settings.defaults();
        String text = plain(mini(defaults.guestInfoText(), Texts.placeholders(defaults, "x")));
        assertTrue(text.contains("5 votes"), text);
        assertTrue(text.contains("100 days"), text);
    }

    @Test
    void placeholders_follow_the_settings() {
        Settings custom = Settings.defaults().withNumbers(new SettingsForm(7, 30, 2, 1, true, true, true));
        String text = plain(mini(custom.guestInfoText(), Texts.placeholders(custom, "x")));
        assertTrue(text.contains("7 votes"), text);
        assertTrue(text.contains("30 days"), text);
    }

    @Test
    void placeholders_supply_the_player_name() {
        Settings defaults = Settings.defaults();
        assertEquals("★ Bilbo_B has been promoted to Commoner!",
                plain(mini(defaults.announceText(), Texts.placeholders(defaults, "Bilbo_B"))));
    }

    @Test
    void player_name_cannot_inject_tags() {
        Settings defaults = Settings.defaults();
        Component announce = mini(defaults.announceText(), Texts.placeholders(defaults, "<red>Evil</red>"));
        assertEquals("★ <red>Evil</red> has been promoted to Commoner!", plain(announce));
        assertFalse(allColors(announce).contains(NamedTextColor.RED), "the name must not be parsed");
    }

    private static List<TextColor> allColors(Component c) {
        List<TextColor> colors = new ArrayList<>();
        if (c.color() != null) {
            colors.add(c.color());
        }
        for (Component child : c.children()) {
            colors.addAll(allColors(child));
        }
        return colors;
    }

    @Test
    void mini_parses_admin_tags() {
        Component c = mini("<red>hi</red> there");
        assertEquals("hi there", plain(c));
        assertTrue(allColors(c).contains(NamedTextColor.RED));
    }

    @Test
    void fit_trims_to_max_length() {
        assertEquals("abc", fit("abcdef", 3));
        assertEquals("abcdef", fit("abcdef", 6));
        assertEquals("abcdef", fit("abcdef", 100));
        assertEquals("", fit("abcdef", 0));
        assertEquals("", fit("", 5));
    }

    @Test
    void fit_null_is_empty() {
        assertEquals("", fit(null, 10));
    }

    @Test
    void fit_does_not_split_a_surrogate_pair() {
        String smile = new String(Character.toChars(0x1F600));
        String text = "ab" + smile + "cd";
        assertEquals("ab", fit(text, 3));
        assertEquals("ab" + smile, fit(text, 4));
        assertEquals("ab" + smile + "c", fit(text, 5));
        assertEquals("", fit(smile, 1));
    }
}
