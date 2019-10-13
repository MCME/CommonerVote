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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 *
 * @author Eriol_Eandur
 */
public interface VoteStorage {

    public void disconnect();
    
    public Future<Map<UUID, List<Vote>>> getPlayerVotes();
    
    public Future<List<Vote>> getPlayerVotes(UUID recipient);

    public void apply(OfflinePlayer player);

    public void addVote(OfflinePlayer recipient, Vote vote, boolean withdrawPrevious);

    public void withdrawVote(Player voter, OfflinePlayer recipient);

    public Future<Boolean> hasVoted(Player voter, OfflinePlayer recipient);

    public Future<Double> getMaxWeight(Player voter, OfflinePlayer recipient);

    public void clearVotes(OfflinePlayer player);

    public Future<Boolean> hasApplied(OfflinePlayer player);

    public Future<Iterable<UUID>> getPlayers();

    public void clearOldVotes();


}
