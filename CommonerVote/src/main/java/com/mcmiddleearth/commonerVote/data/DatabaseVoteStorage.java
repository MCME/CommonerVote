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
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.Getter;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
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
    
    @Getter
    private boolean connected = false;
    
    public DatabaseVoteStorage(ConfigurationSection config) {
        dbUser = config.getString("user","development");
        dbPassword = config.getString("password","development");
        dbName = config.getString("dataBase","development");
        dbIp = config.getString("ip", "localhost");
        port = config.getInt("port",5992);
        dataBase = new MySQLDataSource(dbIp,port,dbName);
        connect();
    }
    
    public synchronized boolean checkConnection() {
        try {
            if(connected && dbConnection.isValid(1)) {
                connected = true;
                return true;
            } else {
                throw new SQLException();
            }
        } catch (SQLException ex) {
            connected = false;
            connect();
            return isConnected();
        }
    }
    
    private synchronized void connect() {
        try {
            dbConnection = dataBase.getConnection(dbUser, dbPassword);
            
            checkTables();
            
            addVote = dbConnection
                .prepareStatement("INSERT INTO votes VALUES (?,?,?,?,?)");
            withdrawVote = dbConnection
                .prepareStatement("DELETE FROM votes WHERE voter = ? AND recipient = ?");
            clearPlayerVotes = dbConnection
                .prepareStatement("DELETE FROM votes WHERE recipient = ?");
            clearOldVotes = dbConnection
                .prepareStatement("DELETE FROM votes WHERE timestamp < ?");
            getVotes = dbConnection
                .prepareStatement("SELECT * FROM votes ORDER BY recipient");
            getPlayers = dbConnection
                .prepareStatement("SELECT DISTINCT recipient FROM votes");
            getPlayerVotes = dbConnection
                .prepareStatement("SELECT * FROM votes WHERE recipient = ?");
            hasVoted = dbConnection
                .prepareStatement("SELECT voter FROM votes WHERE voter = ? AND recipient = ?");
            maxWeight = dbConnection
                .prepareStatement("SELECT MAX(weight) FROM votes WHERE voter = ? AND recipient = ?");
            connected = true;
        } catch (SQLException ex) {
            connected = false;
            Logger.getLogger(DatabaseVoteStorage.class.getName()).log(Level.SEVERE, "Connection to DB failed", ex);
        }
    }
    
    private synchronized void checkTables() throws SQLException {
        dbConnection.createStatement().execute("CREATE TABLE IF NOT EXISTS votes "
                                              +"(voter VARCHAR(50), recipient VARCHAR(150), "
                                              +"timestamp LONG(20), weight DOUBLE(10), "
                                              +"reason VARCHAR(50))");
    }
    
    @Override
    public Future<Map<UUID, List<Vote>>> getPlayerVotes() {
        return new FutureTask<>(()-> {
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
        });
    }

    @Override
    public Future<List<Vote>> getPlayerVotes(UUID player) {
        return new FutureTask<>(()-> {
            List<Vote> resultList = new ArrayList<>();
            try {
                getPlayerVotes.setFetchSize(100);
                getPlayerVotes.setString(1, player.toString());
                ResultSet result = getPlayerVotes.executeQuery();
                while(!result.next()) {
                    resultList.add(new Vote(UUID.fromString(result.getString("voter")),
                                       result.getDouble("weight"),
                                       result.getLong("timestamp"),
                                       result.getString("reason")));
                }
            } catch (SQLException ex) {
                Logger.getLogger(DatabaseVoteStorage.class.getName()).log(Level.SEVERE, null, ex);
            }
            return resultList;
        });
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
    public Future<Boolean> hasVoted(Player voter, OfflinePlayer recipient) {
        return new FutureTask<>(()-> {
            try {
                hasVoted.setString(1, voter.getUniqueId().toString());
                hasVoted.setString(2, recipient.getUniqueId().toString());
                ResultSet result = hasVoted.executeQuery();
                return result.first();
            } catch (SQLException ex) {
                Logger.getLogger(DatabaseVoteStorage.class.getName()).log(Level.SEVERE, null, ex);
                return false;
            }
        });
    }

    @Override
    public Future<Double> getMaxWeight(Player voter, OfflinePlayer recipient) {
        return new FutureTask<>(()-> {
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
        });
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
    public Future<Boolean> hasApplied(OfflinePlayer player) {
        return new FutureTask(()->true);
    }

    @Override
    public Future<Iterable<UUID>> getPlayers() {
        return new FutureTask<>(()-> {
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
        });
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
}
