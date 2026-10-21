package com.mcmiddleearth.commonervote.service;

/**
 * Runs a command as the server console. The promotion command goes through this, so the services can be tested with
 * a recording one; the plugin's runner dispatches it to the console sender.
 * <p>
 * Called on the main thread.
 */
@FunctionalInterface
public interface CommandRunner {

    void runConsole(String command);
}
