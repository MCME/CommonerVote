package com.mcmiddleearth.commonervote.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import com.mcmiddleearth.commonervote.Permissions;
import com.mcmiddleearth.commonervote.domain.PromotionGuard;
import com.mcmiddleearth.commonervote.domain.Settings;
import com.mcmiddleearth.commonervote.domain.Vote;
import com.mcmiddleearth.commonervote.domain.VoteMath;
import com.mcmiddleearth.commonervote.network.Messenger;
import com.mcmiddleearth.commonervote.rank.RankService;
import com.mcmiddleearth.commonervote.service.PlayerNames.Target;
import com.mcmiddleearth.commonervote.storage.DbExecutor;
import com.mcmiddleearth.commonervote.storage.RecipientScore;
import com.mcmiddleearth.commonervote.storage.VoteStore;
import com.mcmiddleearth.commonervote.ui.DialogPresenter;
import com.mcmiddleearth.commonervote.ui.view.ConfirmView;
import com.mcmiddleearth.commonervote.ui.view.ReviewDetailView;
import com.mcmiddleearth.commonervote.ui.view.ReviewDetailView.Line;
import com.mcmiddleearth.commonervote.ui.view.ReviewListView;

import net.kyori.adventure.text.Component;

/**
 * What an admin (a Vala) sees and does on {@code /vote review}: the candidates, the votes for one candidate, and
 * clearing those votes or promoting the candidate by hand, each behind an "Are you sure?" confirmation. The console
 * gets the same in plain text, without the confirmation step ({@link #printReview}, {@link #clear(CommandSender,
 * String)}, {@link #promote(CommandSender, String)}).
 * <p>
 * A candidate is anyone with valid votes who is not recorded as promoted, online on this server or not, the closest
 * to a promotion first, at most 50 of them. The name shown for a player follows {@link PlayerNames}: the one this
 * server holds in memory, else the rank service's, else the player's UUID, which can also be typed back.
 * <p>
 * The public methods are called on the main thread. Database work runs on the {@link DbExecutor}, the rank service
 * answers on its own threads, and every continuation that touches Bukkit comes back through {@code main} and checks
 * that a player is still online before it tells them anything.
 * <p>
 * <b>Failures.</b> When a flow an admin started fails because the database or the rank service cannot be reached, the
 * admin is told {@link Messages#databaseDown()} and nothing else happens. The {@link DbExecutor} has already logged
 * the failure, so this class logs nothing. Tab completion never fails: {@link #candidateNames()} answers with no names.
 * <p>
 * <b>Permissions.</b> A dialog can outlive the permission it was opened with, so every method an admin can reach
 * checks the permission again and answers {@link Messages#noPermission()}: {@link Permissions#REVIEW} for looking,
 * {@link Permissions#CLEAR} and {@link Permissions#PROMOTE} for the two actions, also in the callbacks of the
 * confirmations. The console always has permission.
 */
public final class ReviewService {

    /** The most candidates that the list shows. */
    private static final int MAX_CANDIDATES = 50;

    /** Votes from the start of time on, that is every row of a recipient, the expired ones too. */
    private static final long ALL_VOTES = 0L;

    private final DbExecutor db;
    private final Executor main;
    private final VoteStore votes;
    private final SettingsCache settings;
    private final RankService ranks;
    private final PlayerNames names;
    private final Continuations continuations;
    private final Messenger messenger;
    private final DialogPresenter presenter;
    private final XpBarService xp;
    private final PromotionService promotion;
    private final LongSupplier clock;

    /**
     * @param main     runs work on the server's main thread
     * @param profiles the names Paper keeps in memory for players who are offline
     * @param clock    the time in epoch milliseconds
     */
    public ReviewService(DbExecutor db, Executor main, VoteStore votes, SettingsCache settings, RankService ranks,
                         ProfileCache profiles, Messenger messenger, DialogPresenter presenter, XpBarService xp,
                         PromotionService promotion, LongSupplier clock) {
        this.db = Objects.requireNonNull(db);
        this.main = Objects.requireNonNull(main);
        this.votes = Objects.requireNonNull(votes);
        this.settings = Objects.requireNonNull(settings);
        this.ranks = Objects.requireNonNull(ranks);
        this.names = new PlayerNames(ranks, profiles, main);
        this.continuations = new Continuations(main);
        this.messenger = Objects.requireNonNull(messenger);
        this.presenter = Objects.requireNonNull(presenter);
        this.xp = Objects.requireNonNull(xp);
        this.promotion = Objects.requireNonNull(promotion);
        this.clock = Objects.requireNonNull(clock);
    }

