package com.mcmiddleearth.commonervote.command;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.mcmiddleearth.commonervote.Permissions;
import com.mcmiddleearth.commonervote.service.ReviewService;
import com.mcmiddleearth.commonervote.service.SettingsService;
import com.mcmiddleearth.commonervote.service.VoteService;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * The {@code /vote} command tree, for Paper's Brigadier. Each subcommand calls one of the services; the tree only
 * decides who may use what and hands players and the console the right flow.
 * <p>
 * <b>Permissions.</b> A subcommand the sender may not use is cut from the tree for them through its {@code requires}:
 * it does not run, and the client is not sent it. A literal such as {@code review} also never falls through to
 * {@code /vote <player>} for a player called "review". The services check the permissions again, since their dialogs
 * can outlive them, except {@link VoteService#showProgressOf}, which only this tree guards. The {@code <player>}
 * suggestions check the same permissions, because Brigadier asks every child of a node for suggestions without
 * asking its {@code requires}.
 * <p>
 * <b>Players and the console.</b> The dialogs are for players. The console gets the plain-text review, clears and
 * promotes without a confirmation, and is answered with the help lines where only a player can do something. Help
 * lines carry no chat prefix.
 * <p>
 * <b>A disabled plugin.</b> Nothing guarantees that the tree goes away when the plugin is disabled, and the services'
 * database executor refuses work from then on. So every action and every suggestion first checks that the plugin is
 * still enabled, and does nothing (or suggests nothing) once it is not.
 */
public final class VoteCommand {

    /** The command's name; Paper adds {@code commonervote:vote} itself. */
    private static final String NAME = "vote";

    /** The description Paper shows for the command. */
    public static final String DESCRIPTION = "Vote for guests to become Commoner";

    private static final String PLAYER_ARGUMENT = "player";

    private static final Predicate<CommandSender> ANYONE = sender -> true;
    private static final Predicate<CommandSender> PLAYERS = sender -> sender instanceof Player;
    private static final Predicate<CommandSender> MAY_VOTE = holdsAny(Permissions.VOTE);
    private static final Predicate<CommandSender> MAY_SCORE_OTHERS = holdsAny(Permissions.SCORE_OTHER,
            Permissions.REVIEW);
    private static final Predicate<CommandSender> MAY_REVIEW = holdsAny(Permissions.REVIEW);
    private static final Predicate<CommandSender> MAY_CLEAR = holdsAny(Permissions.CLEAR);
    private static final Predicate<CommandSender> MAY_PROMOTE = holdsAny(Permissions.PROMOTE);
    private static final Predicate<CommandSender> MAY_CONFIG = holdsAny(Permissions.CONFIG);

    /** {@code /vote help}: one line per subcommand, shown to the senders who may use it, in this order. */
    private static final List<HelpLine> HELP = List.of(
            new HelpLine("/vote", "Pick a guest online here to vote for.", PLAYERS.and(MAY_VOTE)),
            new HelpLine("/vote <player>", "Vote for a guest, or renew your vote.", PLAYERS.and(MAY_VOTE)),
            new HelpLine("/vote withdraw <player>", "Withdraw your vote for a guest.", PLAYERS.and(MAY_VOTE)),
            new HelpLine("/vote score", "See how close you are to Commoner.", PLAYERS),
            new HelpLine("/vote score <player>", "See how close a player is to Commoner.", MAY_SCORE_OTHERS),
            new HelpLine("/vote review [player]", "See the candidates, or the votes for one of them.", MAY_REVIEW),
            new HelpLine("/vote clear <player>", "Delete every vote for a player.", MAY_CLEAR),
            new HelpLine("/vote promote <player>", "Make a player Commoner now.", MAY_PROMOTE),
            new HelpLine("/vote config", "Change the settings of every server.", PLAYERS.and(MAY_CONFIG)),
            new HelpLine("/vote help", "List the vote commands you can use.", ANYONE));

    private final VoteService votes;
    private final ReviewService review;
    private final SettingsService settings;
    private final BooleanSupplier enabled;

    /**
     * @param enabled whether the plugin is enabled now, read on every action and suggestion
     */
    public VoteCommand(VoteService votes, ReviewService review, SettingsService settings, BooleanSupplier enabled) {
        this.votes = Objects.requireNonNull(votes);
        this.review = Objects.requireNonNull(review);
        this.settings = Objects.requireNonNull(settings);
        this.enabled = Objects.requireNonNull(enabled);
    }

    /** A new {@code /vote} tree, to register with Paper. */
    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal(NAME)
                .executes(run(sender -> forPlayer(sender, votes::openMenu)))
                .then(player(MAY_VOTE, this::onlineGuests)
                        .executes(runNamed((sender, name) -> forPlayer(sender, p -> votes.openVote(p, name)))))
                .then(Commands.literal("withdraw")
                        .requires(source(MAY_VOTE))
                        .then(player(MAY_VOTE, this::onlinePlayers)
                                .executes(runNamed((sender, name) ->
                                        forPlayer(sender, p -> votes.withdraw(p, name))))))
                .then(Commands.literal("score")
                        .executes(run(sender -> forPlayer(sender, votes::showOwnProgress)))
                        .then(player(MAY_SCORE_OTHERS, this::onlinePlayers)
                                .executes(runNamed(votes::showProgressOf))))
                .then(Commands.literal("review")
                        .requires(source(MAY_REVIEW))
                        .executes(run(this::reviewAll))
                        .then(player(MAY_REVIEW, this::candidates)
                                .executes(runNamed(this::reviewOne))))
                .then(Commands.literal("clear")
                        .requires(source(MAY_CLEAR))
                        .then(player(MAY_CLEAR, this::candidates)
                                .executes(runNamed(this::clear))))
                .then(Commands.literal("promote")
                        .requires(source(MAY_PROMOTE))
                        .then(player(MAY_PROMOTE, this::candidates)
                                .executes(runNamed(this::promote))))
                .then(Commands.literal("config")
                        .requires(source(MAY_CONFIG))
                        .executes(run(sender -> forPlayer(sender, settings::open))))
                .then(Commands.literal("help")
                        .executes(run(this::help)))
                .build();
    }

    // ---- what the subcommands do ----

    private void reviewAll(CommandSender sender) {
        if (sender instanceof Player p) {
            review.openList(p);
        } else {
            review.printReview(sender, Optional.empty());
        }
    }

    private void reviewOne(CommandSender sender, String name) {
        if (sender instanceof Player p) {
            review.openDetail(p, name);
        } else {
            review.printReview(sender, Optional.of(name));
        }
    }

    private void clear(CommandSender sender, String name) {
        if (sender instanceof Player p) {
            review.confirmClear(p, name);
        } else {
            review.clear(sender, name);
        }
    }

    private void promote(CommandSender sender, String name) {
        if (sender instanceof Player p) {
            review.confirmPromote(p, name);
        } else {
            review.promote(sender, name);
        }
    }

    /** The help lines for this sender, without the chat prefix. */
    private void help(CommandSender sender) {
        for (HelpLine line : HELP) {
            if (line.shownTo().test(sender)) {
                sender.sendMessage(Component.textOfChildren(Component.text(line.usage(), NamedTextColor.GOLD),
                        Component.text(" - " + line.description())));
            }
        }
    }

    /** Runs a player's flow; anyone else (the console) gets the help lines instead. */
    private void forPlayer(CommandSender sender, Consumer<Player> flow) {
        if (sender instanceof Player p) {
            flow.accept(p);
        } else {
            help(sender);
        }
    }

    // ---- suggestions ----

    /** The names of the players online here whom the sender can see, that start with what is typed. */
    private CompletableFuture<Suggestions> onlinePlayers(CommandSender sender, SuggestionsBuilder builder) {
        return online(sender, builder, player -> true);
    }

    /**
     * {@link #onlinePlayers}, but only the guests, as on the team's guest list: not the sender, and nobody with
     * {@link Permissions#EXEMPT} or {@link Permissions#VOTE}.
     */
    private CompletableFuture<Suggestions> onlineGuests(CommandSender sender, SuggestionsBuilder builder) {
        UUID self = sender instanceof Player p ? p.getUniqueId() : null;
        return online(sender, builder, player -> !player.getUniqueId().equals(self)
                && !player.hasPermission(Permissions.EXEMPT) && !player.hasPermission(Permissions.VOTE));
    }

    /** The names of the players online here that pass {@code which}, whom the sender can see, that start as typed. */
    private static CompletableFuture<Suggestions> online(CommandSender sender, SuggestionsBuilder builder,
                                                         Predicate<Player> which) {
        String typed = builder.getRemainingLowerCase();
        for (Player online : Bukkit.getOnlinePlayers()) {
            boolean visible = !(sender instanceof Player viewer) || viewer.canSee(online);
            if (visible && which.test(online) && online.getName().toLowerCase(Locale.ROOT).startsWith(typed)) {
                builder.suggest(online.getName());
            }
        }
        return builder.buildFuture();
    }

    /** The names of the review candidates that start with what is typed; no names when they cannot be read. */
    private CompletableFuture<Suggestions> candidates(CommandSender sender, SuggestionsBuilder builder) {
        String typed = builder.getRemainingLowerCase();
        return review.candidateNames().thenApply(names -> {
            names.stream()
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed))
                    .forEach(builder::suggest);
            return builder.build();
        });
    }

    // ---- building blocks ----

    /**
     * The {@code <player>} argument, for senders that pass {@code may}, with suggestions for those senders only.
     */
    private RequiredArgumentBuilder<CommandSourceStack, String> player(Predicate<CommandSender> may,
                                                                       Suggester suggester) {
        SuggestionProvider<CommandSourceStack> suggestions = (context, builder) -> {
            CommandSender sender = context.getSource().getSender();
            return enabled.getAsBoolean() && may.test(sender)
                    ? suggester.suggest(sender, builder)
                    : builder.buildFuture();
        };
        return Commands.argument(PLAYER_ARGUMENT, StringArgumentType.word())
                .requires(source(may))
                .suggests(suggestions);
    }

    /** An action for the sender, which does nothing once the plugin is disabled. */
    private Command<CommandSourceStack> run(Consumer<CommandSender> action) {
        return context -> {
            if (!enabled.getAsBoolean()) {
                return 0;
            }
            action.accept(context.getSource().getSender());
            return Command.SINGLE_SUCCESS;
        };
    }

    /** An action for the sender and the typed {@code <player>}, which does nothing once the plugin is disabled. */
    private Command<CommandSourceStack> runNamed(BiConsumer<CommandSender, String> action) {
        return context -> {
            if (!enabled.getAsBoolean()) {
                return 0;
            }
            action.accept(context.getSource().getSender(), StringArgumentType.getString(context, PLAYER_ARGUMENT));
            return Command.SINGLE_SUCCESS;
        };
    }

    private static Predicate<CommandSourceStack> source(Predicate<CommandSender> check) {
        return source -> check.test(source.getSender());
    }

    private static Predicate<CommandSender> holdsAny(String... permissions) {
        return sender -> Arrays.stream(permissions).anyMatch(sender::hasPermission);
    }

    /** Suggests names for the sender, from what is typed so far. */
    @FunctionalInterface
    private interface Suggester {
        CompletableFuture<Suggestions> suggest(CommandSender sender, SuggestionsBuilder builder);
    }

    /** One line of {@code /vote help}. */
    private record HelpLine(String usage, String description, Predicate<CommandSender> shownTo) {
    }
}
