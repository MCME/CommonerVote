package com.mcmiddleearth.commonervote.service;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;

/**
 * How the player-facing services carry the result of asynchronous work back to the main thread, and what they do for a
 * sender who left in the meantime: a player who has gone offline is told nothing, and a failed future is answered
 * with {@link Messages#databaseDown()}. One place for that policy, so a change to it reaches every service.
 * <p>
 * {@link PromotionService} keeps its own variants on purpose: it logs the failures that nobody is told about.
 */
final class Continuations {

    private final Executor main;

    /**
     * @param main runs work on the server's main thread
     */
    Continuations(Executor main) {
        this.main = Objects.requireNonNull(main);
    }

    /**
     * Runs {@code then} on the main thread with the outcome of {@code future}: the value, or the failure. An exception
     * thrown by {@code then} is left to the main thread's handler, which logs it.
     */
    <T> void onMain(CompletableFuture<T> future, BiConsumer<? super T, ? super Throwable> then) {
        future.whenComplete((value, error) -> main.execute(() -> then.accept(value, error)));
    }

    /**
     * Runs {@code then} on the main thread with the value of {@code future}, for a flow whose only result is something
     * shown to {@code who}. Nothing happens if {@code who} is a player who has left; if the future failed, {@code who}
     * is told the database is unreachable.
     */
    <T> void whenReady(CommandSender who, CompletableFuture<T> future, Consumer<? super T> then) {
        onMain(future, (value, error) -> {
            if (gone(who)) {
                return;
            }
            if (error != null) {
                who.sendMessage(Messages.databaseDown());
                return;
            }
            then.accept(value);
        });
    }

    /** Sends the message, unless the receiver is a player who has gone offline. */
    static void tell(CommandSender receiver, Component message) {
        if (!gone(receiver)) {
            receiver.sendMessage(message);
        }
    }

    private static boolean gone(CommandSender who) {
        return who instanceof Player p && !p.isOnline();
    }
}