    // ---- the candidate list ----

    /**
     * {@code /vote review}: the list of candidates, or a chat line when nobody has valid votes. A click on a candidate
     * opens {@link #openDetail(Player, UUID, String)} for the player who clicked.
     */
    public void openList(Player admin) {
        if (!allowed(admin, Permissions.REVIEW)) {
            return;
        }
        continuations.whenReady(admin, candidates(), found -> {
            if (found.isEmpty()) {
                admin.sendMessage(Messages.noCandidates());
                return;
            }
            presenter.showReviewList(admin, new ReviewListView(found.stream()
                    .map(c -> new ReviewListView.Row(c.id(), c.name(), c.percent(), c.blockedByGroup(),
                            clicker -> openDetail(clicker, c.id(), c.name())))
                    .toList()));
        });
    }

    /**
     * The candidates in score order, each with the name to show and the group that keeps their auto-promotion from
     * running (see {@link PromotionGuard}). Names and groups are fetched together.
     */
    private CompletableFuture<List<Candidate>> candidates() {
        Settings s = settings.get();
        long since = VoteMath.validSince(clock.getAsLong(), s.validityDays());
        return db.supply(() -> votes.candidates(since, MAX_CANDIDATES))
                .thenComposeAsync(scores -> described(scores, s), main);
    }

    /** Runs on the main thread, since the names come from Bukkit. */
    private CompletableFuture<List<Candidate>> described(List<RecipientScore> scores, Settings s) {
        List<UUID> ids = scores.stream().map(RecipientScore::recipient).toList();
        CompletableFuture<Map<UUID, String>> shown = names.display(ids, UUID::toString);
        Map<UUID, CompletableFuture<Set<String>>> groups = new LinkedHashMap<>();
        for (UUID id : ids) {
            groups.put(id, ranks.directGroups(id));
        }
        CompletableFuture<?>[] all = Stream.concat(Stream.of(shown), groups.values().stream())
                .toArray(CompletableFuture<?>[]::new);
        return CompletableFuture.allOf(all).thenApply(done -> scores.stream()
                .map(score -> new Candidate(score.recipient(), shown.join().get(score.recipient()),
                        VoteMath.percent(score.score()),
                        PromotionGuard.check(groups.get(score.recipient()).join(), s.promotableGroups())
                                .blockingGroup()))
                .toList());
    }

    /**
     * The names of the candidates, in score order, for tab completion. Never fails: if the database or the rank
     * service cannot be reached, the answer is no names.
     */
    public CompletableFuture<List<String>> candidateNames() {
        long since = VoteMath.validSince(clock.getAsLong(), settings.get().validityDays());
        return db.supply(() -> votes.candidates(since, MAX_CANDIDATES))
                .thenComposeAsync(scores -> {
                    List<UUID> ids = scores.stream().map(RecipientScore::recipient).toList();
                    return names.display(ids, UUID::toString)
                            .thenApply(shown -> ids.stream().map(shown::get).toList());
                }, main)
                .exceptionally(error -> List.of());
    }

    // ---- the votes for one candidate ----

    /** {@code /vote review <player>}: the votes for the player with this name, found in any case, also if offline. */
    public void openDetail(Player admin, String typedName) {
        if (!allowed(admin, Permissions.REVIEW)) {
            return;
        }
        resolve(admin, typedName, target -> openDetail(admin, target.id(), target.name()));
    }

    /**
     * Shows every valid vote for this player, newest first, with the buttons the admin may use: promote now (with
     * {@link Permissions#PROMOTE}), clear the votes (with {@link Permissions#CLEAR}) and back to the list.
     *
     * @param name the candidate's name as shown to players
     */
    public void openDetail(Player admin, UUID candidate, String name) {
        if (!allowed(admin, Permissions.REVIEW)) {
            return;
        }
        boolean mayPromote = has(admin, Permissions.PROMOTE);
        boolean mayClear = has(admin, Permissions.CLEAR);
        continuations.whenReady(admin, detail(candidate), detail ->
                presenter.showReviewDetail(admin, new ReviewDetailView(name, detail.percent(), detail.lines(),
                        mayPromote ? Optional.of(clicker -> confirmPromote(clicker, candidate, name))
                                : Optional.empty(),
                        mayClear ? Optional.of(clicker -> confirmClear(clicker, candidate, name))
                                : Optional.empty(),
                        this::openList)));
    }

