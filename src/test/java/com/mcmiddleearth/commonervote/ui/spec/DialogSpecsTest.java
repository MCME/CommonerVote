package com.mcmiddleearth.commonervote.ui.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import com.mcmiddleearth.commonervote.domain.Settings;
import com.mcmiddleearth.commonervote.domain.SettingsForm;
import com.mcmiddleearth.commonervote.domain.TextsForm;
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

import static com.mcmiddleearth.commonervote.ui.Texts.plain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DialogSpecsTest {

    private static final Pattern KEY = Pattern.compile("[a-z0-9_]+");

    private final Player clicker = mock(Player.class);

    // ---- helpers ----

    private static List<String> body(DialogSpec spec) {
        return spec.body().stream().map(c -> plain(c)).toList();
    }

    private static List<String> labels(List<ButtonSpec> buttons) {
        return buttons.stream().map(b -> plain(b.label())).toList();
    }

    private static ButtonSpec button(DialogSpec spec, String label) {
        return spec.buttons().stream().filter(b -> plain(b.label()).equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError("no button " + label + " in " + labels(spec.buttons())));
    }

    private static <T extends InputSpec> T input(DialogSpec spec, String key, Class<T> type) {
        return spec.inputs().stream().filter(i -> i.key().equals(key)).findFirst().map(type::cast)
                .orElseThrow(() -> new AssertionError("no input " + key));
    }

    /** Clicks the button, as the adapter would, with these submitted values. */
    private void click(ButtonSpec button, DialogValues values) {
        assertInstanceOf(Callback.class, button.action()).handler().accept(clicker, values);
    }

    private void click(ButtonSpec button) {
        click(button, values(Map.of()));
    }

    private static DialogValues values(Map<String, ?> submitted) {
        return new DialogValues() {
            @Override
            public String text(String key) {
                return (String) submitted.get(key);
            }

            @Override
            public int number(String key) {
                return (Integer) submitted.get(key);
            }

            @Override
            public boolean toggle(String key) {
                return (Boolean) submitted.get(key);
            }
        };
    }

    private static void assertClose(ButtonSpec button, String label) {
        assertEquals(label, plain(button.label()));
        assertInstanceOf(Close.class, button.action());
    }

    private static Settings withNumbers(int votesNeeded, int validityDays, int staffWeight, int teamWeight) {
        Settings d = Settings.defaults();
        return new Settings(votesNeeded, validityDays, staffWeight, teamWeight, d.autoPromote(), d.xpBar(),
                d.broadcastNetwork(), d.commonerGroup(), d.promotableGroups(), d.promotionCommand(), d.rulesUrl(),
                d.guideUrl(), d.guestInfoText(), d.welcomeText(), d.announceText());
    }

    private static VoteView newVote(Consumer<String> onCast) {
        return new VoteView("Bilbo_B", 80, 4, 5, 100, 2, 1, OptionalInt.empty(), "",
                (p, reason) -> onCast.accept(reason), Optional.empty());
    }

    private static Stream<DialogSpec> everySpec() {
        Settings s = Settings.defaults();
        Consumer<Player> nothing = p -> { };
        return Stream.of(
                DialogSpecs.candidates(new CandidateListView(List.of(
                        new CandidateListView.Row(UUID.randomUUID(), "Bilbo_B", 80, true, nothing)))),
                DialogSpecs.vote(newVote(r -> { })),
                DialogSpecs.guestInfo(new GuestInfoView(60, Component.text("info"), "https://example.org")),
                DialogSpecs.welcome(new WelcomeView(Component.text("welcome"), s.rulesUrl())),
                DialogSpecs.reviewList(new ReviewListView(List.of(new ReviewListView.Row(UUID.randomUUID(),
                        "Bilbo_B", 80, Optional.of("artist"), nothing)))),
                DialogSpecs.reviewDetail(new ReviewDetailView("Bilbo_B", 80, List.of(), Optional.of(nothing),
                        Optional.of(nothing), nothing)),
                DialogSpecs.confirm(new ConfirmView("Clear?", "Sure?", "Clear votes", nothing)),
                DialogSpecs.settings(new SettingsView(s, nothing, (p, f) -> { })),
                DialogSpecs.texts(new TextsView(s, (p, f) -> { })));
    }

    // ---- the team's candidate list ----

    @Test
    void candidates_spec() {
        List<Player> clicked = new ArrayList<>();
        DialogSpec spec = DialogSpecs.candidates(new CandidateListView(List.of(
                new CandidateListView.Row(UUID.randomUUID(), "Bilbo_B", 80, false, clicked::add),
                new CandidateListView.Row(UUID.randomUUID(), "Frodo", 40, true, p -> fail()))));

        assertEquals("Commoner votes", plain(spec.title()));
        assertEquals(List.of("Bilbo_B ■■■■■ 80%", "Frodo ■■■■■ 40% ✔ you"), labels(spec.buttons()));
        assertClose(spec.exit(), "Close");
        assertEquals(1, spec.columns());
        assertTrue(spec.inputs().isEmpty());

        click(spec.buttons().get(0));
        assertEquals(List.of(clicker), clicked);
    }

    private static void fail() {
        throw new AssertionError("the wrong row was clicked");
    }

    // ---- the vote dialog ----

    @Test
    void vote_spec_new() {
        AtomicReference<String> cast = new AtomicReference<>();
        DialogSpec spec = DialogSpecs.vote(newVote(cast::set));

        assertEquals("Vote for Bilbo_B", plain(spec.title()));
        assertTrue(body(spec).contains("Progress: 80% · 4 of 5 votes"), body(spec).toString());
        assertTrue(body(spec).contains("Votes last 100 days. Staff votes count double."), body(spec).toString());
        assertFalse(body(spec).stream().anyMatch(line -> line.startsWith("You voted")), body(spec).toString());

        TextInput reason = assertInstanceOf(TextInput.class, spec.inputs().getFirst());
        assertEquals(1, spec.inputs().size());
        assertEquals("reason", reason.key());
        assertEquals("", reason.initial());
        assertEquals(150, reason.maxLength());
        assertFalse(reason.multiline());

        assertEquals(List.of("Cast vote"), labels(spec.buttons()));
        assertClose(spec.exit(), "Cancel");

        click(button(spec, "Cast vote"), values(Map.of("reason", "great builds")));
        assertEquals("great builds", cast.get());
    }

    @Test
    void vote_spec_existing() {
        AtomicReference<String> cast = new AtomicReference<>();
        List<Player> withdrawn = new ArrayList<>();
        DialogSpec spec = DialogSpecs.vote(new VoteView("Bilbo_B", 80, 4, 5, 100, 2, 1, OptionalInt.of(12), "nice",
                (p, reason) -> cast.set(reason), Optional.of(withdrawn::add)));

        assertTrue(body(spec).contains("You voted 12 days ago"), body(spec).toString());
        assertEquals("nice", input(spec, "reason", TextInput.class).initial());
        assertEquals(List.of("Renew vote", "Withdraw my vote"), labels(spec.buttons()));
        assertClose(spec.exit(), "Cancel");

        click(button(spec, "Renew vote"), values(Map.of("reason", "nicer")));
        assertEquals("nicer", cast.get());
        click(button(spec, "Withdraw my vote"));
        assertEquals(List.of(clicker), withdrawn);
    }

    @Test
    void vote_spec_says_how_the_weights_compare() {
        DialogSpec tripled = DialogSpecs.vote(new VoteView("Bilbo_B", 0, 0, 1, 1, 3, 1, OptionalInt.of(1), "",
                (p, r) -> { }, Optional.empty()));
        assertTrue(body(tripled).contains("Progress: 0% · 0 of 1 vote"), body(tripled).toString());
        assertTrue(body(tripled).contains("You voted 1 day ago"), body(tripled).toString());
        assertTrue(body(tripled).contains("Votes last 1 day. A staff vote counts as 3 votes, a team vote as 1 vote."),
                body(tripled).toString());

        DialogSpec same = DialogSpecs.vote(new VoteView("Bilbo_B", 0, 0, 5, 30, 2, 2, OptionalInt.of(0), "",
                (p, r) -> { }, Optional.empty()));
        assertTrue(body(same).contains("You voted today"), body(same).toString());
        assertTrue(body(same).contains("Votes last 30 days. Staff votes count the same as team votes."),
                body(same).toString());
    }

    @Test
    void vote_spec_fits_a_long_stored_reason() {
        DialogSpec spec = DialogSpecs.vote(new VoteView("Bilbo_B", 0, 0, 5, 100, 2, 1, OptionalInt.of(3),
                "r".repeat(400), (p, r) -> { }, Optional.empty()));
        assertEquals("r".repeat(150), input(spec, "reason", TextInput.class).initial());
    }

    // ---- the guest's own dialog and the welcome ----

    @Test
    void guest_spec() {
        Component info = Component.text("Team members vote for guests.");
        DialogSpec none = DialogSpecs.guestInfo(new GuestInfoView(60, info, ""));

        assertEquals("Becoming a Commoner", plain(none.title()));
        assertTrue(none.body().contains(info));
        assertTrue(body(none).contains("Your progress: 60%"), body(none).toString());
        assertTrue(none.buttons().isEmpty());
        assertClose(none.exit(), "Close");

        DialogSpec linked = DialogSpecs.guestInfo(new GuestInfoView(60, info, "https://example.org/guide"));
        assertEquals(List.of("Building guide"), labels(linked.buttons()));
        assertEquals(new OpenUrl("https://example.org/guide"), linked.buttons().getFirst().action());
        assertClose(linked.exit(), "Close");
    }

    @Test
    void a_link_minecraft_cannot_open_gets_no_button() {
        Component info = Component.text("info");
        for (String bad : List.of("   ", "ftp://example.org/guide", "https://exa mple.org", "javascript:alert(1)")) {
            assertTrue(DialogSpecs.guestInfo(new GuestInfoView(0, info, bad)).buttons().isEmpty(), bad);
        }
        // Paper sends a link without a scheme as https; the button opens the same.
        assertEquals(new OpenUrl("https://example.org/guide"),
                DialogSpecs.guestInfo(new GuestInfoView(0, info, " example.org/guide ")).buttons().getFirst()
                        .action());
    }

    @Test
    void welcome_spec() {
        Component welcome = Component.text("You're now a Commoner.");
        DialogSpec spec = DialogSpecs.welcome(new WelcomeView(welcome, "https://www.mcmiddleearth.com/help/terms"));

        assertEquals("★ Welcome, Commoner! ★", plain(spec.title()));
        assertEquals(List.of(welcome), spec.body());
        assertEquals(List.of("Read the rules"), labels(spec.buttons()));
        assertEquals(new OpenUrl("https://www.mcmiddleearth.com/help/terms"), spec.buttons().getFirst().action());
        assertClose(spec.exit(), "Let's build");
    }

    // ---- review ----

    @Test
    void review_list_spec() {
        List<Player> clicked = new ArrayList<>();
        DialogSpec spec = DialogSpecs.reviewList(new ReviewListView(List.of(
                new ReviewListView.Row(UUID.randomUUID(), "Bilbo_B", 100, Optional.of("artist"), p -> fail()),
                new ReviewListView.Row(UUID.randomUUID(), "Frodo", 40, Optional.empty(), clicked::add))));

        assertEquals("Commoner candidates", plain(spec.title()));
        assertEquals(List.of("Bilbo_B ■■■■■ 100% (blocked)", "Frodo ■■■■■ 40%"), labels(spec.buttons()));
        assertEquals("Auto-promotion blocked: has group artist", plain(spec.buttons().get(0).tooltip()));
        assertNull(spec.buttons().get(1).tooltip());
        assertClose(spec.exit(), "Close");
        assertEquals(1, spec.columns());

        click(spec.buttons().get(1));
        assertEquals(List.of(clicker), clicked);
    }

    @Test
    void review_detail_spec() {
        List<String> clicked = new ArrayList<>();
        List<ReviewDetailView.Line> votes = List.of(
                new ReviewDetailView.Line("voter1", true, 12, 88, "nice"),
                new ReviewDetailView.Line("voter2", false, 1, 99, ""));
        DialogSpec all = DialogSpecs.reviewDetail(new ReviewDetailView("Bilbo_B", 80, votes,
                Optional.of(p -> clicked.add("promote")), Optional.of(p -> clicked.add("clear")),
                p -> clicked.add("back")));

        assertEquals("Votes for Bilbo_B · 80%", plain(all.title()));
        assertEquals(List.of("voter1 (staff) · 12 days ago · ends in 88 days", "\"nice\"",
                "voter2 · 1 day ago · ends in 99 days"), body(all));
        assertEquals(List.of("Promote now", "Clear votes…"), labels(all.buttons()));
        assertEquals("Back to candidates", plain(all.exit().label()));

        click(button(all, "Promote now"));
        click(button(all, "Clear votes…"));
        click(all.exit());
        assertEquals(List.of("promote", "clear", "back"), clicked);

        DialogSpec lookOnly = DialogSpecs.reviewDetail(new ReviewDetailView("Bilbo_B", 80, votes, Optional.empty(),
                Optional.empty(), p -> { }));
        assertTrue(lookOnly.buttons().isEmpty());
        assertEquals("Back to candidates", plain(lookOnly.exit().label()));

        DialogSpec clearOnly = DialogSpecs.reviewDetail(new ReviewDetailView("Bilbo_B", 80, votes,
                Optional.empty(), Optional.of(p -> { }), p -> { }));
        assertEquals(List.of("Clear votes…"), labels(clearOnly.buttons()));
    }

    @Test
    void review_detail_without_votes_says_so() {
        DialogSpec spec = DialogSpecs.reviewDetail(new ReviewDetailView("Bilbo_B", 0, List.of(), Optional.empty(),
                Optional.empty(), p -> { }));
        assertEquals(List.of("No valid votes."), body(spec));
    }

    @Test
    void confirm_spec() {
        List<Player> confirmed = new ArrayList<>();
        DialogSpec spec = DialogSpecs.confirm(new ConfirmView("Clear votes for Bilbo_B?",
                "This deletes all 3 votes for Bilbo_B. It can't be undone.", "Clear votes", confirmed::add));

        assertEquals("Clear votes for Bilbo_B?", plain(spec.title()));
        assertEquals(List.of("This deletes all 3 votes for Bilbo_B. It can't be undone."), body(spec));
        assertEquals(List.of("Clear votes"), labels(spec.buttons()));
        assertClose(spec.exit(), "Cancel");

        click(spec.buttons().getFirst());
        assertEquals(List.of(clicker), confirmed);
    }

    @Test
    void player_strings_are_never_parsed() {
        String evil = "<red><click:run_command:'/op Evil'>Evil";
        Consumer<Player> nothing = p -> { };

        DialogSpec vote = DialogSpecs.vote(new VoteView(evil, 0, 0, 5, 100, 2, 1, OptionalInt.of(1), evil,
                (p, r) -> { }, Optional.empty()));
        assertEquals("Vote for " + evil, plain(vote.title()));

        DialogSpec detail = DialogSpecs.reviewDetail(new ReviewDetailView(evil, 0,
                List.of(new ReviewDetailView.Line(evil, false, 1, 99, evil)), Optional.empty(), Optional.empty(),
                nothing));
        assertEquals("Votes for " + evil + " · 0%", plain(detail.title()));
        assertEquals(List.of(evil + " · 1 day ago · ends in 99 days", "\"" + evil + "\""), body(detail));

        DialogSpec confirm = DialogSpecs.confirm(new ConfirmView("Promote " + evil + " now?", evil + "?", "Promote",
                nothing));
        assertEquals("Promote " + evil + " now?", plain(confirm.title()));
        assertEquals(List.of(evil + "?"), body(confirm));

        DialogSpec list = DialogSpecs.candidates(new CandidateListView(List.of(
                new CandidateListView.Row(UUID.randomUUID(), evil, 0, false, nothing))));
        assertEquals(evil + " ■■■■■ 0%", plain(list.buttons().getFirst().label()));
        assertTrue(Stream.of(vote, detail, confirm, list).allMatch(spec -> noClickEvents(spec)),
                "a player string must not become a click event");
    }

    private static boolean noClickEvents(DialogSpec spec) {
        List<Component> all = new ArrayList<>(spec.body());
        all.add(spec.title());
        spec.buttons().forEach(b -> all.add(b.label()));
        return all.stream().allMatch(DialogSpecsTest::noClickEvent);
    }

    private static boolean noClickEvent(Component c) {
        return c.clickEvent() == null && c.children().stream().allMatch(DialogSpecsTest::noClickEvent);
    }

    // ---- settings ----

    @Test
    void settings_spec_clamps() {
        Settings stored = Settings.fromMap(Map.of("votesNeeded", "5"), warning -> { });
        DialogSpec spec = DialogSpecs.settings(new SettingsView(stored, p -> { }, (p, f) -> { }));

        NumberInput votesNeeded = input(spec, "votes_needed", NumberInput.class);
        assertEquals(1, votesNeeded.min());
        assertEquals(20, votesNeeded.max());
        assertEquals(5, votesNeeded.initial());
        assertEquals("Votes needed", plain(votesNeeded.label()));

        NumberInput validity = input(spec, "validity_days", NumberInput.class);
        assertEquals(1, validity.min());
        assertEquals(365, validity.max());
        assertEquals(100, validity.initial());

        assertEquals(List.of("votes_needed", "validity_days", "staff_weight", "team_weight", "auto_promote", "xp_bar",
                "broadcast_network"), spec.inputs().stream().map(InputSpec::key).toList());
        assertTrue(spec.inputs().stream().allMatch(i -> KEY.matcher(i.key()).matches()));

        DialogSpec outOfRange = DialogSpecs.settings(new SettingsView(withNumbers(0, 999, 9, -3), p -> { },
                (p, f) -> { }));
        assertEquals(1, input(outOfRange, "votes_needed", NumberInput.class).initial());
        assertEquals(365, input(outOfRange, "validity_days", NumberInput.class).initial());
        assertEquals(5, input(outOfRange, "staff_weight", NumberInput.class).initial());
        assertEquals(1, input(outOfRange, "team_weight", NumberInput.class).initial());
    }

    @Test
    void settings_spec_labels_and_buttons() {
        DialogSpec spec = DialogSpecs.settings(new SettingsView(Settings.defaults(), p -> { }, (p, f) -> { }));

        assertEquals(List.of("Votes needed", "Votes last (days)", "Staff vote counts as", "Team vote counts as",
                "Promote automatically", "Progress on the XP bar", "Announce to the whole network"),
                spec.inputs().stream().map(i -> plain(i.label())).toList());
        for (String weight : List.of("staff_weight", "team_weight")) {
            NumberInput n = input(spec, weight, NumberInput.class);
            assertEquals(List.of(1, 5), List.of(n.min(), n.max()), weight);
        }
        assertEquals(2, input(spec, "staff_weight", NumberInput.class).initial());
        assertTrue(input(spec, "auto_promote", ToggleInput.class).initial());
        assertEquals(List.of("Save for all servers", "Texts and links…"), labels(spec.buttons()));
        assertClose(spec.exit(), "Cancel");
    }

    @Test
    void settings_save_callback() {
        AtomicReference<SettingsForm> saved = new AtomicReference<>();
        List<Player> texts = new ArrayList<>();
        DialogSpec spec = DialogSpecs.settings(new SettingsView(Settings.defaults(), texts::add, (p, form) -> {
            assertSame(clicker, p);
            saved.set(form);
        }));

        click(button(spec, "Save for all servers"), values(Map.of("votes_needed", 10, "validity_days", 30,
                "staff_weight", 3, "team_weight", 2, "auto_promote", false, "xp_bar", true,
                "broadcast_network", false)));
        assertEquals(new SettingsForm(10, 30, 3, 2, false, true, false), saved.get());

        click(button(spec, "Texts and links…"));
        assertEquals(List.of(clicker), texts);
    }

    // ---- texts and links ----

    @Test
    void texts_spec_fits() {
        Settings d = Settings.defaults();
        Settings longWelcome = d.withTexts(new TextsForm(d.commonerGroup(), "builder,badge", d.promotionCommand(),
                d.rulesUrl(), d.guideUrl(), d.guestInfoText(), "w".repeat(2000), d.announceText()));
        DialogSpec spec = DialogSpecs.texts(new TextsView(longWelcome, (p, f) -> { }));

        TextInput welcome = input(spec, "welcome_text", TextInput.class);
        assertTrue(welcome.initial().length() <= welcome.maxLength());
        assertEquals("w".repeat(1024), welcome.initial());

        Set<String> longTexts = Set.of("guest_info_text", "welcome_text", "announce_text");
        assertEquals(List.of("commoner_group", "promotable_groups", "promotion_command", "rules_url", "guide_url",
                "guest_info_text", "welcome_text", "announce_text"),
                spec.inputs().stream().map(InputSpec::key).toList());
        for (InputSpec in : spec.inputs()) {
            TextInput text = assertInstanceOf(TextInput.class, in);
            assertTrue(KEY.matcher(text.key()).matches(), text.key());
            boolean isLong = longTexts.contains(text.key());
            assertEquals(isLong ? 1024 : 256, text.maxLength(), text.key());
            assertEquals(isLong, text.multiline(), text.key());
            assertTrue(text.initial().length() <= text.maxLength(), text.key());
        }
        assertEquals("badge, builder", input(spec, "promotable_groups", TextInput.class).initial());
        assertEquals(d.promotionCommand(), input(spec, "promotion_command", TextInput.class).initial());
        assertEquals(List.of("Save for all servers"), labels(spec.buttons()));
        assertClose(spec.exit(), "Back");
    }

    @Test
    void texts_save_callback() {
        AtomicReference<TextsForm> saved = new AtomicReference<>();
        DialogSpec spec = DialogSpecs.texts(new TextsView(Settings.defaults(), (p, form) -> saved.set(form)));

        click(button(spec, "Save for all servers"), values(Map.of("commoner_group", "citizen",
                "promotable_groups", "a, b", "promotion_command", "promote <player>", "rules_url", "https://r",
                "guide_url", "https://g", "guest_info_text", "guest", "welcome_text", "welcome",
                "announce_text", "announce")));
        assertEquals(new TextsForm("citizen", "a, b", "promote <player>", "https://r", "https://g", "guest", "welcome",
                "announce"), saved.get());
    }

    // ---- what Paper would refuse ----

    @Test
    void every_spec_has_valid_keys_and_fitting_initial_values() {
        everySpec().forEach(spec -> {
            assertTrue(spec.columns() >= 1);
            for (InputSpec in : spec.inputs()) {
                assertTrue(KEY.matcher(in.key()).matches(), in.key());
                switch (in) {
                    case TextInput t -> assertTrue(t.initial().length() <= t.maxLength(), t.key());
                    case NumberInput n -> assertTrue(n.min() <= n.initial() && n.initial() <= n.max(), n.key());
                    case ToggleInput t -> { }
                }
            }
        });
    }

    @Test
    void inputs_refuse_what_paper_would_refuse() {
        Component label = Component.text("x");
        assertThrows(IllegalArgumentException.class, () -> new TextInput("Reason", label, "", 10, false));
        assertThrows(IllegalArgumentException.class, () -> new TextInput("re-ason", label, "", 10, false));
        assertThrows(IllegalArgumentException.class, () -> new TextInput("", label, "", 10, false));
        assertThrows(IllegalArgumentException.class, () -> new TextInput("reason", label, "too long", 3, false));
        assertThrows(IllegalArgumentException.class, () -> new TextInput("reason", label, "", 0, false));
        assertThrows(IllegalArgumentException.class, () -> new NumberInput("n", label, 1, 5, 6));
        assertThrows(IllegalArgumentException.class, () -> new NumberInput("n", label, 1, 5, 0));
        assertThrows(IllegalArgumentException.class, () -> new NumberInput("n", label, 5, 1, 3));
        assertThrows(IllegalArgumentException.class, () -> new ToggleInput("N", label, true));
        ButtonSpec close = new ButtonSpec(label, null, new ButtonAction.Close());
        assertThrows(IllegalArgumentException.class,
                () -> new DialogSpec(label, List.of(), List.of(), List.of(), close, 0));
    }
}
