package com.mcmiddleearth.commonervote.ui;

import com.mcmiddleearth.commonervote.domain.Settings;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Text helpers shared by the dialogs and the chat messages.
 */
public final class Texts {

    private static final int BAR_SEGMENTS = 5;
    private static final String BAR_SEGMENT = "■";

    /** Colours and formatting, and nothing that acts or looks something up: no clicks, hovers or selectors. */
    private static final MiniMessage SETTING_TEXTS = MiniMessage.builder()
            .tags(TagResolver.resolver(StandardTags.color(), StandardTags.decorations(), StandardTags.gradient(),
                    StandardTags.rainbow(), StandardTags.newline(), StandardTags.reset()))
            .build();

    private Texts() {
    }

    /**
     * A progress bar of five segments. A segment is filled, in green, for every fifth of the percentage (rounded to
     * the nearest, halves up); the rest are dark gray. Below 0 or above 100 the bar is empty or full.
     */
    public static Component bar(int percent) {
        long filled = Math.max(0, Math.min(BAR_SEGMENTS, Math.round(percent / 20.0)));
        TextComponent.Builder bar = Component.text();
        for (int i = 0; i < BAR_SEGMENTS; i++) {
            bar.append(Component.text(BAR_SEGMENT, i < filled ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
        }
        return bar.build();
    }

    /**
     * Parses a text from the settings as MiniMessage, for colours and formatting only; any other tag stays in the
     * text as it was typed. A text it cannot parse (MiniMessage refuses the old section sign codes) shows as typed, so
     * that one stored text cannot stop the flow that shows it. Only for texts an administrator wrote: a player's name
     * goes in through a resolver such as {@link #placeholders}, never into the string.
     */
    public static Component mini(String miniMessage, TagResolver... r) {
        try {
            return SETTING_TEXTS.deserialize(miniMessage, r);
        } catch (ParsingException e) {
            return Component.text(miniMessage);
        }
    }

    /**
     * The tags the setting texts may use: {@code <player>}, {@code <votes_needed>} and {@code <validity_days>}.
     * They are inserted as plain text, so a player name that looks like a tag stays text.
     */
    public static TagResolver placeholders(Settings s, String playerName) {
        return TagResolver.resolver(
                Placeholder.unparsed("player", playerName),
                Placeholder.unparsed("votes_needed", String.valueOf(s.votesNeeded())),
                Placeholder.unparsed("validity_days", String.valueOf(s.validityDays())));
    }

    /** The text of the component without any formatting. */
    public static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /**
     * The text cut to at most {@code maxLength} characters (UTF-16 units, which is how Minecraft's text boxes
     * count); {@code null} gives {@code ""}. A character outside the basic plane is never cut in half.
     */
    public static String fit(String s, int maxLength) {
        if (s == null || maxLength <= 0) {
            return "";
        }
        if (s.length() <= maxLength) {
            return s;
        }
        int end = maxLength;
        if (Character.isHighSurrogate(s.charAt(end - 1)) && Character.isLowSurrogate(s.charAt(end))) {
            end--;
        }
        return s.substring(0, end);
    }
}