    /** The valid votes for a candidate, newest first, with the voters' names, and the candidate's percentage. */
    private CompletableFuture<Detail> detail(UUID candidate) {
        Settings s = settings.get();
        long now = clock.getAsLong();
        long since = VoteMath.validSince(now, s.validityDays());
        return db.supply(() -> votes.validVotesFor(candidate, since)).thenComposeAsync(valid ->
                names.display(valid.stream().map(Vote::voter).toList(), UUID::toString).thenApply(shown ->
                        new Detail(VoteMath.percent(VoteMath.score(valid, since)), valid.stream()
                                .map(v -> new Line(shown.get(v.voter()),
                                        VoteMath.isStaffWeight(v.weight(), s.staffWeight(), s.votesNeeded()),
                                        VoteMath.daysAgo(v.timestamp(), now),
                                        VoteMath.daysLeft(v.timestamp(), now, s.validityDays()), v.reason()))
                                .toList())), main);
    }

    // ---- clearing votes ----

    /** {@code /vote clear <player>}: asks the admin to confirm clearing the votes of the player with this name. */
    public void confirmClear(Player admin, String typedName) {
        if (!allowed(admin, Permissions.CLEAR)) {
            return;
        }
        resolve(admin, typedName, target -> confirmClear(admin, target.id(), target.name()));
    }

    /**
     * Asks the admin to confirm clearing every vote for this player: a dialog that says how many rows go, the expired
     * ones included, since all of them are deleted. Nothing is changed until the admin confirms.
     *
     * @param name the candidate's name as shown to players
     */
    public void confirmClear(Player admin, UUID candidate, String name) {
        if (!allowed(admin, Permissions.CLEAR)) {
            return;
        }
        continuations.whenReady(admin, db.supply(() -> votes.validVotesFor(candidate, ALL_VOTES).size()), count ->
                presenter.showConfirm(admin, new ConfirmView("Clear votes for " + name + "?",
                        "This deletes all " + count + " votes for " + name + ". It can't be undone.", "Clear votes",
                        clicker -> clear(clicker, candidate, name))));
    }

    /** The console's {@code clear <player>}, and the same for a player typed by name. No confirmation. */
    public void clear(CommandSender admin, String typedName) {
        if (!allowed(admin, Permissions.CLEAR)) {
            return;
        }
        resolve(admin, typedName, target -> clear(admin, target.id(), target.name()));
    }

    /**
     * Deletes every vote for this player, valid or not, and tells the admin how many. The candidate then sees their
     * XP bar empty: at once when they are online here, through the {@link Messenger} on the server they are on
     * otherwise. Once the votes are gone, that part goes on even if the admin has quit.
     *
     * @param name the candidate's name as shown to players
     */
    public void clear(CommandSender admin, UUID candidate, String name) {
        if (!allowed(admin, Permissions.CLEAR)) {
            return;
        }
        continuations.onMain(db.supply(() -> votes.clear(candidate)), (count, error) -> {
            if (error != null) {
                Continuations.tell(admin, Messages.databaseDown());
                return;
            }
            Continuations.tell(admin, Messages.votesCleared(name, count));
            Player recipient = Bukkit.getPlayer(candidate);
            if (recipient == null) {
                messenger.notifyRecipient(name, Messenger.Update.UPDATE);
            } else {
                xp.refresh(recipient, 0.0);
            }
        });
    }

    // ---- promoting by hand ----

    /** {@code /vote promote <player>}: asks the admin to confirm promoting the player with this name. */
    public void confirmPromote(Player admin, String typedName) {
        if (!allowed(admin, Permissions.PROMOTE)) {
            return;
        }
        resolve(admin, typedName, target -> confirmPromote(admin, target.id(), target.name()));
    }

