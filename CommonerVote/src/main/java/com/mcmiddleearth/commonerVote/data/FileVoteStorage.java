/*
 * Copyright (C) 2019 Eriol_Eandur
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.mcmiddleearth.commonerVote.data;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 *
 * @author Eriol_Eandur
 */
public class FileVoteStorage implements VoteStorage{

    private final static Map<UUID,List<Vote>> playerVotes = new HashMap<>();
    
    private final File dataFile;
    
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    
    public FileVoteStorage(File dataFile) {
        this.dataFile = dataFile;
        load();
    }
    
    private void load() {
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

    private void save() {
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

    @Override
    public Future<Map<UUID, List<Vote>>> getPlayerVotes() {
        return executor.submit(() -> playerVotes);
    }
    
    @Override
    public Future<List<Vote>> getPlayerVotes(UUID player) {
        return executor.submit(()->playerVotes.get(player));
    }
    
    @Override
    public void apply(OfflinePlayer applicant) {
        List<Vote> votes = playerVotes.get(applicant.getUniqueId());        
        if(votes==null) {
            votes = new ArrayList<>();
            playerVotes.put(applicant.getUniqueId(), votes);
            save();
        }
    }

    @Override
    public void addVote(OfflinePlayer recipient, Vote vote, boolean withdrawPrevious) {
        if(withdrawPrevious) {
            withdrawVote_internal(vote.getVoter(), recipient);
        }
        List<Vote> votes = playerVotes.get(recipient.getUniqueId());        
        if(votes!=null) {
            votes.add(vote);
            save();
        }
    }

    @Override
    public void withdrawVote(Player voter, OfflinePlayer recipient) {
        withdrawVote_internal(voter.getUniqueId(), recipient);
        save();
    }

    private static void withdrawVote_internal(UUID voter, OfflinePlayer recipient) {
        List<Vote> votes = playerVotes.get(recipient.getUniqueId());        
        if(votes!=null) {
            List<Vote> removalList = new ArrayList<>();
            votes.stream().filter((vote) -> (vote.getVoter().equals(voter)))
                          .forEach((vote) -> {
                removalList.add(vote);
            });
            votes.removeAll(removalList);
        }
    }

    @Override
    public Future<Boolean> hasVoted(Player voter, OfflinePlayer recipient) {
        return executor.submit(()-> {
            List<Vote> votes = playerVotes.get(recipient.getUniqueId());
//Logger.getGlobal().info("Has Voted!!");
            if(votes!=null) {
                for(Vote vote: votes) {
                    if(vote.getVoter().equals(voter.getUniqueId())) {
//Logger.getGlobal().info("Has Voted!! -> true");
                        return true;
                    }
                }
            }
//Logger.getGlobal().info("Has Voted!! -> false");
            return false;
        });
    }

    @Override
    public Future<Double> getMaxWeight(Player voter, OfflinePlayer recipient) {
        return executor.submit(()->{
            List<Vote> votes = playerVotes.get(recipient.getUniqueId());        
            double result = 0;
            if(votes!=null) {
                for(Vote vote: votes) {
                    result = Math.max(result, vote.getWeight());
                }
            }
            return result;
        });
    }

    @Override
    public void clearVotes(OfflinePlayer player) {
        playerVotes.remove(player.getUniqueId());
        save();
    }

    @Override
    public Future<Boolean> hasApplied(OfflinePlayer player) {
        return executor.submit(()->playerVotes.containsKey(player.getUniqueId()));
    }

    @Override
    public Future<Iterable<UUID>> getPlayers() {
        return executor.submit(()->playerVotes.keySet());
    }

    @Override
    public void clearOldVotes() {
        List<UUID> invalidVotes = new ArrayList<>();
        playerVotes.keySet().forEach((id) -> {
            clearOldVotes(playerVotes.get(id));
            //OfflinePlayer player = Bukkit.getOfflinePlayer(id);
            //Keep votes for future /vote review <player> -voter
            /*if(player.isOnline() && player.getPlayer().hasPermission(getCommonerPerm())) {
            invalidVotes.add(id);
            }*/
        });
        invalidVotes.forEach((id) -> {
            playerVotes.remove(id);
        });
    }
    
    private static void clearOldVotes(List<Vote> votes) {
        if(votes==null) {
            return;
        }
        List<Vote> old = new ArrayList<>();
        votes.stream().filter((vote) -> (!vote.isValid()))
                      .forEachOrdered((vote) -> {
            old.add(vote);
        });
        votes.removeAll(old);
    }

    @Override
    public void disconnect(){};
    
}
