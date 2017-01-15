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

import com.mcmiddleearth.commonerVote.CommonerVotePlugin;
import com.mcmiddleearth.pluginutil.NumericUtil;
import com.mcmiddleearth.pluginutil.message.MessageUtil;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.Getter;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 *
 * @author Ivan1pl, Eriol_Eandur
 */
public class PluginData {
    
    @Getter
    private final static MessageUtil messageUtil = new MessageUtil();
    
    private final static Map<UUID,List<Vote>> playerVotes = new HashMap<>();
    
    @Getter
    private static long storageTime = ((long)30)*24*3600*1000; //one month
    
    private static int neededVotes = 16;
    
    private static int staffVoteWeight = 2;
    
    private static int otherVoteWeight = 1;
    
    @Getter
    private static String commonerGroup = "Commoner";

    private static boolean allowMultipleVoting = false;
    
    private static boolean automatedPromotion = false;
    
    private static boolean useXpBar = true;
    
    @Getter
    private static boolean applicationNeeded = false;
    
    private static final File configFile = new File(CommonerVotePlugin.getPluginInstance()
                                                .getDataFolder(),"config.yml");
    private static final File dataFile = new File(CommonerVotePlugin.getPluginInstance()
                                                .getDataFolder(),"votes.yml");
    
    static {
        messageUtil.setPluginName("Vote");
    }
    
