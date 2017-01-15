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
package com.mcmiddleearth.commonerVote.command;

import com.mcmiddleearth.commonerVote.data.PluginData;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 *
 * @author Eriol_Eandur
 */
public class VoteWithdraw extends AbstractCommand {
    
    public VoteWithdraw(String... permissionNodes) {
        super(1, true, permissionNodes);
        setShortDescription(": Withdraws your vote for a player.");
        setUsageDescription("<playerName>: Withdraws your vote for player <playerName>.");
    }
    
    @Override
    protected void execute(CommandSender cs, String... args) {
        OfflinePlayer p = getOfflinePlayer(cs,args[0]);
        if(p==null) {
            return;
        }
        if(PluginData.hasVoted((Player)cs, p)) {
            PluginData.withdrawVote((Player)cs, p);
            sendVoteWithdrawn(cs);
        } else {
            sendNoVoteFound(cs);
        }
    }

    private void sendVoteWithdrawn(CommandSender cs) {
        PluginData.getMessageUtil().sendInfoMessage(cs, "You withdrew your vote.");
    }
    
    private void sendNoVoteFound(CommandSender cs) {
        PluginData.getMessageUtil().sendInfoMessage(cs, "You havent not voted for this player before.");
    }
    
}
