package com.mcmiddleearth.commonervote.ui;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.mcmiddleearth.commonervote.ui.spec.DialogSpec;
import com.mcmiddleearth.commonervote.ui.spec.DialogSpec.ButtonAction;
import com.mcmiddleearth.commonervote.ui.spec.DialogSpec.ButtonAction.Callback;
import com.mcmiddleearth.commonervote.ui.spec.DialogSpec.ButtonAction.Close;
import com.mcmiddleearth.commonervote.ui.spec.DialogSpec.ButtonAction.OpenUrl;
import com.mcmiddleearth.commonervote.ui.spec.DialogSpec.ButtonSpec;
import com.mcmiddleearth.commonervote.ui.spec.DialogSpec.DialogValues;
import com.mcmiddleearth.commonervote.ui.spec.DialogSpec.InputSpec;
import com.mcmiddleearth.commonervote.ui.spec.DialogSpec.InputSpec.NumberInput;
import com.mcmiddleearth.commonervote.ui.spec.DialogSpec.InputSpec.TextInput;
import com.mcmiddleearth.commonervote.ui.spec.DialogSpec.InputSpec.ToggleInput;
import com.mcmiddleearth.commonervote.ui.spec.DialogSpecs;
import com.mcmiddleearth.commonervote.ui.view.CandidateListView;
import com.mcmiddleearth.commonervote.ui.view.ConfirmView;
import com.mcmiddleearth.commonervote.ui.view.GuestInfoView;
import com.mcmiddleearth.commonervote.ui.view.ReviewDetailView;
import com.mcmiddleearth.commonervote.ui.view.ReviewListView;
import com.mcmiddleearth.commonervote.ui.view.SettingsView;
import com.mcmiddleearth.commonervote.ui.view.TextsView;
import com.mcmiddleearth.commonervote.ui.view.VoteView;
import com.mcmiddleearth.commonervote.ui.view.WelcomeView;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;

/**
 * Draws every screen as a Paper dialog: {@link DialogSpecs} decides what a screen holds, and this class only turns
 * that {@link DialogSpec} into a {@link Dialog} and shows it. MockBukkit cannot build Paper dialogs, so this class has
 * no unit test; the specs are tested instead, and the dialogs themselves are checked in game.
 * <p>
 * <b>The dialog.</b> Built anew for each showing: a base with the title, one plain message per body line and the
 * inputs, closable with Escape, and after-action {@link DialogBase.DialogAfterAction#CLOSE}, never
 * {@code WAIT_FOR_RESPONSE} (whose waiting screen the server cannot close). Its type is a multi-action dialog with the
 * spec's buttons, columns and exit button. Minecraft refuses a multi-action dialog without action buttons, so a spec
 * whose {@link DialogSpec#buttons()} is empty (the guest info without a guide link, say) becomes a notice whose only
 * button is the exit button. The Escape key runs the exit button's action too.
 * <p>
 * <b>Buttons.</b> A {@link Callback} is a custom click that can be used once, for five minutes. It does nothing when
 * the plugin has been disabled since (Paper keeps click callbacks after a disable, so a reload would otherwise leave
 * old buttons running against dead services) or when the clicker is not a player; the handler then gets the player
 * and the submitted values. An {@link OpenUrl} is a static open-URL click and a {@link Close} has no action, so the
 * button just closes the dialog. Dialog callbacks run on the main thread.
 * <p>
 * <b>Inputs.</b> A {@link NumberInput} is a slider with step 1 and the label {@code Votes needed: 5}; a
 * {@link TextInput} a text box, several lines high when multiline; a {@link ToggleInput} an on/off button. The specs
 * guarantee what Paper checks: valid keys, a slider value in range, a text that fits its box.
 */
public final class PaperDialogPresenter implements DialogPresenter {

    /** Wide enough for a 16-character name, the bar, {@code 100%} and {@code ✔ you} in one button. */
    private static final int LIST_BUTTON_WIDTH = 250;
    /** Minecraft's own button width, for buttons that sit side by side. */
    private static final int BUTTON_WIDTH = 150;
    private static final int BODY_WIDTH = 300;
    private static final int TEXT_WIDTH = 300;
    private static final int MULTILINE_HEIGHT = 100;

    private static final ClickCallback.Options ONE_CLICK = ClickCallback.Options.builder()
            .uses(1)
            .lifetime(Duration.ofMinutes(5))
            .build();

    private final Plugin plugin;

    public PaperDialogPresenter(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
    }

    @Override
    public void showCandidateList(Player viewer, CandidateListView view) {
        show(viewer, DialogSpecs.candidates(view));
    }

    @Override
    public void showVote(Player viewer, VoteView view) {
        show(viewer, DialogSpecs.vote(view));
    }

