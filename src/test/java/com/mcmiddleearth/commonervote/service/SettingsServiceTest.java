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
import java.util.logging.Level;
import java.util.logging.LogRecord;

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
import com.mcmiddleearth.commonervote.support.TestDatabase;
import com.mcmiddleearth.commonervote.ui.Texts;
import com.mcmiddleearth.commonervote.ui.view.SettingsView;
import com.mcmiddleearth.commonervote.ui.view.TextsView;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsServiceTest extends ServiceTestBase {

    private static final UUID VOTER = UUID.randomUUID();

    /** The numbers and switches the settings are shipped with. */
    private static final SettingsForm DEFAULT_NUMBERS = new SettingsForm(5, 100, 2, 1, true, true, true);

    /** Texts that are all valid and differ from the defaults in every field. */
    private static final TextsForm NEW_TEXTS = new TextsForm("Badge_Mod, guide ,",
            "https://example.org/rules", "https://example.org/guide", "Guests <votes_needed>", "Welcome!",
            "Hail <player>");

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

    @Test
    void open_without_settings_ever_loaded_says_the_database_is_down() throws SQLException {
        // Down since startup: the cache only has the defaults, and a save from them would overwrite every setting.
        neverLoadSettings();
        breakDatabase();
        PlayerMock elrond = admin("Elrond");
        presenter.clear();

        configuring.open(elrond);
        configuring.openTexts(elrond);

        assertEquals(Messages.databaseDown(), elrond.nextComponentMessage());
        assertEquals(Messages.databaseDown(), elrond.nextComponentMessage());
        assertNull(elrond.nextComponentMessage());
        assertNull(presenter.last(elrond, SettingsView.class), "no dialog filled with the defaults");
        assertNull(presenter.last(elrond, TextsView.class));
    }

    @Test
    void open_without_settings_ever_loaded_loads_them_first() throws SQLException {
        Settings theirs = Settings.defaults().withNumbers(new SettingsForm(8, 40, 3, 2, false, true, false));
        new SettingsStore(database).save(theirs);
        neverLoadSettings();
        PlayerMock elrond = admin("Elrond");

        configuring.open(elrond);

        assertEquals(theirs, settingsViewOf(elrond).current());
        assertTrue(settings.loaded());
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
    void a_second_save_builds_on_the_first() throws SQLException {
        PlayerMock elrond = admin("Elrond");

        configuring.save(elrond, numbers(6));
        configuring.saveTexts(elrond, NEW_TEXTS);

        Settings expected = Settings.defaults().withNumbers(numbers(6)).withTexts(NEW_TEXTS);
        assertEquals(expected, settings.get());
        assertEquals(expected, stored());
    }

    // ---- the log of what was saved ----

    /** The log lines about saved settings, oldest first. */
    private List<String> saveLog() {
        return logger.records(Level.INFO).stream().map(LogRecord::getMessage)
                .filter(m -> m.startsWith("Settings: ")).toList();
    }

    private static String who(PlayerMock admin) {
        return "Settings: " + admin.getName() + " (" + admin.getUniqueId() + ") ";
    }

    private static TextsForm welcoming(String welcomeText) {
        Settings d = Settings.defaults();
        return new TextsForm("", d.rulesUrl(), d.guideUrl(), d.guestInfoText(), welcomeText, d.announceText());
    }

    private void storeRow(String name, String value) throws SQLException {
        TestDatabase.exec(database,
                "UPDATE commonervote_settings SET value = '" + value + "' WHERE name = '" + name + "'");
    }

    @Test
    void a_save_logs_who_changed_which_setting_from_what_to_what() throws SQLException {
        PlayerMock elrond = admin("Elrond");

        configuring.save(elrond, new SettingsForm(7, 30, 2, 1, true, false, true));

        assertEquals(List.of(who(elrond) + "changed votesNeeded from '5' to '7'",
                who(elrond) + "changed validityDays from '100' to '30'",
                who(elrond) + "changed xpBar from 'true' to 'false'"), saveLog());
    }

    @Test
    void a_save_of_the_texts_logs_them_the_same_way() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        Settings d = Settings.defaults();

        configuring.saveTexts(elrond, new TextsForm("Badge_B, badge_a", d.rulesUrl(), "https://example.org/guide",
                d.guestInfoText(), d.welcomeText(), "Hail <player>"));

        assertEquals(List.of(who(elrond) + "changed promotableGroups from '' to 'badge_a,badge_b'",
                who(elrond) + "changed guideUrl from '' to 'https://example.org/guide'",
                who(elrond) + "changed announceText from '" + d.announceText() + "' to 'Hail <player>'"), saveLog());
    }

    @Test
    void a_save_that_changes_nothing_says_so() throws SQLException {
        PlayerMock elrond = admin("Elrond");

        configuring.save(elrond, DEFAULT_NUMBERS);

        assertEquals(List.of(who(elrond) + "saved without changing anything"), saveLog());
    }

    @Test
    void the_old_value_is_what_was_stored_and_not_what_this_server_had_cached() throws SQLException {
        // Another server saved this a moment ago, and this server has not read it yet.
        storeRow("validityDays", "33");
        PlayerMock elrond = admin("Elrond");

        configuring.save(elrond, DEFAULT_NUMBERS);

        assertEquals(List.of(who(elrond) + "changed validityDays from '33' to '100'"), saveLog());
    }

    @Test
    void a_setting_without_a_row_is_logged_as_not_set() throws SQLException {
        TestDatabase.exec(database, "DELETE FROM commonervote_settings WHERE name = 'validityDays'");
        PlayerMock elrond = admin("Elrond");

        configuring.save(elrond, DEFAULT_NUMBERS);

        assertEquals(List.of(who(elrond) + "changed validityDays from (not set) to '100'"), saveLog());
    }

    @Test
    void a_value_stays_on_one_line_of_the_log() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        String typed = "one\ntwo\r\n[Server] fake" + (char) 0x1B + "[31m" + (char) 0x85 + (char) 0x2028 + (char) 0x202E
                + "end";

        configuring.saveTexts(elrond, welcoming(typed));

        assertTrue(saveLog().getFirst().endsWith(" to 'one two  [Server] fake [31m   end'"), saveLog().getFirst());
        assertEquals(1, saveLog().size());
    }

    @Test
    void a_value_cannot_pass_for_the_end_of_another() throws SQLException {
        PlayerMock elrond = admin("Elrond");

        configuring.saveTexts(elrond, welcoming("a' to 'b"));
        configuring.saveTexts(elrond, welcoming("c\\' to 'd"));

        assertTrue(saveLog().get(0).endsWith(" to 'a\\' to \\'b'"), saveLog().get(0));
        assertEquals(who(elrond) + "changed welcomeText from 'a\\' to \\'b' to 'c\\\\\\' to \\'d'", saveLog().get(1));
    }

    @Test
    void a_section_sign_does_not_colour_the_log() throws SQLException {
        PlayerMock elrond = admin("Elrond");

        configuring.saveTexts(elrond, welcoming("§chi"));

        assertTrue(saveLog().getFirst().endsWith(" to ' chi'"), saveLog().getFirst());
    }

    @Test
    void a_value_as_long_as_a_text_box_takes_is_logged_whole() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        String longest = "w".repeat(1024);

        configuring.saveTexts(elrond, welcoming(longest));

        assertTrue(saveLog().getFirst().endsWith(" to '" + longest + "'"), saveLog().getFirst());
    }

    @Test
    void a_longer_value_is_cut_and_says_how_long_it_was() throws SQLException {
        PlayerMock elrond = admin("Elrond");

        configuring.saveTexts(elrond, welcoming("w".repeat(5000)));

        assertTrue(saveLog().getFirst().endsWith(" to '" + "w".repeat(1024) + "...' (5000 characters)"),
                saveLog().getFirst());
    }

    @Test
    void a_refused_save_logs_nothing() throws SQLException {
        PlayerMock elrond = admin("Elrond");

        configuring.save(elrond, numbers(25));

        assertEquals(List.of(), saveLog());
    }

    @Test
    void a_save_that_fails_logs_nothing() throws SQLException {
        PlayerMock elrond = admin("Elrond");
        breakDatabase();

        configuring.save(elrond, numbers(10));

        assertEquals(List.of(), saveLog());
    }

    @Test
    void the_log_does_not_wait_for_the_main_thread() throws SQLException {
        // A server that stops right after a save drops what is still on its way to the main thread.
        main = new QueueExecutor();
        build();
        PlayerMock elrond = admin("Elrond");

        configuring.save(elrond, numbers(10));

        assertEquals(List.of(who(elrond) + "changed votesNeeded from '5' to '10'"), saveLog());
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
