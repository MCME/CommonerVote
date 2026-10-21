package com.mcmiddleearth.commonervote;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what the filtered plugin.yml and config.yml on the classpath declare, since a wrong
 * api-version, depend or permission default only shows once the jar runs on a server.
 */
class PluginDescriptorTest {

    private PluginDescriptionFile description;

    @BeforeEach
    void setUp() throws IOException, InvalidDescriptionException {
        MockBukkit.mock();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(in, "plugin.yml is missing from the classpath");
            description = new PluginDescriptionFile(in);
        }
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void name_and_platform() {
        assertEquals("CommonerVote", description.getName());
        assertFalse(description.getVersion().contains("$"), "resource filtering left a placeholder in the version");
        assertEquals("26.2", description.getAPIVersion());
        assertEquals(List.of("LuckPerms"), description.getDepend());
        assertTrue(description.getLoadBefore().isEmpty(), "loadbefore must stay empty");
        assertTrue(description.getCommands().isEmpty(), "the commands are Brigadier, not plugin.yml");
    }

    @Test
    void permissions_and_defaults() {
        Map<String, PermissionDefault> expected = new LinkedHashMap<>();
        expected.put("commonervote.vote", PermissionDefault.OP);
        expected.put("commonervote.staff", PermissionDefault.OP);
        expected.put("commonervote.exempt", PermissionDefault.OP);
        expected.put("commonervote.score", PermissionDefault.TRUE);
        expected.put("commonervote.score.other", PermissionDefault.OP);
        expected.put("commonervote.review", PermissionDefault.OP);
        expected.put("commonervote.clear", PermissionDefault.OP);
        expected.put("commonervote.config", PermissionDefault.OP);
        expected.put("commonervote.promote", PermissionDefault.OP);
        expected.put("commonervote.*", PermissionDefault.OP);

        Map<String, PermissionDefault> actual = new LinkedHashMap<>();
        for (Permission permission : description.getPermissions()) {
            actual.put(permission.getName(), permission.getDefault());
        }
        assertEquals(expected.keySet(), actual.keySet(), "the declared permission nodes");
        expected.forEach((node, expectedDefault) ->
                assertEquals(expectedDefault, actual.get(node), "default of " + node));
    }

    @Test
    void staff_implies_vote() {
        Permission staff = permission("commonervote.staff");
        assertEquals(Boolean.TRUE, staff.getChildren().get("commonervote.vote"));
    }

    @Test
    void star_children() {
        Permission star = permission("commonervote.*");
        assertEquals(Set.of(
                        "commonervote.vote",
                        "commonervote.staff",
                        "commonervote.score",
                        "commonervote.score.other",
                        "commonervote.review",
                        "commonervote.clear",
                        "commonervote.config",
                        "commonervote.promote"),
                star.getChildren().keySet(), "commonervote.* grants everything except exempt");
        assertFalse(star.getChildren().containsValue(Boolean.FALSE), "every child of commonervote.* is granted");
    }

    @Test
    void default_config() throws IOException {
        YamlConfiguration config;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in, "config.yml is missing from the classpath");
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            config = YamlConfiguration.loadConfiguration(reader);
        }
        assertEquals("localhost", config.getString("database.ip"));
        assertEquals(3306, config.getInt("database.port"));
        assertEquals("development", config.getString("database.dbName"));
        assertEquals("development", config.getString("database.user"));
        assertEquals("development", config.getString("database.password"));
        assertTrue(config.getBoolean("useBungee"));
    }

    private Permission permission(String name) {
        return description.getPermissions().stream()
                .filter(p -> p.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no permission node " + name));
    }
}
