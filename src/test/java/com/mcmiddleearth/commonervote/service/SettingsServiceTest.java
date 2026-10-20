package com.mcmiddleearth.commonervote.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.permissions.PermissionAttachment;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.mcmiddleearth.commonervote.Permissions;
import com.mcmiddleearth.commonervote.domain.Settings;
import com.mcmiddleearth.commonervote.domain.SettingsForm;
import com.mcmiddleearth.commonervote.domain.TextsForm;
import com.mcmiddleearth.commonervote.storage.SettingsStore;
import com.mcmiddleearth.commonervote.support.QueueExecutor;
import com.mcmiddleearth.commonervote.support.ServiceTestBase;
import com.mcmiddleearth.commonervote.ui.Texts;
import com.mcmiddleearth.commonervote.ui.view.SettingsView;
import com.mcmiddleearth.commonervote.ui.view.TextsView;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SettingsServiceTest extends ServiceTestBase {

    private static final UUID VOTER = UUID.randomUUID();

    /** The numbers and switches the settings are shipped with. */
    private static final SettingsForm DEFAULT_NUMBERS = new SettingsForm(5, 100, 2, 1, true, true, true);

    /** Texts that are all valid and differ from the defaults in every field. */
    private static final TextsForm NEW_TEXTS = new TextsForm("member", "Badge_Mod, guide ,",
            "lp user <player> promote", "https://example.org/rules", "https://example.org/guide",
            "Guests <votes_needed>", "Welcome!", "Hail <player>");

    private PlayerMock admin(String name) {
        PlayerMock player = guest(name);
        player.addAttachment(plugin, Permissions.CONFIG, true);
        return player;
    }

    private static SettingsForm numbers(int votesNeeded) {
        return new SettingsForm(votesNeeded, 100, 2, 1, true, true, true);
    }

    /** What the settings table holds, read as another server would read it. */
    private Settings stored() throws SQLException {
        return Settings.fromMap(new SettingsStore(database).load(), warning -> {
            throw new IllegalArgumentException(warning);
        });
    }

    private Map<String, String> table() throws SQLException {
        return new SettingsStore(database).load();
    }

    /** Every stored vote weight, smallest first. */
    private List<Double> weights() throws SQLException {
        List<Double> weights = new ArrayList<>();
        try (Connection c = database.open();
             PreparedStatement ps = c.prepareStatement("SELECT weight FROM commonervote_votes ORDER BY weight");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                weights.add(rs.getDouble(1));
            }
        }
        return weights;
    }

    private SettingsView settingsViewOf(PlayerMock admin) {
        SettingsView view = presenter.last(admin, SettingsView.class);
        assertNotNull(view, "the settings dialog should be open");
        return view;
    }

    private TextsView textsViewOf(PlayerMock admin) {
        TextsView view = presenter.last(admin, TextsView.class);
        assertNotNull(view, "the texts dialog should be open");
        return view;
    }

    // ---- opening the dialogs ----

    @Test
    void open_presents_current() throws SQLException {
        PlayerMock elrond = admin("Elrond");

        configuring.open(elrond);

        assertEquals(settings.get(), settingsViewOf(elrond).current());
        assertEquals(Settings.defaults(), settingsViewOf(elrond).current());
        assertNull(elrond.nextComponentMessage());
    }

    @Test
    void open_texts_presents_current() throws SQLException {
        PlayerMock elrond = admin("Elrond");

        configuring.openTexts(elrond);

        assertEquals(settings.get(), textsViewOf(elrond).current());
        assertNull(elrond.nextComponentMessage());
    }

    @Test
    void open_loads_what_another_server_saved() throws SQLException {
        // Another backend saved after this one's cache was filled.
        Settings theirs = Settings.defaults().withNumbers(new SettingsForm(8, 40, 3, 2, false, true, false))
                .withTexts(NEW_TEXTS);
        new SettingsStore(database).save(theirs);
        assertEquals(Settings.defaults(), settings.get());
        PlayerMock elrond = admin("Elrond");

        configuring.open(elrond);
        configuring.openTexts(elrond);

        assertEquals(theirs, settingsViewOf(elrond).current());
        assertEquals(theirs, textsViewOf(elrond).current());
        assertEquals(theirs, settings.get(), "the reload also refreshed this server's cache");
    }

    @Test
    void a_save_after_open_keeps_what_another_server_saved() throws SQLException {
        // The numbers dialog saves only the numbers; the texts another server saved must survive it.
        Settings theirs = Settings.defaults().withTexts(NEW_TEXTS);
        new SettingsStore(database).save(theirs);
        PlayerMock elrond = admin("Elrond");

        configuring.open(elrond);
        settingsViewOf(elrond).onSave().accept(elrond, numbers(6));

        assertEquals(theirs.withNumbers(numbers(6)), stored());
    }

    @Test
    void open_falls_back_to_the_cache_when_the_reload_fails() throws SQLException {
        breakDatabase();
        Settings cached = Settings.defaults().withNumbers(numbers(9));
        settings.replace(cached);
        PlayerMock elrond = admin("Elrond");

        configuring.open(elrond);
        configuring.openTexts(elrond);

        assertEquals(cached, settingsViewOf(elrond).current());
        assertEquals(cached, textsViewOf(elrond).current());
        assertNull(elrond.nextComponentMessage(), "a stale dialog is better than none; no databaseDown here");
        assertEquals(cached, settings.get());
    }

    // ---- saving the numbers and switches ----

    @Test
    void save_numbers() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        UUID bilbo = remoteGuest("Bilbo_B");
        vote(VOTER, bilbo, 0.2, 1);
        vote(UUID.randomUUID(), bilbo, 0.4, 2);

        configuring.save(elrond, numbers(10));

        List<Double> weights = weights();
        assertEquals(2, weights.size());
        assertEquals(0.1, weights.get(0), 1e-9);
        assertEquals(0.2, weights.get(1), 1e-9);
        assertEquals(10, settings.get().votesNeeded());
        assertEquals(10, stored().votesNeeded());
        assertEquals(Messages.settingsSaved(), elrond.nextComponentMessage());
        assertNull(elrond.nextComponentMessage());
    }

    @Test
    void save_numbers_stores_every_field_and_leaves_the_texts() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        SettingsForm form = new SettingsForm(7, 30, 3, 2, false, false, false);
        Settings expected = Settings.defaults().withNumbers(form);

        configuring.save(elrond, form);

        assertEquals(expected, settings.get());
        assertEquals(expected, stored());
        assertEquals(Messages.settingsSaved(), elrond.nextComponentMessage());
    }

    @Test
    void save_numbers_keeps_the_weights_when_votes_needed_is_unchanged() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        vote(VOTER, remoteGuest("Bilbo_B"), 0.2, 1);

        configuring.save(elrond, new SettingsForm(5, 60, 2, 1, true, true, true));

        assertEquals(List.of(0.2), weights());
        assertEquals(60, stored().validityDays());
    }

    @Test
    void save_numbers_rejects_a_value_out_of_range() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        vote(VOTER, remoteGuest("Bilbo_B"), 0.2, 1);
        Map<String, String> before = table();

        configuring.save(elrond, numbers(25));

        assertEquals("[Vote] Not saved: votesNeeded must be between 1 and 20 but is 25.",
                Texts.plain(elrond.nextComponentMessage()));
        assertNull(elrond.nextComponentMessage());
        assertEquals(before, table());
        assertEquals(List.of(0.2), weights(), "the weights were not rescaled");
        assertEquals(Settings.defaults(), settings.get());
    }

    // ---- saving the texts ----

    @Test
    void save_texts() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        Settings expected = Settings.defaults().withTexts(NEW_TEXTS);

        configuring.saveTexts(elrond, NEW_TEXTS);

        assertEquals(expected, settings.get());
        assertEquals(expected, stored());
        assertEquals(Set.of("badge_mod", "guide"), stored().promotableGroups());
        assertEquals(Messages.settingsSaved(), elrond.nextComponentMessage());
        assertNull(elrond.nextComponentMessage());
    }

    @Test
    void save_texts_rejects_bad_command() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        Map<String, String> before = table();
        TextsForm bad = new TextsForm("commoner", "", "lp user bilbo parent set commoner", "", "", "a", "b", "c");

        configuring.saveTexts(elrond, bad);

        assertEquals(Messages.settingsRejected("promotionCommand must contain <player>."),
                elrond.nextComponentMessage());
        assertNull(elrond.nextComponentMessage());
        assertEquals(before, table());
        assertEquals(Settings.defaults(), settings.get());
    }

    @Test
    void save_texts_rejects_an_empty_group_and_names_every_problem() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        Map<String, String> before = table();
        TextsForm bad = new TextsForm("  ", "", "", "", "", "a", "b", "c");

        configuring.saveTexts(elrond, bad);

        assertEquals("[Vote] Not saved: commonerGroup must not be empty. promotionCommand must not be empty.",
                Texts.plain(elrond.nextComponentMessage()));
        assertEquals(before, table());
    }

    @Test
    void a_second_save_builds_on_the_first() throws SQLException {
        PlayerMock elrond = admin("Elrond");

        configuring.save(elrond, numbers(6));
        configuring.saveTexts(elrond, NEW_TEXTS);

        Settings expected = Settings.defaults().withNumbers(numbers(6)).withTexts(NEW_TEXTS);
        assertEquals(expected, settings.get());
        assertEquals(expected, stored());
    }

    // ---- the buttons of the dialogs ----

    @Test
    void the_buttons_open_the_texts_and_save() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        configuring.open(elrond);

        settingsViewOf(elrond).onTexts().accept(elrond);
        textsViewOf(elrond).onSave().accept(elrond, NEW_TEXTS);
        settingsViewOf(elrond).onSave().accept(elrond, numbers(4));

        assertEquals(Settings.defaults().withTexts(NEW_TEXTS).withNumbers(numbers(4)), stored());
        assertEquals(Messages.settingsSaved(), elrond.nextComponentMessage());
        assertEquals(Messages.settingsSaved(), elrond.nextComponentMessage());
        assertNull(elrond.nextComponentMessage());
    }

    // ---- permission ----

    @Test
    void save_requires_config() throws SQLException {
        PlayerMock nobody = guest("Nobody");
        Map<String, String> before = table();

        configuring.save(nobody, numbers(10));
        configuring.saveTexts(nobody, NEW_TEXTS);

        assertEquals(Messages.noPermission(), nobody.nextComponentMessage());
        assertEquals(Messages.noPermission(), nobody.nextComponentMessage());
        assertNull(nobody.nextComponentMessage());
        assertEquals(before, table());
        assertEquals(Settings.defaults(), settings.get());
    }

    @Test
    void open_requires_config() throws SQLException {
        PlayerMock nobody = guest("Nobody");
        presenter.clear();

        configuring.open(nobody);
        configuring.openTexts(nobody);

        assertEquals(Messages.noPermission(), nobody.nextComponentMessage());
        assertEquals(Messages.noPermission(), nobody.nextComponentMessage());
        assertNull(nobody.nextComponentMessage());
        assertNull(presenter.last(nobody, SettingsView.class));
        assertNull(presenter.last(nobody, TextsView.class));
    }

    @Test
    void permission_is_checked_again_when_a_dialog_button_is_pressed() throws SQLException {
        PlayerMock elrond = guest("Elrond");
        PermissionAttachment right = elrond.addAttachment(plugin, Permissions.CONFIG, true);
        configuring.open(elrond);
        SettingsView settingsView = settingsViewOf(elrond);
        configuring.openTexts(elrond);
        TextsView textsView = textsViewOf(elrond);
        Map<String, String> before = table();
        presenter.clear();
        right.unsetPermission(Permissions.CONFIG);

        settingsView.onTexts().accept(elrond);
        settingsView.onSave().accept(elrond, numbers(10));
        textsView.onSave().accept(elrond, NEW_TEXTS);

        assertEquals(Messages.noPermission(), elrond.nextComponentMessage());
        assertEquals(Messages.noPermission(), elrond.nextComponentMessage());
        assertEquals(Messages.noPermission(), elrond.nextComponentMessage());
        assertNull(elrond.nextComponentMessage());
        assertNull(presenter.last(elrond, TextsView.class));
        assertEquals(before, table());
        assertEquals(Settings.defaults(), settings.get());
    }

    // ---- the database is down ----

    @Test
    void database_down_on_save() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        Map<String, String> before = table();
        breakDatabase();

        configuring.save(elrond, numbers(10));
        configuring.saveTexts(elrond, NEW_TEXTS);

        assertEquals(Messages.databaseDown(), elrond.nextComponentMessage());
        assertEquals(Messages.databaseDown(), elrond.nextComponentMessage());
        assertNull(elrond.nextComponentMessage());
        assertEquals(Settings.defaults(), settings.get(), "the cache only follows a save that was stored");
        assertEquals(before, table());
    }

    // ---- an admin who quits while the work is under way ----

    @Test
    void quit_before_open_shows_nothing() throws SQLException {
        QueueExecutor queue = new QueueExecutor();
        main = queue;
        build();
        PlayerMock elrond = admin("Elrond");
        presenter.clear();

        configuring.open(elrond);
        configuring.openTexts(elrond);
        elrond.disconnect();

        assertDoesNotThrow(queue::runAll);
        assertNull(presenter.last(elrond, SettingsView.class));
        assertNull(presenter.last(elrond, TextsView.class));
        assertNull(elrond.nextComponentMessage());
    }

    @Test
    void quit_before_the_reload_failed_continuation_tells_nobody() throws SQLException {
        QueueExecutor queue = new QueueExecutor();
        main = queue;
        connections = () -> {
            throw new SQLException("Communications link failure");
        };
        build();
        PlayerMock elrond = admin("Elrond");
        presenter.clear();

        configuring.open(elrond);
        elrond.disconnect();

        assertDoesNotThrow(queue::runAll);
        assertNull(presenter.last(elrond, SettingsView.class));
        assertNull(elrond.nextComponentMessage());
    }

    @Test
    void quit_before_the_save_continuation_still_updates_the_cache() throws SQLException {
        QueueExecutor queue = new QueueExecutor();
        main = queue;
        build();
        PlayerMock elrond = admin("Elrond");

        configuring.save(elrond, numbers(10));
        elrond.disconnect();

        assertDoesNotThrow(queue::runAll);
        assertNull(elrond.nextComponentMessage());
        assertEquals(10, stored().votesNeeded());
        assertEquals(10, settings.get().votesNeeded(), "the settings are stored, so this server follows them");
    }
}
