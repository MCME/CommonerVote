package com.mcmiddleearth.commonervote.service;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.mcmiddleearth.commonervote.Permissions;
import com.mcmiddleearth.commonervote.domain.PromotionGuard;
import com.mcmiddleearth.commonervote.domain.Settings;
import com.mcmiddleearth.commonervote.domain.VoteMath;
import com.mcmiddleearth.commonervote.network.Messenger;
import com.mcmiddleearth.commonervote.rank.RankService;
import com.mcmiddleearth.commonervote.storage.DbExecutor;
import com.mcmiddleearth.commonervote.storage.PromotionRecord;
import com.mcmiddleearth.commonervote.storage.PromotionRecord.Source;
import com.mcmiddleearth.commonervote.storage.PromotionStore;
import com.mcmiddleearth.commonervote.storage.VoteStore;
import com.mcmiddleearth.commonervote.ui.DialogPresenter;
import com.mcmiddleearth.commonervote.ui.Texts;
import com.mcmiddleearth.commonervote.ui.view.WelcomeView;

import net.kyori.adventure.text.Component;

/**
 * Turns a guest into a Commoner: runs the promotion command, records the promotion, welcomes the player, announces it
 * to the network and empties the progress meter. It also keeps the records right when a player joins.
 * <p>
 * <b>The promotion command replaces all of a player's groups</b> ({@code lp user <player> parent set <group>}), so it
 * would demote anyone who holds more than a plain guest's groups. It therefore runs in one place only,
 * {@link #promoteIfAllowed}, right after {@link PromotionGuard} has passed on the player's direct LuckPerms groups as
 * they are at that moment. Auto-promotion and "Promote now" both go through it.
 * <p>
 * LuckPerms applies the command asynchronously, so for a moment after it a promoted player still looks like a guest.
 * The players whose promotion is under way or done in this session are kept in memory (until they quit or join) and
 * are not promoted again in that time.
 * <p>
 * The public methods are called on the main thread. Database work runs on the {@link DbExecutor}; the rank service
 * answers on its own threads; every continuation that touches Bukkit comes back through {@code main}, and checks that
 * the player is still online (and still in the same session) before it does anything for them. A database or rank
 * service failure tells the player or admin concerned that the database is unreachable, and leaves nothing half done
 * except as described at {@link #promoteIfAllowed}.
 */
public final class PromotionService {

    private final DbExecutor db;
    private final Executor main;
    private final VoteStore votes;
    private final PromotionStore promotions;
    private final SettingsCache settings;
    private final RankService ranks;
    private final Messenger messenger;
    private final DialogPresenter presenter;
    private final XpBarService xp;
    private final CommandRunner runner;
    private final Logger logger;
    private final LongSupplier clock;

    /**
     * The players whose promotion is under way, or done, in this session, each with the token of the attempt that
     * claimed it. A successful promotion keeps its entry until the player quits or joins. A continuation goes on only
     * while its own token is still the one here, so work started before a quit never acts on a later session.
     */
    private final Map<UUID, Object> inProgress = new ConcurrentHashMap<>();

    /** The players whose blocked auto-promotion has been logged in this session. */
    private final Set<UUID> blockLogged = ConcurrentHashMap.newKeySet();

