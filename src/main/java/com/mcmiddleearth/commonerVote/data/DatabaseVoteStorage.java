/*
 * Copyright (C) 2016 MCME
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

import com.mcmiddleearth.commonerVote.CommonerVotePlugin;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.mariadb.jdbc.MySQLDataSource;

/**
 *
 * @author Eriol_Eandur
 */
public class DatabaseVoteStorage implements VoteStorage{

    private final String dbUser;
    private final String dbPassword;
    private final String dbName;
    private final String dbIp;
    private final int port;
    
    private final MySQLDataSource dataBase;
    
    private Connection dbConnection;
    
    private PreparedStatement addVote;
    private PreparedStatement withdrawVote;
    private PreparedStatement clearPlayerVotes;
    private PreparedStatement clearOldVotes;
    private PreparedStatement getVotes;
    private PreparedStatement getPlayers;
    private PreparedStatement getPlayerVotes;
    private PreparedStatement hasVoted;
    private PreparedStatement maxWeight;
    
    private boolean connected = false;
    
    private BukkitTask keepAliveTask;
    
    public DatabaseVoteStorage(ConfigurationSection config) {
        if(config==null) {
            config = new MemoryConfiguration();
        }
        dbUser = config.getString("user","development");
        dbPassword = config.getString("password","development");
        dbName = config.getString("dbName","development");
        dbIp = config.getString("ip", "localhost");
        port = config.getInt("port",3306);
        dataBase = new MySQLDataSource(dbIp,port,dbName);
        connect();
        keepAliveTask = new BukkitRunnable() {
            @Override
            public void run() {
                checkConnection();
Logger.getGlobal().info("ComonerVoteTasks: "+Bukkit.getScheduler().getPendingTasks().stream().filter(task -> task.getOwner().equals(CommonerVotePlugin.getPluginInstance())).count());
Logger.getGlobal().info("CommonerVoteWorker: "+Bukkit.getScheduler().getActiveWorkers().stream().filter(task -> task.getOwner().equals(CommonerVotePlugin.getPluginInstance())).count());
            }
        }.runTaskTimerAsynchronously(CommonerVotePlugin.getPluginInstance(), 0, 1200);
    }
    
    private boolean checkConnection() {
        try {
            if(connected && dbConnection.isValid(5)) {
                CommonerVotePlugin.getPluginInstance().getLogger().log(Level.INFO,
                        "Successfully checked connection to vote database.");
                connected = true;
                return true;
            } else {
                throw new SQLException();
            }
        } catch (SQLException ex) {
            connected = false;
                if (dbConnection != null) {
                    try {
                        dbConnection.close();
                    } catch (SQLException ex1) {
                        Logger.getLogger(DatabaseVoteStorage.class.getName()).log(Level.SEVERE, null, ex1);
                    }
                }
            connect();
            return isConnected();
        }
    }
    
    private void connect() {
        try {
            dbConnection = dataBase.getConnection(dbUser, dbPassword);
            
            checkTables();
            
            addVote = dbConnection
                .prepareStatement("INSERT INTO commonervote_votes VALUES (?,?,?,?,?)");
            withdrawVote = dbConnection
                .prepareStatement("DELETE FROM commonervote_votes WHERE voter = ? AND recipient = ?");
            clearPlayerVotes = dbConnection
                .prepareStatement("DELETE FROM commonervote_votes WHERE recipient = ?");
            clearOldVotes = dbConnection
                .prepareStatement("DELETE FROM commonervote_votes WHERE timestamp < ?");
            getVotes = dbConnection
                .prepareStatement("SELECT * FROM commonervote_votes ORDER BY recipient");
            getPlayers = dbConnection
                .prepareStatement("SELECT DISTINCT recipient FROM commonervote_votes");
            getPlayerVotes = dbConnection
                .prepareStatement("SELECT * FROM commonervote_votes WHERE recipient = ?");
            hasVoted = dbConnection
                .prepareStatement("SELECT voter FROM commonervote_votes WHERE voter = ? AND recipient = ?");
            maxWeight = dbConnection
                .prepareStatement("SELECT MAX(weight) FROM commonervote_votes WHERE voter = ? AND recipient = ?");
            connected = true;
        } catch (SQLException ex) {
            if (dbConnection != null) {
                try {
                    dbConnection.close();
                } catch (SQLException ex1) {
                    Logger.getLogger(DatabaseVoteStorage.class.getName()).log(Level.SEVERE, null, ex1);
                }
            }
            connected = false;
            Logger.getLogger(DatabaseVoteStorage.class.getName()).log(Level.SEVERE, "Connection to DB failed", ex);
        }
    }
    
