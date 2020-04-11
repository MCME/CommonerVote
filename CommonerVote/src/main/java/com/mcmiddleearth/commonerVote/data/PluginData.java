/*
 *  Copyright (C) 2017 Minecraft Middle Earth
 *
 *  This file is part of CommonerVote.
 *
 *  CommonerVote is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  CommonerVote is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with CommonerVote.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.mcmiddleearth.commonerVote.data;

import com.google.common.collect.Iterables;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.mcmiddleearth.commonerVote.CommonerVotePlugin;
import com.mcmiddleearth.pluginutil.NumericUtil;
import com.mcmiddleearth.pluginutil.message.FancyMessage;
import com.mcmiddleearth.pluginutil.message.MessageType;
import com.mcmiddleearth.pluginutil.message.MessageUtil;
import com.mcmiddleearth.pluginutil.message.config.FancyMessageConfigUtil;
import com.mcmiddleearth.pluginutil.message.config.MessageParseException;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * @author Ivan1pl, Eriol_Eandur
 */
public class PluginData {

    private final static MessageUtil messageUtil = new MessageUtil();

    private static long storageTime = ((long) 30) * 24 * 3600 * 1000; //one month

    private static int neededVotes = 16;

    private static int staffVoteWeight = 2;

    private static int otherVoteWeight = 1;

    private static boolean useDatabase = false;

    private static boolean useBungee = false;

    private static String commonerGroup = "Commoner";

    private static String promotionMessage = "§l§6Congrats!!! §eYou were promoted to §9§l "
            + commonerGroup + " §erank. Please §eread the [Click=\"https://www.mcmiddleearth.com/help/terms\"]"
            + "[Hover=\"Click here\"]§9rules[/Hover][/Click]§e.";

    private static boolean allowMultipleVoting = false;

    private static boolean automatedPromotion = true;

    private static String promotionCommand = "lp user <player> parent set <group>";

    private static boolean useXpBar = true;

    private static boolean applicationNeeded = false;

    private static final File configFile = new File(CommonerVotePlugin.getPluginInstance()
            .getDataFolder(), "config.yml");
    private static final File dataFile = new File(CommonerVotePlugin.getPluginInstance()
            .getDataFolder(), "votes.yml");

    private static VoteStorage voteStorage;

    private static long storageTimeout = 4000;

    private static final String internalErrorMessage = "An internal error occured!";

    private static YamlConfiguration config;

    static {
        messageUtil.setPluginName("Vote");
    }
    
    /*public static void loadData() {
        voteStorage.load();
    }*/