    /**
     * Asks the admin to confirm promoting this player now. Nothing happens until the admin confirms.
     *
     * @param name the candidate's name as shown to players
     */
    public void confirmPromote(Player admin, UUID candidate, String name) {
        if (!allowed(admin, Permissions.PROMOTE)) {
            return;
        }
        presenter.showConfirm(admin, new ConfirmView("Promote " + name + " now?",
                name + " becomes Commoner through LuckPerms, as if they had reached 100%.", "Promote",
                clicker -> promote(clicker, candidate, name)));
    }

    /** The console's {@code promote <player>}, and the same for a player typed by name. No confirmation. */
    public void promote(CommandSender admin, String typedName) {
        if (!allowed(admin, Permissions.PROMOTE)) {
            return;
        }
        resolve(admin, typedName, target -> promote(admin, target.id(), target.name()));
    }

    /**
     * Promotes this player now, through the same guard as auto-promotion; {@link PromotionService#promoteNow} tells the
     * admin the outcome.
     *
     * @param name the candidate's name as shown to players
     */
    public void promote(CommandSender admin, UUID candidate, String name) {
        if (!allowed(admin, Permissions.PROMOTE)) {
            return;
        }
        promotion.promoteNow(admin, candidate, name);
    }

    // ---- console text ----

    /**
     * The plain-text review, for the console: without a name one line per candidate, such as
     * {@code - Bilbo_B: 80% (blocked: badge_moderator)}; with a name one line per valid vote of that player, such as
     * {@code - Elrond (staff): 12 days ago, ends in 88 days: "nice"}. These lines have no chat prefix. A player
     * without valid votes is answered with their progress, 0%.
     */
    public void printReview(CommandSender sender, Optional<String> typedName) {
        if (!allowed(sender, Permissions.REVIEW)) {
            return;
        }
        if (typedName.isEmpty()) {
            printCandidates(sender);
        } else {
            resolve(sender, typedName.get(), target -> printVotes(sender, target));
        }
    }

    private void printCandidates(CommandSender sender) {
        continuations.whenReady(sender, candidates(), found -> {
            if (found.isEmpty()) {
                sender.sendMessage(Messages.noCandidates());
                return;
            }
            for (Candidate c : found) {
                String blocked = c.blockedByGroup().map(group -> " (blocked: " + group + ")").orElse("");
                sender.sendMessage(Component.text("- " + c.name() + ": " + c.percent() + "%" + blocked));
            }
        });
    }

    private void printVotes(CommandSender sender, Target target) {
        continuations.whenReady(sender, detail(target.id()), detail -> {
            if (detail.lines().isEmpty()) {
                sender.sendMessage(Messages.progressOf(target.name(), detail.percent()));
                return;
            }
            for (Line line : detail.lines()) {
                String reason = line.reason().isEmpty() ? "" : ": \"" + line.reason() + "\"";
                sender.sendMessage(Component.text("- " + line.voterName() + (line.staff() ? " (staff)" : "") + ": "
                        + line.daysAgo() + " days ago, ends in " + line.daysLeft() + " days" + reason));
            }
        });
    }

    // ---- checks and names ----

    /** Whether the sender holds the permission; the console holds every permission. */
    private static boolean has(CommandSender sender, String permission) {
        return sender instanceof ConsoleCommandSender || sender.hasPermission(permission);
    }

    /** {@link #has}, and when the sender does not, tells them so. */
    private static boolean allowed(CommandSender sender, String permission) {
        if (has(sender, permission)) {
            return true;
        }
        sender.sendMessage(Messages.noPermission());
        return false;
    }

    /**
     * Finds the player a sender typed (see {@link PlayerNames#find}) and calls {@code found} with them on the main
     * thread. An unknown name is answered with {@link Messages#unknownPlayer}, and a failure of the rank service with
     * {@link Messages#databaseDown()}. Nothing happens for a sender who is a player and has gone offline.
     */
    private void resolve(CommandSender sender, String typedName, Consumer<Target> found) {
        continuations.whenReady(sender, names.find(typedName), target -> {
            if (target.isPresent()) {
                found.accept(target.get());
            } else {
                sender.sendMessage(Messages.unknownPlayer(typedName));
            }
        });
    }

    // ---- what the views are made from ----

    /** One line of the candidate list. */
    private record Candidate(UUID id, String name, int percent, Optional<String> blockedByGroup) {
    }

    /** What the valid votes for one candidate add up to, and the votes. */
    private record Detail(int percent, List<Line> lines) {
    }
}
