package com.mcmiddleearth.commonervote.ui.spec;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Consumer;

import org.bukkit.entity.Player;

import com.mcmiddleearth.commonervote.domain.IntRange;
import com.mcmiddleearth.commonervote.domain.Settings;
import com.mcmiddleearth.commonervote.domain.SettingsForm;
import com.mcmiddleearth.commonervote.domain.TextsForm;
import com.mcmiddleearth.commonervote.ui.Texts;
import com.mcmiddleearth.commonervote.ui.spec.DialogSpec.ButtonAction;
import com.mcmiddleearth.commonervote.ui.spec.DialogSpec.ButtonAction.Callback;
import com.mcmiddleearth.commonervote.ui.spec.DialogSpec.ButtonAction.Close;
import com.mcmiddleearth.commonervote.ui.spec.DialogSpec.ButtonAction.OpenUrl;
import com.mcmiddleearth.commonervote.ui.spec.DialogSpec.ButtonSpec;
import com.mcmiddleearth.commonervote.ui.spec.DialogSpec.InputSpec;
import com.mcmiddleearth.commonervote.ui.spec.DialogSpec.InputSpec.NumberInput;
import com.mcmiddleearth.commonervote.ui.spec.DialogSpec.InputSpec.TextInput;
import com.mcmiddleearth.commonervote.ui.spec.DialogSpec.InputSpec.ToggleInput;
import com.mcmiddleearth.commonervote.ui.view.CandidateListView;
import com.mcmiddleearth.commonervote.ui.view.ConfirmView;
import com.mcmiddleearth.commonervote.ui.view.GuestInfoView;
import com.mcmiddleearth.commonervote.ui.view.ReviewDetailView;
import com.mcmiddleearth.commonervote.ui.view.ReviewListView;
import com.mcmiddleearth.commonervote.ui.view.SettingsView;
import com.mcmiddleearth.commonervote.ui.view.TextsView;
import com.mcmiddleearth.commonervote.ui.view.VoteView;
import com.mcmiddleearth.commonervote.ui.view.WelcomeView;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * The {@link DialogSpec} of every screen, built from its view: the wording, the order and the clicks.
 * <p>
 * <b>Player text stays text.</b> Player names, voter names, vote reasons and the confirmation title and question (which
 * contain names) go in with {@link Component#text}, never through MiniMessage, so a name or reason that looks like a
 * tag is shown as typed. Only the guest info and welcome texts, which the views hand over already parsed from what an
 * administrator wrote, carry formatting.
 * <p>
 * <b>Stored values always fit.</b> A slider starts at the stored number clamped to its range, and a text box at the
 * stored text cut to its limit, so settings that were edited by hand never make Paper refuse a dialog. A link that
 * Minecraft cannot open gets no button rather than breaking the dialog.
 */
public final class DialogSpecs {

    /** The longest reason, the width of the {@code reason} column. */
    static final int REASON_LENGTH = 150;
    /** The longest group list, promotion command or link. */
    static final int SHORT_TEXT_LENGTH = 256;
    /** The longest guest info, welcome or announcement text. */
    static final int LONG_TEXT_LENGTH = 1024;
    /** How long the buttons of the settings dialogs work: editing the long texts can take a while. */
    static final Duration EDITING_CLICK_LIFETIME = Duration.ofMinutes(30);

    private static final ButtonSpec CLOSE = closing("Close");
    private static final ButtonSpec CANCEL = closing("Cancel");

    private DialogSpecs() {
    }

    // ---- the team ----

    /** The guests online on this server, one button each, which opens the vote for that guest. */
    public static DialogSpec candidates(CandidateListView view) {
        List<ButtonSpec> buttons = view.rows().stream()
                .map(row -> new ButtonSpec(row.votedByViewer()
                        ? progress(row.name(), row.percent()).append(Component.text(" ✔ you", NamedTextColor.GREEN))
                        : progress(row.name(), row.percent()), null, click(row.onClick())))
                .toList();
        return new DialogSpec(text("Commoner votes"), List.of(text("Guests on this server. Click one to vote.")),
                List.of(), buttons, CLOSE, 1);
    }

    /**
     * The vote for one guest: their progress, how long votes last and how a staff vote compares, and a reason box.
     * Without a valid vote of the viewer the button casts one; with one, it renews it and a second one withdraws it.
     */
    public static DialogSpec vote(VoteView view) {
        List<Component> body = new ArrayList<>();
        body.add(text("Progress: " + view.percent() + "% · " + view.points() + " of "
                + count(view.votesNeeded(), "vote")));
        body.add(Texts.bar(view.percent()));
        view.votedDaysAgo().ifPresent(days -> body.add(text("You voted " + ago(days))));
        body.add(text("Votes last " + count(view.validityDays(), "day") + ". "
                + weights(view.staffWeight(), view.teamWeight())));

        TextInput reason = new TextInput("reason", text("Reason (optional)"), Texts.fit(view.reason(), REASON_LENGTH),
                REASON_LENGTH, false);
        Callback cast = new Callback((p, values) -> view.onCast().accept(p, values.text(reason.key())));
        List<ButtonSpec> buttons = new ArrayList<>();
        buttons.add(button(view.votedDaysAgo().isPresent() ? "Renew vote" : "Cast vote", cast));
        view.onWithdraw().ifPresent(withdraw -> buttons.add(button("Withdraw my vote", click(withdraw))));
        return new DialogSpec(text("Vote for " + view.name()), body, List.of(reason), buttons, CANCEL, 2);
    }

    // ---- the guest ----

    /** What a guest sees on {@code /vote}: how the vote works, their progress, and the building guide if set. */
    public static DialogSpec guestInfo(GuestInfoView view) {
        return new DialogSpec(text("Becoming a Commoner"),
                List.of(view.infoText(), text("Your progress: " + view.percent() + "%"), Texts.bar(view.percent())),
                List.of(), linkButton("Building guide", view.guideUrl()), CLOSE, 1);
    }

    /** The welcome of a new Commoner, with the rules a click away. */
    public static DialogSpec welcome(WelcomeView view) {
        return new DialogSpec(Component.text("★ Welcome, Commoner! ★", NamedTextColor.GOLD),
                List.of(view.welcomeText()), List.of(), linkButton("Read the rules", view.rulesUrl()),
                closing("Let's build"), 1);
    }

    // ---- review ----

    /**
     * The candidates, one button each, which opens their votes. A candidate whose auto-promotion a LuckPerms group
     * blocks is marked, and the tooltip names the group.
     */
    public static DialogSpec reviewList(ReviewListView view) {
        List<ButtonSpec> buttons = view.rows().stream()
                .map(row -> new ButtonSpec(row.blockedByGroup().isPresent()
                        ? progress(row.name(), row.percent()).append(Component.text(" (blocked)", NamedTextColor.RED))
                        : progress(row.name(), row.percent()),
                        row.blockedByGroup().map(group -> text("Auto-promotion blocked: has group " + group))
                                .orElse(null),
                        click(row.onClick())))
                .toList();
        return new DialogSpec(text("Commoner candidates"),
                List.of(text("The closest to 100% first. Click a name to see the votes.")), List.of(), buttons, CLOSE,
                1);
    }

    /**
     * Every valid vote for one candidate: a line with the voter, their age and how long they last, then the reason on
     * its own line when there is one. The promote and clear buttons appear only when the view offers them.
     */
    public static DialogSpec reviewDetail(ReviewDetailView view) {
        List<Component> body = new ArrayList<>();
        for (ReviewDetailView.Line vote : view.votes()) {
            body.add(text(vote.voterName() + (vote.staff() ? " (staff)" : "") + " · " + ago(vote.daysAgo()) + " · "
                    + endsIn(vote.daysLeft())));
            if (!vote.reason().isBlank()) {
                body.add(Component.text("\"" + vote.reason() + "\"", NamedTextColor.GRAY, TextDecoration.ITALIC));
            }
        }
        if (body.isEmpty()) {
            body.add(text("No valid votes."));
        }
        List<ButtonSpec> buttons = new ArrayList<>();
        view.onPromote().ifPresent(promote -> buttons.add(button("Promote now", click(promote))));
        view.onClear().ifPresent(clear -> buttons.add(button("Clear votes…", click(clear))));
        return new DialogSpec(text("Votes for " + view.name() + " · " + view.percent() + "%"), body, List.of(),
                buttons, new ButtonSpec(text("Back to candidates"), null, click(view.onBack())), 2);
    }

    /** "Are you sure?" before something that cannot be undone; cancelling only closes. */
    public static DialogSpec confirm(ConfirmView view) {
        return new DialogSpec(text(view.title()), List.of(text(view.question())), List.of(),
                List.of(button(view.confirmLabel(), click(view.onConfirm()))), CANCEL, 1);
    }

    // ---- settings ----

    /** The numbers and switches of the shared settings; a second button opens the texts and links. */
    public static DialogSpec settings(SettingsView view) {
        Settings s = view.current();
        List<InputSpec> inputs = List.of(
                number("votes_needed", "Votes needed", Settings.VOTES_NEEDED, s.votesNeeded()),
                number("validity_days", "Votes last (days)", Settings.VALIDITY_DAYS, s.validityDays()),
                number("staff_weight", "Staff vote counts as", Settings.STAFF_WEIGHT, s.staffWeight()),
                number("team_weight", "Team vote counts as", Settings.TEAM_WEIGHT, s.teamWeight()),
                new ToggleInput("auto_promote", text("Promote automatically"), s.autoPromote()),
                new ToggleInput("xp_bar", text("Progress on the XP bar"), s.xpBar()),
                new ToggleInput("broadcast_network", text("Announce to the whole network"), s.broadcastNetwork()));
        Callback save = new Callback((p, v) -> view.onSave().accept(p, new SettingsForm(v.number("votes_needed"),
                v.number("validity_days"), v.number("staff_weight"), v.number("team_weight"),
                v.toggle("auto_promote"), v.toggle("xp_bar"), v.toggle("broadcast_network"))));
        return new DialogSpec(text("Vote settings"), List.of(text("A save reaches every server within a minute.")),
                inputs, List.of(button("Save for all servers", save), button("Texts and links…",
                        click(view.onTexts()))), CANCEL, 2, EDITING_CLICK_LIFETIME);
    }

    /** The groups, the promotion command, the links and the three texts of the shared settings. */
    public static DialogSpec texts(TextsView view) {
        Settings s = view.current();
        List<InputSpec> inputs = List.of(
                shortText("commoner_group", "Commoner group", s.commonerGroup()),
                shortText("promotable_groups", "Groups that may be promoted (comma-separated)",
                        String.join(", ", new TreeSet<>(s.promotableGroups()))),
                shortText("promotion_command", "Promotion command", s.promotionCommand()),
                shortText("rules_url", "Rules link", s.rulesUrl()),
                shortText("guide_url", "Building guide link (empty for none)", s.guideUrl()),
                longText("guest_info_text", "Guest info text", s.guestInfoText()),
                longText("welcome_text", "Welcome text", s.welcomeText()),
                longText("announce_text", "Promotion announcement", s.announceText()));
        Callback save = new Callback((p, v) -> view.onSave().accept(p, new TextsForm(v.text("commoner_group"),
                v.text("promotable_groups"), v.text("promotion_command"), v.text("rules_url"), v.text("guide_url"),
                v.text("guest_info_text"), v.text("welcome_text"), v.text("announce_text"))));
        return new DialogSpec(text("Texts and links"), List.of(
                text("The promotion command may use <player> and <group>."),
                text("The three texts are MiniMessage and may use <player>, <votes_needed> and <validity_days>.")),
                inputs, List.of(button("Save for all servers", save)), closing("Back"), 1, EDITING_CLICK_LIFETIME);
    }

    // ---- helpers ----

    /**
     * The link a button may open, or empty when there is none or Minecraft cannot open it: it opens only http and
     * https links, and refuses the whole dialog otherwise. Paper sends a link without a scheme as https; so does this.
     * A link without a host name opens nothing useful and gets no button either.
     */
    static Optional<String> webLink(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        String link = url.strip();
        if (!link.contains("://")) {
            link = "https://" + link;
        }
        try {
            URI uri = new URI(link);
            String scheme = uri.getScheme();
            if (scheme != null && List.of("http", "https").contains(scheme.toLowerCase(Locale.ROOT))
                    && uri.getHost() != null) {
                return Optional.of(link);
            }
        } catch (URISyntaxException e) {
            // Not a link; no button.
        }
        return Optional.empty();
    }

    /** One button that opens the link, with the link as its tooltip; no button when there is no usable link. */
    private static List<ButtonSpec> linkButton(String label, String url) {
        return webLink(url).map(link -> List.of(new ButtonSpec(text(label), text(link), new OpenUrl(link))))
                .orElse(List.of());
    }

    /** {@code Bilbo_B ■■■■■ 80%}: the name as plain text, the bar and the percentage. */
    private static Component progress(String name, int percent) {
        return Component.text().append(text(name + " ")).append(Texts.bar(percent))
                .append(text(" " + percent + "%")).build();
    }

    /** How a staff vote compares with a team vote. */
    private static String weights(int staffWeight, int teamWeight) {
        if (staffWeight == 2 * teamWeight) {
            return "Staff votes count double.";
        }
        if (staffWeight == teamWeight) {
            return "Staff votes count the same as team votes.";
        }
        return "A staff vote counts as " + count(staffWeight, "vote") + ", a team vote as " + count(teamWeight, "vote")
                + ".";
    }

    private static String ago(int days) {
        return days == 0 ? "today" : count(days, "day") + " ago";
    }

    private static String endsIn(int days) {
        return days == 0 ? "ends today" : "ends in " + count(days, "day");
    }

    private static String count(int n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s");
    }

    private static NumberInput number(String key, String label, IntRange range, int stored) {
        return new NumberInput(key, text(label), range.min(), range.max(), range.clamp(stored));
    }

    private static TextInput shortText(String key, String label, String stored) {
        return new TextInput(key, text(label), Texts.fit(stored, SHORT_TEXT_LENGTH), SHORT_TEXT_LENGTH, false);
    }

    private static TextInput longText(String key, String label, String stored) {
        return new TextInput(key, text(label), Texts.fit(stored, LONG_TEXT_LENGTH), LONG_TEXT_LENGTH, true);
    }

    private static Callback click(Consumer<Player> onClick) {
        return new Callback((p, values) -> onClick.accept(p));
    }

    private static ButtonSpec button(String label, ButtonAction action) {
        return new ButtonSpec(text(label), null, action);
    }

    private static ButtonSpec closing(String label) {
        return new ButtonSpec(text(label), null, new Close());
    }

    private static Component text(String plain) {
        return Component.text(plain);
    }
}
