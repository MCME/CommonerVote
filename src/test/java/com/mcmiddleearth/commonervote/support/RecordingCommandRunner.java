package com.mcmiddleearth.commonervote.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.mcmiddleearth.commonervote.service.CommandRunner;

/**
 * A {@link CommandRunner} that runs nothing and remembers the commands it was given, in order. A test reads
 * {@link #commands()} back.
 */
public final class RecordingCommandRunner implements CommandRunner {

    private final List<String> commands = new CopyOnWriteArrayList<>();

    @Override
    public void runConsole(String command) {
        commands.add(command);
    }

    /** The commands run so far, oldest first. */
    public List<String> commands() {
        return List.copyOf(commands);
    }

    /** Forgets every command recorded so far. */
    public void clear() {
        commands.clear();
    }
}