    public static void loadConfig() {
        config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException | InvalidConfigurationException ex) {
            try {
                config.save(configFile);
            } catch (IOException ex1) {
                Logger.getLogger(PluginData.class.getName()).log(Level.SEVERE, null, ex1);
            }
            return;
        }
        storageTime = (long) (NumericUtil.getInt(config.getString("validityPeriod", "30")) * (long) 24 * 3600 * 1000);
        staffVoteWeight = config.getInt("staffWeight", staffVoteWeight);
        otherVoteWeight = config.getInt("otherWeight", otherVoteWeight);
        neededVotes = config.getInt("votesNeeded", neededVotes);
        allowMultipleVoting = config.getBoolean("allowMultipleVotes", allowMultipleVoting);
        automatedPromotion = config.getBoolean("automatedPromotion", automatedPromotion);
        useXpBar = config.getBoolean("useXpBar", useXpBar);
        applicationNeeded = config.getBoolean("applicationNeeded", applicationNeeded);
        commonerGroup = config.getString("commonerGroupName", commonerGroup);
        promotionMessage = config.getString("promotionMessage", promotionMessage);
        if (config.contains("promotionCommand")) {
            promotionCommand = config.getString("promotionCommand", promotionCommand);
        }
        useBungee = config.getBoolean("useBungee", useBungee);
        useDatabase = config.getBoolean("useDatabase", useDatabase);
        if (useDatabase) {
            if (voteStorage == null) {
                voteStorage = new DatabaseVoteStorage(config.getConfigurationSection("database"));
            }
        } else {
            voteStorage = new FileVoteStorage(dataFile);
        }
    }

    public static void saveConfig() {
        YamlConfiguration config = getConfig();
        saveConfig_internal(config);
    }

    private static void saveConfig_internal(YamlConfiguration config) {
        try {
            config.save(configFile);
        } catch (IOException ex) {
            Logger.getLogger(PluginData.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public static YamlConfiguration getConfig() {
        YamlConfiguration config = PluginData.config;
        if (config == null) {
            config = new YamlConfiguration();
        }
        config.set("validityPeriod", storageTime / 1000 / 3600 / 24);
        config.set("staffWeight", staffVoteWeight);
        config.set("otherWeight", otherVoteWeight);
        config.set("votesNeeded", neededVotes);
        config.set("allowMultipleVotes", allowMultipleVoting);
        config.set("automatedPromotion", automatedPromotion);
        config.set("useXpBar", useXpBar);
        config.set("applicationNeeded", applicationNeeded);
        config.set("commonerGroupName", commonerGroup);
        config.set("promotionMessage", promotionMessage);
        config.set("promotionCommand", promotionCommand);
        config.set("useDatabase", useDatabase);
        config.set("useBungee", useBungee);
        return config;
    }

    public static boolean setConfig(String key, String value) {
        YamlConfiguration config = getConfig();
        if (!config.contains(key)) {
            return false;
        } else if (config.isInt(key)) {
            if (NumericUtil.isInt(value)) {
                config.set(key, NumericUtil.getInt(value));
                saveConfig_internal(config);
                loadConfig();
                return true;
            } else {
                return false;
            }
        } else if (config.isBoolean(key)) {
            config.set(key, value.trim().equalsIgnoreCase("true"));
            saveConfig_internal(config);
            loadConfig();
            return true;
        } else {
            config.set(key, value);
            saveConfig_internal(config);
            loadConfig();
            return true;
        }
    }

    public static void getPlayerVotes(Consumer<Map<UUID, List<Vote>>> success,
                                      Consumer<String> fail) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    success.accept(voteStorage.getPlayerVotes()
                            .get(storageTimeout, TimeUnit.MILLISECONDS));
                } catch (ExecutionException | TimeoutException ex) {
                    Logger.getLogger(PluginData.class.getName()).log(Level.SEVERE, null, ex);
                    fail.accept(internalErrorMessage);
                } catch (InterruptedException ex) {
                    Logger.getLogger(PluginData.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }.runTaskAsynchronously(CommonerVotePlugin.getPluginInstance());
    }

    public static void apply(OfflinePlayer applicant) {
        new BukkitRunnable() {
            @Override
            public void run() {
                voteStorage.apply(applicant);
            }
        }.runTaskAsynchronously(CommonerVotePlugin.getPluginInstance());
    }

    public static void addVote(Player voter, OfflinePlayer recipient, String reason) {
        //voteStorage.addVote(voter, recipient, reason);
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    List<Vote> votes = voteStorage
                            .getPlayerVotes(recipient.getUniqueId())
                            .get(storageTimeout, TimeUnit.MILLISECONDS);
                    if (votes == null && !applicationNeeded) {
                        voteStorage.apply(recipient);
                        //votes = voteStorage.getPlayerVotes(recipient.getUniqueId());
                    } else if (votes == null) {
                        return;
                    }
                    double newWeight = (getVotingWeight(voter) * 1.0) / neededVotes;
                    boolean withdrawPrevious = false;
                    if (!allowMultipleVoting) {
                        newWeight = Math.max(newWeight,
                                voteStorage.getMaxWeight(voter, recipient)
                                        .get(storageTimeout, TimeUnit.MILLISECONDS));
                        //withdrawVote_internal(voter, recipient);
                        withdrawPrevious = true;
                    }
                    Vote vote = new Vote(voter, newWeight, reason);
                    voteStorage.addVote(recipient, vote, withdrawPrevious);
                    double score = calculateScore(recipient.getUniqueId());
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            promotePlayer(recipient, score);
                            //voteStorage.save();
                            updateXpBar(recipient, score, true);
                        }
                    }.runTask(CommonerVotePlugin.getPluginInstance());
                } catch (InterruptedException ex) {
                    Logger.getLogger(PluginData.class.getName()).log(Level.SEVERE, null, ex);
                } catch (ExecutionException | TimeoutException ex) {
                    Logger.getLogger(PluginData.class.getName()).log(Level.SEVERE, null, ex);
                    messageUtil.sendErrorMessage(voter, internalErrorMessage);
                }
            }
        }.runTaskAsynchronously(CommonerVotePlugin.getPluginInstance());
    }

    public static void withdrawVote(Player voter, OfflinePlayer recipient) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    voteStorage.withdrawVote(voter, recipient);
                    double score = calculateScore(recipient.getUniqueId());
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            updateXpBar(recipient, score, true);
                        }
                    }.runTask(CommonerVotePlugin.getPluginInstance());
                } catch (InterruptedException ex) {
                    Logger.getLogger(PluginData.class.getName()).log(Level.SEVERE, null, ex);
                } catch (ExecutionException | TimeoutException ex) {
                    Logger.getLogger(PluginData.class.getName()).log(Level.SEVERE, null, ex);
                    messageUtil.sendErrorMessage(voter, internalErrorMessage);
                }
            }
        }.runTaskAsynchronously(CommonerVotePlugin.getPluginInstance());
    }

    public static void hasVoted(Player voter, OfflinePlayer recipient,
                                Consumer<Boolean> success,
                                Consumer<String> fail) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    success.accept(voteStorage.hasVoted(voter, recipient)
                            .get(storageTimeout, TimeUnit.MILLISECONDS));
                } catch (InterruptedException ex) {
                    Logger.getLogger(PluginData.class.getName()).log(Level.SEVERE, null, ex);
                } catch (ExecutionException | TimeoutException ex) {
                    Logger.getLogger(PluginData.class.getName()).log(Level.SEVERE, null, ex);
                    fail.accept(internalErrorMessage);
                }
            }
        }.runTaskAsynchronously(CommonerVotePlugin.getPluginInstance());
    }

    public static void getScore(UUID player, Consumer<Double> success, Consumer<String> fail) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    success.accept(calculateScore(player));
                } catch (InterruptedException ex) {
                    Logger.getLogger(PluginData.class.getName()).log(Level.SEVERE, null, ex);
                } catch (ExecutionException | TimeoutException ex) {
                    Logger.getLogger(PluginData.class.getName()).log(Level.SEVERE, null, ex);
                    fail.accept(internalErrorMessage);
                }
            }
        }.runTaskAsynchronously(CommonerVotePlugin.getPluginInstance());
    }

    private static double calculateScore(UUID player)
            throws InterruptedException, ExecutionException, TimeoutException {
        List<Vote> votes = voteStorage.getPlayerVotes(player).get(storageTimeout, TimeUnit.MILLISECONDS);
        return calculateScore(votes);
    }

    public static double calculateScore(List<Vote> votes) {
        if (votes == null) {
            return 0;
        } else {
            double result = 0;
            for (Vote vote : votes) {
                if (vote.isValid()) {
                    result += vote.getWeight();
                }
            }
            return result;
        }
    }

    private static void updateXpBar(OfflinePlayer player, double score, boolean bungeeUpdate) {
        if (useXpBar) {
            if (player.isOnline() && !player.getPlayer().hasPermission(getCommonerPerm())) {
                player.getPlayer().setLevel(0);
                //double score = calculateScore(player.getUniqueId());
                player.getPlayer().setExp(Math.min((float) score, 1));
            } else if (useBungee && bungeeUpdate) {
                try {
                    ByteArrayDataOutput out = ByteStreams.newDataOutput();
                    out.writeUTF("ForwardToPlayer");
                    out.writeUTF(player.getName());
                    out.writeUTF("CommonerVote");

                    ByteArrayOutputStream msgbytes = new ByteArrayOutputStream();
                    DataOutputStream msgout = new DataOutputStream(msgbytes);
                    msgout.writeUTF("Update");

                    out.writeShort(msgbytes.toByteArray().length);
                    out.write(msgbytes.toByteArray());

                    Player sender = Iterables.getFirst(Bukkit.getOnlinePlayers(), null);
                    sender.sendPluginMessage(CommonerVotePlugin.getPluginInstance(),
                            "BungeeCord", out.toByteArray());
                } catch (IOException ex) {
                    Logger.getLogger(PluginData.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
    }

    private static void promotePlayer(OfflinePlayer player, double score) {
        if (automatedPromotion
                && player.isOnline()
                && score >= 0.9999 //calculateScore(player.getUniqueId())>=0.9999
                && !player.getPlayer().hasPermission(getCommonerPerm())) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), promotionCommand.replace("<player>", player.getName()).replace("<group>", commonerGroup));//"pex user "+player.getName()+" group set "+commonerGroup.toLowerCase());
            Bukkit.getOnlinePlayers().stream()
                    .filter(p -> !p.getName().equals(player.getName()))
                    .forEach(p ->
                        messageUtil.sendInfoMessage(p.getPlayer(),
                            "" + ChatColor.GOLD + ChatColor.BOLD + "Congrats!!! "
                                    + ChatColor.YELLOW + player.getName() + " has been promoted to "
                                    + ChatColor.BLUE + ChatColor.BOLD + getCommonergroupCapitalized() + ChatColor.YELLOW + " rank."));
            sendPromotionMessage(player.getPlayer());
            player.getPlayer().recalculatePermissions();
            player.getPlayer().setLevel(0);
            player.getPlayer().setExp(0);

            //Keep votes for future /vote review <player> -voter
            /*if(player.getPlayer().hasPermission(getCommonerPerm())) {
                clearVotes(player);
            }*/
        }
    }

    public static void sendPromotionMessage(Player player) {
        if (!useBungee) {
            List<String> message = new ArrayList<>();
//            test
            message.add(promotionMessage);
            try {
                FancyMessageConfigUtil.addFromStringList(new FancyMessage(MessageType.WHITE,
                                PluginData.getMessageUtil()),
                        message)
                        .setRunDirect()
                        .send(player);
            } catch (MessageParseException ex) {
                messageUtil.sendInfoMessage(player.getPlayer(),
                        "" + ChatColor.GOLD + ChatColor.BOLD + "Congrats!!! "
                                + ChatColor.YELLOW + "You were promoted to "
                                + ChatColor.BLUE + ChatColor.BOLD + getCommonergroupCapitalized() + ChatColor.YELLOW + " rank.");
            }
        } else {
            new BukkitRunnable() {
                @Override
                public void run() {
                    ByteArrayDataOutput out = ByteStreams.newDataOutput();
                    out.writeUTF("Message");
                    out.writeUTF(player.getName());
                    out.writeUTF(promotionMessage);
                    Player sender = Iterables.getFirst(Bukkit.getOnlinePlayers(), null);

                    if (sender != null) {
                        sender.sendPluginMessage(CommonerVotePlugin.getPluginInstance(),
                                "BungeeCord", out.toByteArray());
                    }
                }
            }.runTaskLater(CommonerVotePlugin.getPluginInstance(), 5);
        }
    }
    
    /*private static void getMaxWeight(Player voter, OfflinePlayer recipient,
                                     Consumer<Double> success,
                                     Consumer<String> fail) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    success.accept(voteStorage.getMaxWeight(voter, recipient)
                            .get(storageTimeout, TimeUnit.MILLISECONDS));
                } catch (InterruptedException ex) {
                    Logger.getLogger(PluginData.class.getName()).log(Level.SEVERE, null, ex);
                } catch (ExecutionException | TimeoutException ex) {
                    fail.accept(internalErrorMessage);
                }
            }
        }.runTaskAsynchronously(CommonerVotePlugin.getPluginInstance());
    }*/

    public static void clearVotes(OfflinePlayer player, Consumer<String> fail) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    voteStorage.clearVotes(player);
                    double score = calculateScore(player.getUniqueId());
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            updateXpBar(player, score, true);
                        }
                    }.runTask(CommonerVotePlugin.getPluginInstance());
                } catch (InterruptedException ex) {
                    Logger.getLogger(PluginData.class.getName()).log(Level.SEVERE, null, ex);
                } catch (ExecutionException | TimeoutException ex) {
                    Logger.getLogger(PluginData.class.getName()).log(Level.SEVERE, null, ex);
                    fail.accept(internalErrorMessage);
                }
            }
        }.runTaskAsynchronously(CommonerVotePlugin.getPluginInstance());
    }

    public static void getVotes(OfflinePlayer player,
                                Consumer<List<Vote>> success,
                                Consumer<String> fail) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    success.accept(voteStorage.getPlayerVotes(player.getUniqueId())
                            .get(storageTimeout, TimeUnit.MILLISECONDS));
                } catch (InterruptedException ex) {
                    Logger.getLogger(PluginData.class.getName()).log(Level.SEVERE, null, ex);
                } catch (ExecutionException | TimeoutException ex) {
                    Logger.getLogger(PluginData.class.getName()).log(Level.SEVERE, null, ex);
                    fail.accept(internalErrorMessage);
                }
            }
        }.runTaskAsynchronously(CommonerVotePlugin.getPluginInstance());
    }

    public static void hasApplied(OfflinePlayer player,
                                  Consumer<Boolean> success,
                                  Consumer<String> fail) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    success.accept(voteStorage.hasApplied(player)
                            .get(storageTimeout, TimeUnit.MILLISECONDS));
                } catch (InterruptedException ex) {
                    Logger.getLogger(PluginData.class.getName()).log(Level.SEVERE, null, ex);
                } catch (ExecutionException | TimeoutException ex) {
                    Logger.getLogger(PluginData.class.getName()).log(Level.SEVERE, null, ex);
                    fail.accept(internalErrorMessage);
                }
            }
        }.runTaskAsynchronously(CommonerVotePlugin.getPluginInstance());
    }

    public static void checkPromotion(Player player, boolean bungeeUpdate) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    double score = calculateScore(player.getUniqueId());
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            promotePlayer(player, score);
                            updateXpBar(player, score, bungeeUpdate);
                        }
                    }.runTask(CommonerVotePlugin.getPluginInstance());
                } catch (InterruptedException ex) {
                    Logger.getLogger(PluginData.class.getName()).log(Level.SEVERE, null, ex);
                } catch (ExecutionException | TimeoutException ex) {
                    messageUtil.sendErrorMessage(player, internalErrorMessage);
                    Logger.getLogger(PluginData.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }.runTaskAsynchronously(CommonerVotePlugin.getPluginInstance());
    }

    public static String getCommonerPerm() {
        return Permission.EXEMPT;
    }
    
    /*public static List<UUID> getPromoteablePlayers() {
        List<UUID> result = new ArrayList<>();
        for(UUID id:voteStorage.getPlayers()) {//playerVotes.keySet()) {
            if(calculateScore(id)<0.9999) {
                result.add(id);
            }
        }
        return result;
    }*/

    private static int getVotingWeight(Player player) {
        if (player.hasPermission(Permission.STAFF)) {
            return staffVoteWeight;
        } else {
            return otherVoteWeight;
        }
    }

    public static void clearOldVotes() {
        voteStorage.clearOldVotes();
    }

    private static String getCommonergroupCapitalized() {
        return commonerGroup.substring(0, 1).toUpperCase() + commonerGroup.substring(1);
    }

    public static boolean getAllowMultipleVoting() {
        return allowMultipleVoting;
    }

    public static MessageUtil getMessageUtil() {
        return messageUtil;
    }

    public static long getStorageTime() {
        return storageTime;
    }

    public static boolean isUseBungee() {
        return useBungee;
    }

    public static String getCommonerGroup() {
        return commonerGroup;
    }

    public static boolean isApplicationNeeded() {
        return applicationNeeded;
    }
}