    @Override
    public void showGuestInfo(Player viewer, GuestInfoView view) {
        show(viewer, DialogSpecs.guestInfo(view));
    }

    @Override
    public void showWelcome(Player viewer, WelcomeView view) {
        show(viewer, DialogSpecs.welcome(view));
    }

    @Override
    public void showReviewList(Player viewer, ReviewListView view) {
        show(viewer, DialogSpecs.reviewList(view));
    }

    @Override
    public void showReviewDetail(Player viewer, ReviewDetailView view) {
        show(viewer, DialogSpecs.reviewDetail(view));
    }

    @Override
    public void showConfirm(Player viewer, ConfirmView view) {
        show(viewer, DialogSpecs.confirm(view));
    }

    @Override
    public void showSettings(Player viewer, SettingsView view) {
        show(viewer, DialogSpecs.settings(view));
    }

    @Override
    public void showTexts(Player viewer, TextsView view) {
        show(viewer, DialogSpecs.texts(view));
    }

    private void show(Player viewer, DialogSpec spec) {
        viewer.showDialog(dialog(spec));
    }

    private Dialog dialog(DialogSpec spec) {
        DialogBase base = DialogBase.builder(spec.title())
                .body(spec.body().stream().map(line -> DialogBody.plainMessage(line, BODY_WIDTH)).toList())
                .inputs(spec.inputs().stream().map(PaperDialogPresenter::input).toList())
                .canCloseWithEscape(true)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        ActionButton exit = button(spec.exit(), BUTTON_WIDTH, spec.inputs());
        DialogType type;
        if (spec.buttons().isEmpty()) {
            type = DialogType.notice(exit);
        } else {
            int width = spec.columns() == 1 ? LIST_BUTTON_WIDTH : BUTTON_WIDTH;
            type = DialogType.multiAction(spec.buttons().stream().map(b -> button(b, width, spec.inputs())).toList())
                    .columns(spec.columns())
                    .exitAction(exit)
                    .build();
        }
        return Dialog.create(b -> b.empty().base(base).type(type));
    }

    private static DialogInput input(InputSpec spec) {
        return switch (spec) {
            case TextInput t -> {
                TextDialogInput.Builder box = DialogInput.text(t.key(), t.label())
                        .width(TEXT_WIDTH)
                        .initial(t.initial())
                        .maxLength(t.maxLength());
                if (t.multiline()) {
                    box.multiline(TextDialogInput.MultilineOptions.create(null, MULTILINE_HEIGHT));
                }
                yield box.build();
            }
            case NumberInput n -> DialogInput.numberRange(n.key(), n.label(), n.min(), n.max())
                    .step(1f)
                    .initial((float) n.initial())
                    .labelFormat("%s: %s")
                    .build();
            case ToggleInput t -> DialogInput.bool(t.key(), t.label())
                    .initial(t.initial())
                    .build();
        };
    }

    private ActionButton button(ButtonSpec spec, int width, List<InputSpec> inputs) {
        return ActionButton.builder(spec.label())
                .tooltip(spec.tooltip())
                .width(width)
                .action(action(spec.action(), inputs))
                .build();
    }

    /** The click of a button; {@code null} for a button that only closes the dialog. */
    private DialogAction action(ButtonAction action, List<InputSpec> inputs) {
        return switch (action) {
            case Callback(var handler) -> DialogAction.customClick((response, audience) -> {
                if (plugin.isEnabled() && audience instanceof Player player) {
                    handler.accept(player, new ResponseValues(response, inputs));
                }
            }, ONE_CLICK);
            case OpenUrl(var url) -> DialogAction.staticAction(ClickEvent.openUrl(url));
            case Close() -> null;
        };
    }

    /**
     * The submitted values of a dialog. A value that did not come back reads as {@code ""} for a text box and as the
     * input's initial value for a slider or an on/off button.
     */
    private record ResponseValues(DialogResponseView response, List<InputSpec> inputs) implements DialogValues {

        @Override
        public String text(String key) {
            String text = response.getText(key);
            return text == null ? "" : text;
        }

        @Override
        public int number(String key) {
            Float value = response.getFloat(key);
            if (value != null) {
                return Math.round(value);
            }
            for (InputSpec in : inputs) {
                if (in instanceof NumberInput n && n.key().equals(key)) {
                    return n.initial();
                }
            }
            return 0;
        }

        @Override
        public boolean toggle(String key) {
            Boolean value = response.getBoolean(key);
            if (value != null) {
                return value;
            }
            for (InputSpec in : inputs) {
                if (in instanceof ToggleInput t && t.key().equals(key)) {
                    return t.initial();
                }
            }
            return false;
        }
    }
}