    public static void loadData() {
        playerVotes.clear();
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(dataFile);
        } catch (IOException | InvalidConfigurationException ex) {
            Logger.getLogger(PluginData.class.getName()).log(Level.WARNING, "No voting data file found.", ex);
            return;
        }
        for(String id: config.getKeys(false)) {
            List<Vote> votes = (List<Vote>) config.getList(id);
            playerVotes.put(UUID.fromString(id), votes);
        }
    }
    
    public static void saveData() {
        YamlConfiguration config = new YamlConfiguration();
        for(UUID player: playerVotes.keySet()) {
            //ConfigurationSection section = config.createSection(player.toString());
            List<Vote> votes = playerVotes.get(player);
            clearOldVotes(votes);
            config.set(player.toString(), votes);
            }
        try {
            config.save(dataFile);
        } catch (IOException ex) {
            Logger.getLogger(PluginData.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    public static void loadConfig() {
        YamlConfiguration config = new YamlConfiguration();
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
        storageTime=(long)(NumericUtil.getInt(config.getString("validityPeriod", "30"))*(long)24*3600*1000);
        staffVoteWeight=config.getInt("staffWeight", staffVoteWeight);
        otherVoteWeight=config.getInt("otherWeight", otherVoteWeight);
        neededVotes=config.getInt("votesNeeded", neededVotes);
        allowMultipleVoting=config.getBoolean("allowMultipleVotes", allowMultipleVoting);
        automatedPromotion=config.getBoolean("automatedPromotion", automatedPromotion);
        useXpBar=config.getBoolean("useXpBar", useXpBar);
        applicationNeeded=config.getBoolean("applicationNeeded", applicationNeeded);
        commonerGroup = config.getString("commonerGroupName", commonerGroup);
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
        YamlConfiguration config = new YamlConfiguration();
        config.set("validityPeriod", storageTime/1000/3600/24);
        config.set("staffWeight", staffVoteWeight);
        config.set("otherWeight", otherVoteWeight);
        config.set("votesNeeded", neededVotes);
        config.set("allowMultipleVotes", allowMultipleVoting);
        config.set("automatedPromotion", automatedPromotion);
        config.set("useXpBar", useXpBar);
        config.set("applicationNeeded", applicationNeeded);
        config.set("commonerGroupName", commonerGroup);
        return config;
    }
    
    public static boolean setConfig(String key, String value) {
        YamlConfiguration config = getConfig();
        if(!config.contains(key)) {
            return false;
        } else if(config.isInt(key)) {
            if(NumericUtil.isInt(value)) {
                config.set(key, NumericUtil.getInt(value));
                saveConfig_internal(config);
                loadConfig();
                return true;
            } else {
                return false;
            }
        } else if(config.isBoolean(key)) {
            config.set(key, Boolean.parseBoolean(value));
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
    
    public static void apply(OfflinePlayer applicant) {
        List<Vote> votes = playerVotes.get(applicant.getUniqueId());        
        if(votes==null) {
            votes = new ArrayList<>();
            playerVotes.put(applicant.getUniqueId(), votes);
        }
    }
    
    public static void addVote(Player voter, OfflinePlayer recipient, String reason) {
        List<Vote> votes = playerVotes.get(recipient.getUniqueId());        
        if(!applicationNeeded) {
           apply(recipient);
           votes = playerVotes.get(recipient.getUniqueId());
        } else if(votes==null) {
            return;
        }
        if(!allowMultipleVoting) {
            withdrawVote_internal(voter, recipient);
        }
        Vote vote = new Vote(voter, getVotingWeight(voter), reason);
        votes.add(vote);
        promotePlayer(recipient);
        saveData();
        updateXpBar(recipient);
    }
    
    public static void withdrawVote(Player voter, OfflinePlayer recipient) {
        withdrawVote_internal(voter, recipient);
        saveData();
        updateXpBar(recipient);
    }
 
    public static boolean hasVoted(Player voter, OfflinePlayer recipient) {
        List<Vote> votes = playerVotes.get(recipient.getUniqueId());
        for(Vote vote: votes) {
            if(vote.getVoter().equals(voter.getUniqueId())) {
                return true;
            }
        }
        return false;
    }
    
    public static void promotePlayer(OfflinePlayer player) {
        if(automatedPromotion
                && player.isOnline()
                && calculateScore(player.getUniqueId())>=neededVotes
                && !player.getPlayer().hasPermission(getCommonerPerm())) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pex user "+player.getName()+" group set "+commonerGroup.toLowerCase());
            sendPromotionMessage(player.getPlayer());
            player.getPlayer().recalculatePermissions();
            if(player.getPlayer().hasPermission(getCommonerPerm())) {
                clearVotes(player);
            }
        }

    }
    private static void withdrawVote_internal(Player voter, OfflinePlayer recipient) {
        List<Vote> votes = playerVotes.get(recipient.getUniqueId());        
        if(votes!=null) {
            List<Vote> removalList = new ArrayList<>();
            for(Vote vote: votes) {
                if(vote.getVoter().equals(voter.getUniqueId())) {
                    removalList.add(vote);
                }
            }
            votes.removeAll(removalList);
        }
    }

    public static void clearVotes(OfflinePlayer player) {
        playerVotes.remove(player.getUniqueId());
        saveData();
        updateXpBar(player);
    }
    
    public static void updateXpBar(OfflinePlayer player) {
        if(useXpBar && player.isOnline()) {
            player.getPlayer().setLevel(0);
            int score = calculateScore(player.getUniqueId());
            player.getPlayer().setExp(Math.min(((float)score)/neededVotes,1));
        }
    }
    
    public static int calculateScore(UUID player) {
        List<Vote> votes = playerVotes.get(player);
        if(votes==null) {
            return 0;
        } else {
            int result=0;
            for(Vote vote: votes) {
                if(vote.isValid()) {
                    result+=vote.getWeight();
                }
            }
            return result;
        }
    }
    
    public static List<Vote> getVotes(OfflinePlayer player) {
        return playerVotes.get(player.getUniqueId());
    }
    
    public static boolean hasApplied(OfflinePlayer player) {
        return playerVotes.containsKey(player.getUniqueId());
    }
    
    public static String getCommonerPerm() {
        return "group."+commonerGroup.toLowerCase();
    }
    
    public static List<UUID> getPromoteablePlayers() {
        List<UUID> result = new ArrayList<>();
        for(UUID id:playerVotes.keySet()) {
            if(calculateScore(id)>=neededVotes) {
                result.add(id);
            }
        }
        return result;
    }
    
    private static int getVotingWeight(Player player) {
        if(player.hasPermission(Permission.STAFF)) {
            return staffVoteWeight;
        } else {
            return otherVoteWeight;
        }
    }
    
    public static void clearOldVotes() {
        List<UUID> invalidVotes = new ArrayList<>();
        for(UUID id: playerVotes.keySet()) {
            clearOldVotes(playerVotes.get(id));
            OfflinePlayer player = Bukkit.getOfflinePlayer(id);
            if(player.isOnline() && player.getPlayer().hasPermission(getCommonerPerm())) {
                invalidVotes.add(id);
            }
        }
        for(UUID id: invalidVotes) {
            playerVotes.remove(id);
        }
    }
    
    private static void clearOldVotes(List<Vote> votes) {
        List<Vote> old = new ArrayList<>();
        for(Vote vote:votes) {
            if(!vote.isValid()) {
                old.add(vote);
            }
        }
        votes.removeAll(old);
    }
    
    public static void sendPromotionMessage(Player player) {
        messageUtil.sendInfoMessage(player.getPlayer(),
                            ""+ChatColor.GOLD+ChatColor.BOLD+"Congrats!!! "
                           +ChatColor.YELLOW+"You were promoted to "
                           +ChatColor.GOLD+commonerGroup+ChatColor.YELLOW+" rank.");
    }
            
    public static boolean getAllowMultipleVoting() {
        return allowMultipleVoting;
    }
}