package com.mcmiddleearth.commonervote.ui.spec;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;

/**
 * One screen as plain data: its title, the lines of its body, the inputs it asks for and what its buttons do.
 * {@link DialogSpecs} builds one for every view, and the Paper adapter turns it into a dialog. So everything a player
 * sees and every click can be tested without a server, which cannot build Paper dialogs in tests.
 * <p>
 * The records refuse what Paper refuses when it builds a dialog: an input key other than lowercase letters, digits and
 * {@code _}, a text longer than its text box, a slider value outside its range. A bad spec then fails in a unit test,
 * not in front of a player. Clamping and trimming stored values is {@link DialogSpecs}' job.
 *
 * @param title   the title at the top
 * @param body    the lines above the inputs, in order, each its own paragraph; immutable
 * @param inputs  the inputs, in order; immutable
 * @param buttons the action buttons, in order; immutable. Without any, the dialog is a notice whose only button is
 *                {@code exit}, since Minecraft refuses a button list without buttons.
 * @param exit    the button at the bottom, which the Escape key also runs
 * @param columns how many action buttons sit side by side, at least 1
 */
public record DialogSpec(Component title, List<Component> body, List<InputSpec> inputs, List<ButtonSpec> buttons,
                         ButtonSpec exit, int columns) {

    /** What Paper and this plugin allow as an input key. */
    private static final Pattern KEY = Pattern.compile("[a-z0-9_]+");

    public DialogSpec {
        Objects.requireNonNull(title, "title");
        body = List.copyOf(body);
        inputs = List.copyOf(inputs);
        buttons = List.copyOf(buttons);
        Objects.requireNonNull(exit, "exit");
        if (columns < 1) {
            throw new IllegalArgumentException("columns must be at least 1 but is " + columns);
        }
    }

    private static String checkKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("an input key must match " + KEY + " but is '" + key + "'");
        }
        return key;
    }

    /** An input of a dialog; its value comes back under {@link #key()}. */
    public sealed interface InputSpec {

        String key();

        Component label();

        /**
         * A text box.
         *
         * @param initial   the text it starts with, at most {@code maxLength} characters
         * @param maxLength the most characters it takes, at least 1
         * @param multiline whether it is a box of several lines rather than one line
         */
        record TextInput(String key, Component label, String initial, int maxLength, boolean multiline)
                implements InputSpec {

            public TextInput {
                checkKey(key);
                Objects.requireNonNull(label, "label");
                Objects.requireNonNull(initial, "initial");
                if (maxLength < 1) {
                    throw new IllegalArgumentException(key + ": maxLength must be at least 1 but is " + maxLength);
                }
                if (initial.length() > maxLength) {
                    throw new IllegalArgumentException(key + ": the initial text has " + initial.length()
                            + " characters, more than " + maxLength);
                }
            }
        }

        /**
         * A slider over the whole numbers from {@code min} to {@code max}, both included.
         *
         * @param initial where it starts, inside the range
         */
        record NumberInput(String key, Component label, int min, int max, int initial) implements InputSpec {

            public NumberInput {
                checkKey(key);
                Objects.requireNonNull(label, "label");
                if (min > max) {
                    throw new IllegalArgumentException(key + ": the range " + min + " to " + max + " is empty");
                }
                if (initial < min || initial > max) {
                    throw new IllegalArgumentException(key + ": " + initial + " lies outside " + min + " to " + max);
                }
            }
        }

        /** An on/off button. */
        record ToggleInput(String key, Component label, boolean initial) implements InputSpec {

            public ToggleInput {
                checkKey(key);
                Objects.requireNonNull(label, "label");
            }
        }
    }

    /**
     * A button.
     *
     * @param tooltip shown when the pointer rests on the button; {@code null} for none
     */
    public record ButtonSpec(Component label, Component tooltip, ButtonAction action) {

        public ButtonSpec {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(action, "action");
        }
    }

    /** What a button does. Every button closes the dialog first. */
    public sealed interface ButtonAction {

        /**
         * Calls back the server with the player who clicked and the values of the inputs. The adapter does nothing
         * when the plugin has been disabled since, or when the clicker is not a player.
         */
        record Callback(BiConsumer<Player, DialogValues> handler) implements ButtonAction {

            public Callback {
                Objects.requireNonNull(handler, "handler");
            }
        }

        /** Opens a web page in the player's browser, after Minecraft's own confirmation screen. */
        record OpenUrl(String url) implements ButtonAction {

            public OpenUrl {
                Objects.requireNonNull(url, "url");
            }
        }

        /** Only closes the dialog. */
        record Close() implements ButtonAction {
        }
    }

    /** The values of a dialog's inputs as submitted with a click, by input key. */
    public interface DialogValues {

        /** The text of a {@link InputSpec.TextInput}, {@code ""} when none came back. */
        String text(String key);

        /** The value of a {@link InputSpec.NumberInput}, its initial value when none came back. */
        int number(String key);

        /** The state of a {@link InputSpec.ToggleInput}, its initial state when none came back. */
        boolean toggle(String key);
    }
}