    @Override
    public void disconnect() {
        if (keepAliveTask != null) {
            keepAliveTask.cancel();
        }
        if(connected && dbConnection!=null) {
            try {
                dbConnection.close();
            } catch (SQLException ex) {
                Logger.getLogger(DatabaseVoteStorage.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }
    
    private void checkTables() throws SQLException {
        dbConnection.createStatement().execute("CREATE TABLE IF NOT EXISTS commonervote_votes "
                                              +"(voter VARCHAR(50), recipient VARCHAR(50), "
                                              +"timestamp BIGINT(20), weight DOUBLE, "
                                              +"reason VARCHAR(150))");
    }
    
    @Override
    public Map<UUID, List<Vote>> getPlayerVotes() {
        Map<UUID, List<Vote>> resultMap = new HashMap<>();
        try {
            getVotes.setFetchSize(5000);
            ResultSet result = getVotes.executeQuery();
            if(result.first()) {
                List<Vote> votes = new ArrayList<>();
                String recipient = result.getString("recipient");
                do {
                    if(!recipient.equals(result.getString("recipient"))) {
                       resultMap.put(UUID.fromString(recipient), votes);
                       recipient = result.getString("recipient");
                       votes = new ArrayList<>();
                    }
                    votes.add(new Vote(UUID.fromString(result.getString("voter")),
                                       result.getDouble("weight"),
                                       result.getLong("timestamp"),
                                       result.getString("reason")));

                } while(result.next());
            }
        } catch (SQLException ex) {
            Logger.getLogger(DatabaseVoteStorage.class.getName()).log(Level.SEVERE, null, ex);
        }
        return resultMap;
    }

    @Override
    public List<Vote> getPlayerVotes(UUID player) {
        List<Vote> resultList = new ArrayList<>();
        try {
            getPlayerVotes.setFetchSize(100);
            getPlayerVotes.setString(1, player.toString());
            ResultSet result = getPlayerVotes.executeQuery();
            while(result.next()) {
                resultList.add(new Vote(UUID.fromString(result.getString("voter")),
                                   result.getDouble("weight"),
                                   result.getLong("timestamp"),
                                   result.getString("reason")));
            }
        } catch (SQLException ex) {
            Logger.getLogger(DatabaseVoteStorage.class.getName()).log(Level.SEVERE, null, ex);
        }
        return resultList;
    }

    @Override
    public void apply(OfflinePlayer player) {
    }

    @Override
    public void addVote(OfflinePlayer recipient, Vote vote, boolean withdrawPrevious) {
        try {
            if(withdrawPrevious) {
                withdrawVote(vote.getVoter(),recipient);
            }
            addVote.setString(1, vote.getVoter().toString());
            addVote.setString(2, recipient.getUniqueId().toString());
            addVote.setLong(3, vote.getTimestamp());
            addVote.setDouble(4, vote.getWeight());
            addVote.setString(5, vote.getReason());
            addVote.executeUpdate();
        } catch (SQLException ex) {
            Logger.getLogger(DatabaseVoteStorage.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    @Override
    public void withdrawVote(Player voter, OfflinePlayer recipient) {
        withdrawVote(voter.getUniqueId(),recipient);
    }
    
    private void withdrawVote(UUID voter, OfflinePlayer recipient) {
        try {
            withdrawVote.setString(1, voter.toString());
            withdrawVote.setString(2, recipient.getUniqueId().toString());
            withdrawVote.executeUpdate();
        } catch (SQLException ex) {
            Logger.getLogger(DatabaseVoteStorage.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    @Override
    public boolean hasVoted(Player voter, OfflinePlayer recipient) {
        try {
            hasVoted.setString(1, voter.getUniqueId().toString());
            hasVoted.setString(2, recipient.getUniqueId().toString());
            ResultSet result = hasVoted.executeQuery();
            return result.first();
        } catch (SQLException ex) {
            Logger.getLogger(DatabaseVoteStorage.class.getName()).log(Level.SEVERE, null, ex);
            return false;
        }
    }

    @Override
    public double getMaxWeight(Player voter, OfflinePlayer recipient) {
        try {
            maxWeight.setString(1, voter.getUniqueId().toString());
            maxWeight.setString(2, recipient.getUniqueId().toString());
            ResultSet result = maxWeight.executeQuery();
            if(result.first()) {
                return result.getDouble(1);
            } else {
                return 0d;
            }
        } catch (SQLException ex) {
            Logger.getLogger(DatabaseVoteStorage.class.getName()).log(Level.SEVERE, null, ex);
        }
        return 0d;
    }

    @Override
    public void clearVotes(OfflinePlayer player) {
        try {
            clearPlayerVotes.setString(1, player.getUniqueId().toString());
            clearPlayerVotes.executeUpdate();
        } catch (SQLException ex) {
            Logger.getLogger(DatabaseVoteStorage.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    @Override
    public boolean hasApplied(OfflinePlayer player) {
        return true;
    }

    @Override
    public Iterable<UUID> getPlayers() {
        Set<UUID> resultSet = new HashSet<>();
        try {
            getPlayers.setFetchSize(5000);
            ResultSet result = getPlayers.executeQuery();
            while(result.next()) {
                resultSet.add(UUID.fromString(result.getString(1)));
            }
        } catch (SQLException ex) {
            Logger.getLogger(DatabaseVoteStorage.class.getName()).log(Level.SEVERE, null, ex);
        }
        return resultSet;
    }

    @Override
    public void clearOldVotes() {
        try {
            clearOldVotes.setLong(1, System.currentTimeMillis()-PluginData.getStorageTime());
            clearOldVotes.executeUpdate();
        } catch (SQLException ex) {
            Logger.getLogger(DatabaseVoteStorage.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public boolean isConnected() {
        return connected;
    }
}
