package com.mcmiddleearth.commonervote.service;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.mcmiddleearth.commonervote.Permissions;
import com.mcmiddleearth.commonervote.domain.Settings;
import com.mcmiddleearth.commonervote.domain.Vote;
import com.mcmiddleearth.commonervote.domain.VoteMath;
import com.mcmiddleearth.commonervote.network.Messenger;
import com.mcmiddleearth.commonervote.rank.RankService;
import com.mcmiddleearth.commonervote.service.PlayerNames.Target;
import com.mcmiddleearth.commonervote.storage.DbExecutor;
import com.mcmiddleearth.commonervote.storage.SqlTask;
import com.mcmiddleearth.commonervote.storage.VoteStore;
import com.mcmiddleearth.commonervote.storage.VoteStore.CastResult;
import com.mcmiddleearth.commonervote.ui.DialogPresenter;
import com.mcmiddleearth.commonervote.ui.Texts;
import com.mcmiddleearth.commonervote.ui.view.CandidateListView;
import com.mcmiddleearth.commonervote.ui.view.GuestInfoView;
import com.mcmiddleearth.commonervote.ui.view.VoteView;

import net.kyori.adventure.text.Component;

/**
 * What a player sees and does on {@code /vote}: the team's list of guests, the dialog for one guest, a guest's own
 * progress, and casting, renewing and withdrawing a vote. After a vote, the guest is told, here when they are online
 * on this server and through the {@link Messenger} when they are not, and auto-promotion is checked.
 * <p>
 * The public methods are called on the main thread. Database work runs on the {@link DbExecutor}, the rank service
 * answers on its own threads, and every continuation that touches Bukkit comes back through {@code main} and checks
 * that the player is still online before it does anything for them.
 * <p>
 * <b>Names.</b> A typed name is found among the players online here (in any case), then through the rank service,
 * which also knows players who were only ever on another server. The name shown and used from then on is this
 * server's own spelling when the server knows the player, and otherwise the rank service's. LuckPerms keeps names in
 * lowercase, so that is only the fallback. See {@link PlayerNames}.
 * <p>
 * <b>Failures.</b> When a flow the player started fails because the database or the rank service cannot be reached,
 * the player is told {@link Messages#databaseDown()} and nothing else happens. The {@link DbExecutor} has already
 * logged the failure, so this class logs nothing.
 * <p>
 * <b>Permissions.</b> A dialog can outlive the permission it was opened with, so the methods a dialog button calls
 * ({@link #cast}, {@link #withdraw(Player, UUID, String)} and {@link #openVote(Player, UUID, String)}) check
 * {@link Permissions#VOTE} themselves, and so do the typed-name variants. Who may see another player's progress
 * ({@link #showProgressOf}) is the caller's decision.
 */
public final class VoteService {

    private final DbExecutor db;
    private final Executor main;
    private final VoteStore votes;
    private final SettingsCache settings;
    private final RankService ranks;
    private final PlayerNames names;
    private final Messenger messenger;
    private final DialogPresenter presenter;
    private final XpBarService xp;
    private final PromotionService promotion;
    private final LongSupplier clock;

    /**
     * @param main  runs work on the server's main thread
     * @param clock the time in epoch milliseconds
     */
    public VoteService(DbExecutor db, Executor main, VoteStore votes, SettingsCache settings, RankService ranks,
                       Messenger messenger, DialogPresenter presenter, XpBarService xp, PromotionService promotion,
                       LongSupplier clock) {
        this.db = Objects.requireNonNull(db);
        this.main = Objects.requireNonNull(main);
        this.votes = Objects.requireNonNull(votes);
        this.settings = Objects.requireNonNull(settings);
        this.ranks = Objects.requireNonNull(ranks);
        this.names = new PlayerNames(ranks, main);
        this.messenger = Objects.requireNonNull(messenger);
        this.presenter = Objects.requireNonNull(presenter);
        this.xp = Objects.requireNonNull(xp);
        this.promotion = Objects.requireNonNull(promotion);
        this.clock = Objects.requireNonNull(clock);
    }

    // ---- /vote ----

    /**
     * {@code /vote} without an argument. A voter (holder of {@link Permissions#VOTE}) gets the list of the guests who
     * are online here; anyone else is shown their own progress, see {@link #showOwnProgress}.
     */
    public void openMenu(Player p) {
        if (p.hasPermission(Permissions.VOTE)) {
            showCandidates(p);
        } else {
            showOwnProgress(p);
        }
    }

