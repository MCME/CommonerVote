package com.mcmiddleearth.commonervote;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Not final, because MockBukkit subclasses the main class to load it in tests.
 */
public class CommonerVotePlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
    }
}