    /**
     * @param main  runs work on the server's main thread
     * @param clock the time in epoch milliseconds
     */
    public PromotionService(DbExecutor db, Executor main, VoteStore votes, PromotionStore promotions,
                            SettingsCache settings, RankService ranks, Messenger messenger, DialogPresenter presenter,
                            XpBarService xp, CommandRunner runner, Logger logger, LongSupplier clock) {
        this.db = Objects.requireNonNull(db);
        this.main = Objects.requireNonNull(main);
        this.votes = Objects.requireNonNull(votes);
        this.promotions = Objects.requireNonNull(promotions);
        this.settings = Objects.requireNonNull(settings);
        this.ranks = Objects.requireNonNull(ranks);
        this.messenger = Objects.requireNonNull(messenger);
        this.presenter = Objects.requireNonNull(presenter);
        this.xp = Objects.requireNonNull(xp);
        this.runner = Objects.requireNonNull(runner);
        this.logger = Objects.requireNonNull(logger);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Promotes {@code p} if auto-promotion is on, {@code score} is a whole promotion, {@code p} is not exempt and not
     * already being promoted in this session, and the guard allows it. A guard refusal is logged once per player per
     * session at INFO and does not hold the player: a later check (after a vote, say, or once the group was made
     * promotable) tries again.
     */
    public void checkAutoPromotion(Player p, double score) {
        if (!settings.get().autoPromote() || !VoteMath.reachesPromotion(score)
                || p.hasPermission(Permissions.EXEMPT)) {
            return;
        }
        UUID id = p.getUniqueId();
        Object attempt = new Object();
        if (!claim(id, attempt)) {
            return;
        }
        onMain(ranks.directGroups(id), (groups, error) -> {
            if (inProgress.get(id) != attempt) {
                return; // the player quit (and maybe came back) in the meantime
            }
            if (!p.isOnline()) {
                inProgress.remove(id, attempt);
                return;
            }
            if (error != null) {
                logRankFailure(p.getName(), error);
                inProgress.remove(id, attempt);
                p.sendMessage(Messages.databaseDown());
                return;
            }
            PromotionGuard.Result guard = promoteIfAllowed(id, p.getName(), groups, Source.AUTO, null, attempt);
            guard.blockingGroup().ifPresent(group -> logBlockedOnce(id, p.getName(), group));
        });
    }

    /**
     * Promotes {@code target} at an admin's request, online or not, through the same guard as auto-promotion. The
     * admin is told the outcome: promoted, blocked by a group, already exempt (or already being promoted), or the
     * database being unreachable. A target who is online here sees the welcome at once; anyone else sees it at their
     * next join on any server. Does not check the admin's permission: the caller has done that.
     *
     * @param name the target's name, as the promotion command and the messages use it
     */
    public void promoteNow(CommandSender admin, UUID target, String name) {
        Object attempt = new Object();
        if (!claim(target, attempt)) {
            admin.sendMessage(Messages.targetExempt(name));
            return;
        }
        Player online = Bukkit.getPlayer(target);
        CompletableFuture<Boolean> exempt = online != null
                ? CompletableFuture.completedFuture(online.hasPermission(Permissions.EXEMPT))
                : ranks.isExempt(target);
        onMain(exempt.thenCombine(ranks.directGroups(target), Target::new), (checked, error) -> {
            if (error != null) {
                logRankFailure(name, error);
                inProgress.remove(target, attempt);
                tell(admin, Messages.databaseDown());
                return;
            }
            // The target may have joined or quit meanwhile, which drops the claim; take it again unless another
            // promotion of them has started since.
            if (!claim(target, attempt)) {
                tell(admin, Messages.targetExempt(name));
                return;
            }
            if (checked.exempt()) {
                inProgress.remove(target, attempt);
                tell(admin, Messages.targetExempt(name));
                return;
            }
            PromotionGuard.Result guard = promoteIfAllowed(target, name, checked.groups(), Source.MANUAL, admin,
                    attempt);
            guard.blockingGroup().ifPresent(group -> tell(admin, Messages.promoteBlocked(name, group)));
        });
    }

    /**
     * Brings the player's promotion record in line with their rank, then refreshes a guest's XP bar and checks them for
     * auto-promotion, or shows an exempt player the welcome they have not seen yet:
     * <ul>
     *   <li>an exempt player without a record gets a {@code detected} one, but only if they have any votes as a
     *       recipient (otherwise every Commoner's join would add a row);</li>
     *   <li>a player who is not exempt but has a record loses it, and is a candidate again.</li>
     * </ul>
     * Starts a new session for the player: whatever promotion was under way for them is forgotten.
     */
    public void onJoin(Player p) {
        UUID id = p.getUniqueId();
        inProgress.remove(id);
        blockLogged.remove(id);
        if (p.hasPermission(Permissions.EXEMPT)) {
            long now = clock.getAsLong();
            onMain(db.supply(() -> welcomePending(id, now)), (pending, error) -> {
                if (!p.isOnline()) {
                    return;
                }
                if (error != null) {
                    p.sendMessage(Messages.databaseDown());
                } else if (pending) {
                    welcome(p);
                }
            });
        } else {
            long since = validSince();
            onMain(db.supply(() -> {
                if (promotions.find(id).isPresent()) {
                    promotions.delete(id);
                }
                return score(id, since);
            }), (score, error) -> {
                if (!p.isOnline()) {
                    return;
                }
                if (error != null) {
                    p.sendMessage(Messages.databaseDown());
                    return;
                }
                xp.refresh(p, score);
                checkAutoPromotion(p, score);
            });
        }
    }

    /** Ends the player's session: a promotion under way for them is forgotten, and a blocked one may be logged again. */
    public void onQuit(Player p) {
        UUID id = p.getUniqueId();
        inProgress.remove(id);
        blockLogged.remove(id);
    }

    /**
     * Another server says the player's votes changed. For {@link Messenger.Update#VOTED} the player sees the action
     * bar note with their new percentage; for both kinds the XP bar is refreshed and auto-promotion checked.
     */
    public void onRemoteUpdate(Player p, Messenger.Update kind) {
        UUID id = p.getUniqueId();
        long since = validSince();
        onMain(db.supply(() -> score(id, since)), (score, error) -> {
            if (!p.isOnline()) {
                return;
            }
            if (error != null) {
                p.sendMessage(Messages.databaseDown());
                return;
            }
            if (kind == Messenger.Update.VOTED) {
                p.sendActionBar(Messages.actionBar(VoteMath.percent(score)));
            }
            xp.refresh(p, score);
            checkAutoPromotion(p, score);
        });
    }

    /**
     * The one place the promotion command runs. It runs only if {@link PromotionGuard} allows {@code groups}; a
     * refusal releases the claim and is returned for the caller to report.
     * <p>
     * The steps, after the guard: the command; the record, written on the database thread with
     * {@code welcomed=false}; then, back on the main thread, the announcement, the admin's confirmation and, for a
     * player online here, the welcome (see {@link #welcome}). The claim stays after a success, so the player is not
     * promoted twice before LuckPerms has applied the command.
     * <p>
     * If the record cannot be written, the player (auto) or the admin (manual) is told the database is unreachable,
     * the claim is released so a later check can try again, and nothing else happens. The command has run by then:
     * once LuckPerms has applied it the player is exempt, and their next join records them as {@code detected}.
     *
     * @param admin who asked for it, or {@code null} for an auto-promotion
     */
    private PromotionGuard.Result promoteIfAllowed(UUID id, String name, Set<String> groups, Source source,
                                                   CommandSender admin, Object attempt) {
        Settings s = settings.get();
        PromotionGuard.Result guard = PromotionGuard.check(groups, s.promotableGroups());
        if (!guard.allowed()) {
            inProgress.remove(id, attempt);
            return guard;
        }
        String command = s.promotionCommand().replace("<player>", name).replace("<group>", s.commonerGroup());
        logger.info("Promoting " + name + " (" + source.name().toLowerCase(Locale.ROOT) + "): " + command);
        runner.runConsole(command);
        PromotionRecord record = new PromotionRecord(id, clock.getAsLong(), source, false);
        onMain(db.supply(() -> {
            promotions.save(record);
            return null;
        }), (saved, error) -> {
            if (error != null) {
                inProgress.remove(id, attempt);
                tell(admin != null ? admin : Bukkit.getPlayer(id), Messages.databaseDown());
                return;
            }
            messenger.announce(Texts.mini(s.announceText(), Texts.placeholders(s, name)));
            if (admin != null) {
                tell(admin, Messages.promoted(name));
            }
            Player promoted = Bukkit.getPlayer(id);
            if (promoted != null) {
                welcome(promoted);
            }
        });
        return guard;
    }

    /**
     * Shows the welcome, empties the progress meter (only when the XP bar is the meter, as otherwise the XP is the
     * player's own) and records that the player has seen the welcome. If that record cannot be written, the
     * {@link DbExecutor} logs it and the welcome comes again at the next join; the player is not told, as there is
     * nothing for them to try again.
     */
    private void welcome(Player p) {
        Settings s = settings.get();
        presenter.showWelcome(p, new WelcomeView(Texts.mini(s.welcomeText(), Texts.placeholders(s, p.getName())),
                s.rulesUrl()));
        if (s.xpBar()) {
            xp.release(p);
        }
        UUID id = p.getUniqueId();
        db.supply(() -> {
            promotions.markWelcomed(id);
            return null;
        });
    }

    /**
     * On the database thread, for an exempt player who joins: whether they have a welcome to see. Writes the
     * {@code detected} record when they have none and have votes; such a player was promoted by hand, outside this
     * plugin, and gets no welcome.
     */
    private boolean welcomePending(UUID id, long now) throws SQLException {
        Optional<PromotionRecord> record = promotions.find(id);
        if (record.isPresent()) {
            return !record.get().welcomed();
        }
        if (votes.hasAnyVotes(id)) {
            promotions.save(new PromotionRecord(id, now, Source.DETECTED, true));
        }
        return false;
    }

    /** On the database thread: the player's score from their valid votes. */
    private double score(UUID id, long since) throws SQLException {
        return VoteMath.score(votes.validVotesFor(id, since), since);
    }

    private long validSince() {
        return VoteMath.validSince(clock.getAsLong(), settings.get().validityDays());
    }

    /** Claims the player for {@code attempt}; true if it is now (or already was) this attempt's. */
    private boolean claim(UUID id, Object attempt) {
        Object holder = inProgress.putIfAbsent(id, attempt);
        return holder == null || holder == attempt;
    }

    /**
     * Runs {@code then} on the main thread with the outcome of {@code future}: the value, or the failure. An exception
     * thrown by {@code then} itself is logged as SEVERE rather than lost in the future.
     */
    private <T> void onMain(CompletableFuture<T> future, BiConsumer<? super T, ? super Throwable> then) {
        future.handleAsync((value, error) -> {
            then.accept(value, error);
            return null;
        }, main).exceptionally(bug -> {
            logger.log(Level.SEVERE, "A promotion step failed unexpectedly", bug);
            return null;
        });
    }

    /** Sends the message, unless the receiver is a player who has gone offline. */
    private static void tell(CommandSender receiver, Component message) {
        if (receiver == null || receiver instanceof Player p && !p.isOnline()) {
            return;
        }
        receiver.sendMessage(message);
    }

    private void logBlockedOnce(UUID id, String name, String group) {
        if (blockLogged.add(id)) {
            logger.info("Not promoting " + name + " automatically: they have the LuckPerms group " + group
                    + ". Promote them by hand, or add the group to promotableGroups.");
        }
    }

    /** The rank service logs nothing itself, so its failures are logged here, without a stack trace per player. */
    private void logRankFailure(String name, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        logger.warning("Could not read the LuckPerms data of " + name + ", so they were not promoted: " + cause);
    }

    /** What "Promote now" needs to know about its target. */
    private record Target(boolean exempt, Set<String> groups) {
    }
}