    /**
     * The guests online on this server, other than the viewer: players with neither {@link Permissions#EXEMPT} nor
     * {@link Permissions#VOTE}, since the team does not vote for itself. The closest to a promotion come first, then
     * by name, ignoring case.
     */
    private void showCandidates(Player viewer) {
        UUID viewerId = viewer.getUniqueId();
        List<Target> guests = Bukkit.getOnlinePlayers().stream()
                .filter(g -> !g.getUniqueId().equals(viewerId))
                .filter(g -> !g.hasPermission(Permissions.EXEMPT) && !g.hasPermission(Permissions.VOTE))
                .map(g -> new Target(g.getUniqueId(), g.getName()))
                .toList();
        if (guests.isEmpty()) {
            viewer.sendMessage(Messages.noGuestsOnline());
            return;
        }
        long since = validSince();
        List<UUID> ids = guests.stream().map(Target::id).toList();
        whenReady(viewer, db.supply(() -> votes.validVotesFor(ids, since)), byGuest -> {
            List<CandidateListView.Row> rows = guests.stream()
                    .map(g -> row(g, byGuest.getOrDefault(g.id(), List.of()), viewerId, since))
                    .sorted(Comparator.comparing(CandidateListView.Row::percent, Comparator.reverseOrder())
                            .thenComparing(CandidateListView.Row::name, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(CandidateListView.Row::name))
                    .toList();
            presenter.showCandidateList(viewer, new CandidateListView(rows));
        });
    }

    private CandidateListView.Row row(Target guest, List<Vote> valid, UUID viewerId, long since) {
        int percent = VoteMath.percent(VoteMath.score(valid, since));
        boolean votedByViewer = valid.stream().anyMatch(v -> v.voter().equals(viewerId));
        return new CandidateListView.Row(guest.id(), guest.name(), percent, votedByViewer,
                clicker -> openVote(clicker, guest.id(), guest.name()));
    }

    /**
     * A guest's own progress: the dialog that explains the vote. An exempt player has nothing to wait for and is told
     * so in chat instead.
     */
    public void showOwnProgress(Player p) {
        if (p.hasPermission(Permissions.EXEMPT)) {
            p.sendMessage(Messages.alreadyCommoner());
            return;
        }
        UUID id = p.getUniqueId();
        long since = validSince();
        whenReady(p, db.supply(() -> score(id, since)), score -> {
            Settings s = settings.get();
            presenter.showGuestInfo(p, new GuestInfoView(VoteMath.percent(score),
                    Texts.mini(s.guestInfoText(), Texts.placeholders(s, p.getName())), s.guideUrl()));
        });
    }

    /**
     * Tells the sender how far the player with this name is, for instance "Bilbo_B has 80% of the votes needed." The
     * caller has checked that the sender may see other players' progress.
     */
    public void showProgressOf(CommandSender sender, String typedName) {
        resolve(sender, typedName, target -> {
            long since = validSince();
            whenReady(sender, db.supply(() -> score(target.id(), since)),
                    score -> sender.sendMessage(Messages.progressOf(target.name(), VoteMath.percent(score))));
        });
    }

    // ---- the vote dialog ----

    /** {@code /vote <player>}: the vote dialog for the player with this name, found in any case, also if offline. */
    public void openVote(Player voter, String typedName) {
        if (!mayVote(voter)) {
            return;
        }
        resolve(voter, typedName, target -> openVote(voter, target.id(), target.name()));
    }

    /**
     * Opens the vote dialog for this player, unless the voter may not vote for them: the voter themselves and an
     * exempt player (a Commoner or higher) are refused with a chat line. The dialog shows the guest's progress and the
     * voter's own valid vote, if there is one.
     *
     * @param name the guest's name as shown to players
     */
    public void openVote(Player voter, UUID target, String name) {
        if (!mayVote(voter)) {
            return;
        }
        Settings s = settings.get();
        long now = clock.getAsLong();
        long since = VoteMath.validSince(now, s.validityDays());
        UUID voterId = voter.getUniqueId();
        whenReady(voter, afterChecks(voter, target, name, () -> votes.validVotesFor(target, since)), checked -> {
            switch (checked) {
                case Checked.Refused<List<Vote>>(var message) -> voter.sendMessage(message);
                case Checked.Passed<List<Vote>>(var valid) ->
                    presenter.showVote(voter, voteView(voterId, target, name, valid, s, now));
            }
        });
    }

    private VoteView voteView(UUID voterId, UUID target, String name, List<Vote> valid, Settings s, long now) {
        double score = VoteMath.score(valid, VoteMath.validSince(now, s.validityDays()));
        Optional<Vote> own = valid.stream().filter(v -> v.voter().equals(voterId)).findFirst();
        OptionalInt votedDaysAgo = own.isPresent()
                ? OptionalInt.of(VoteMath.daysAgo(own.get().timestamp(), now))
                : OptionalInt.empty();
        Optional<Consumer<Player>> onWithdraw = own.isPresent()
                ? Optional.of(clicker -> withdraw(clicker, target, name))
                : Optional.empty();
        return new VoteView(name, VoteMath.percent(score), VoteMath.points(score, s.votesNeeded()), s.votesNeeded(),
                s.validityDays(), votedDaysAgo, own.map(Vote::reason).orElse(""),
                (clicker, reason) -> cast(clicker, target, name, reason), onWithdraw);
    }

    // ---- casting and withdrawing ----

    /**
     * Casts the voter's vote for this player, or renews it if the voter has a valid one: the row is replaced and its
     * clock restarts. The voter is told the new percentage, or that the vote is renewed. The guest then hears of it:
     * on this server through the action bar, the XP bar and the auto-promotion check, otherwise through the
     * {@link Messenger}.
     * <p>
     * Once the vote is stored it counts and reaches the guest, even if the voter has quit by then; only the voter's
     * confirmation is dropped. The same checks as for opening the dialog run again, since the dialog may be old.
     *
     * @param name the guest's name as shown to players
     */
    public void cast(Player voter, UUID target, String name, String reason) {
        if (!mayVote(voter)) {
            return;
        }
        boolean staff = voter.hasPermission(Permissions.STAFF);
        UUID voterId = voter.getUniqueId();
        Settings s = settings.get();
        long now = clock.getAsLong();
        long since = VoteMath.validSince(now, s.validityDays());
        onMain(afterChecks(voter, target, name, () -> votes.cast(voterId, target, staff, reason, now, since)),
                (checked, error) -> {
                    if (error != null) {
                        tell(voter, Messages.databaseDown());
                        return;
                    }
                    switch (checked) {
                        case Checked.Refused<CastResult>(var message) -> tell(voter, message);
                        case Checked.Passed<CastResult>(var cast) -> {
                            tell(voter, cast.replacedValidVote()
                                    ? Messages.voteRenewed(name, s.validityDays())
                                    : Messages.voteCast(name, VoteMath.percent(cast.scoreBefore()),
                                            VoteMath.percent(cast.scoreAfter())));
                            tellRecipientOfVote(target, name, cast.scoreAfter());
                        }
                    }
                });
    }

    /**
     * A guest was voted for: on this server they see the action bar note and their XP bar moves, and they are checked
     * for auto-promotion; if they are not here, the {@link Messenger} tells the server they are on.
     */
    private void tellRecipientOfVote(UUID target, String name, double scoreAfter) {
        Player recipient = Bukkit.getPlayer(target);
        if (recipient == null) {
            messenger.notifyRecipient(name, Messenger.Update.VOTED);
            return;
        }
        recipient.sendActionBar(Messages.actionBar(VoteMath.percent(scoreAfter)));
        xp.refresh(recipient, scoreAfter);
        promotion.checkAutoPromotion(recipient, scoreAfter);
    }

    /**
     * Withdraws the voter's vote for this player, valid or not. The guest is not told in words; their XP bar is
     * refreshed, here or through the {@link Messenger} on the server they are on.
     *
     * @param name the guest's name as shown to players
     */
    public void withdraw(Player voter, UUID target, String name) {
        if (!mayVote(voter)) {
            return;
        }
        UUID voterId = voter.getUniqueId();
        long since = validSince();
        onMain(db.supply(() -> {
            boolean removed = votes.withdraw(voterId, target);
            return new Withdrawal(removed, removed ? score(target, since) : 0);
        }), (withdrawal, error) -> {
            if (error != null) {
                tell(voter, Messages.databaseDown());
                return;
            }
            if (!withdrawal.removed()) {
                tell(voter, Messages.noVoteToWithdraw(name));
                return;
            }
            tell(voter, Messages.withdrawn(name));
            Player recipient = Bukkit.getPlayer(target);
            if (recipient == null) {
                messenger.notifyRecipient(name, Messenger.Update.UPDATE);
            } else {
                xp.refresh(recipient, withdrawal.score());
            }
        });
    }

    /** {@code /vote withdraw <player>}: the same for a player typed by name. */
    public void withdraw(Player voter, String typedName) {
        if (!mayVote(voter)) {
            return;
        }
        resolve(voter, typedName, target -> withdraw(voter, target.id(), target.name()));
    }

    // ---- checks ----

    private static boolean mayVote(Player p) {
        if (p.hasPermission(Permissions.VOTE)) {
            return true;
        }
        p.sendMessage(Messages.noPermission());
        return false;
    }

    /**
     * Why the voter may not vote for this player, or empty when they may: it is the voter themselves, or the player is
     * already a Commoner or higher. Called on the main thread. A player online here is judged by their permissions,
     * anyone else through the rank service.
     */
    private CompletableFuture<Optional<Component>> refusal(Player voter, UUID target, String name) {
        if (target.equals(voter.getUniqueId())) {
            return CompletableFuture.completedFuture(Optional.of(Messages.selfVote()));
        }
        Player online = Bukkit.getPlayer(target);
        CompletableFuture<Boolean> exempt = online != null
                ? CompletableFuture.completedFuture(online.hasPermission(Permissions.EXEMPT))
                : ranks.isExempt(target);
        return exempt.thenApply(isExempt -> isExempt
                ? Optional.of(Messages.targetExempt(name))
                : Optional.<Component>empty());
    }

    /**
     * Runs {@link #refusal} and, if the voter may vote for the player, {@code work} on the database thread. The work
     * goes on straight after the check, without a trip to the main thread, so it does not depend on the voter still
     * being online. Call this on the main thread, with everything the work needs already read from Bukkit.
     */
    private <T> CompletableFuture<Checked<T>> afterChecks(Player voter, UUID target, String name, SqlTask<T> work) {
        return refusal(voter, target, name).thenCompose(refusal -> {
            if (refusal.isPresent()) {
                return CompletableFuture.<Checked<T>>completedFuture(new Checked.Refused<>(refusal.get()));
            }
            return db.supply(work).<Checked<T>>thenApply(Checked.Passed::new);
        });
    }

    // ---- names ----

    /**
     * Finds the player a sender typed (see {@link PlayerNames#find}) and calls {@code found} with them on the main
     * thread. An unknown name is answered with {@link Messages#unknownPlayer}, and a failure of the rank service with
     * {@link Messages#databaseDown()}. Nothing happens for a sender who is a player and has gone offline.
     */
    private void resolve(CommandSender sender, String typedName, Consumer<Target> found) {
        whenReady(sender, names.find(typedName), target -> {
            if (target.isPresent()) {
                found.accept(target.get());
            } else {
                sender.sendMessage(Messages.unknownPlayer(typedName));
            }
        });
    }

    // ---- plumbing ----

    /** On the database thread: the score of {@code id} from the votes that are valid at {@code since}. */
    private double score(UUID id, long since) throws SQLException {
        return VoteMath.score(votes.validVotesFor(id, since), since);
    }

    private long validSince() {
        return VoteMath.validSince(clock.getAsLong(), settings.get().validityDays());
    }

    /**
     * Runs {@code then} on the main thread with the outcome of {@code future}: the value, or the failure. An exception
     * thrown by {@code then} is left to the main thread's handler, which logs it.
     */
    private <T> void onMain(CompletableFuture<T> future, BiConsumer<? super T, ? super Throwable> then) {
        future.whenComplete((value, error) -> main.execute(() -> then.accept(value, error)));
    }

    /**
     * Runs {@code then} on the main thread with the value of {@code future}, for a flow whose only result is something
     * shown to {@code who}. Nothing happens if {@code who} is a player who has left; if the future failed, {@code who}
     * is told the database is unreachable.
     */
    private <T> void whenReady(CommandSender who, CompletableFuture<T> future, Consumer<? super T> then) {
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
    private static void tell(CommandSender receiver, Component message) {
        if (!gone(receiver)) {
            receiver.sendMessage(message);
        }
    }

    private static boolean gone(CommandSender who) {
        return who instanceof Player p && !p.isOnline();
    }

    /** What a withdrawal did: whether there was a vote to remove, and the guest's score once it is gone. */
    private record Withdrawal(boolean removed, double score) {
    }

    /** The outcome of the checks before a vote action: refused with a chat line, or passed with the work's result. */
    private sealed interface Checked<T> {

        record Refused<T>(Component message) implements Checked<T> {
        }

        record Passed<T>(T value) implements Checked<T> {
        }
    }
}
