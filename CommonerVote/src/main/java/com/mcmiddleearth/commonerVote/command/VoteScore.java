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

import com.mcmiddleearth.commonerVote.data.Permission;
import com.mcmiddleearth.commonerVote.data.PluginData;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 *
 * @author Eriol_Eandur
 */
public class VoteScore extends AbstractCommand {
    
    public VoteScore(String... permissionNodes) {
        super(0, true, permissionNodes);
        setShortDescription(": Shows the amount of votes of a player.");
        setUsageDescription("[playerName]: Without optional Argument [Playername] shows the amount of votes for the player who issued the command. Otherwise for the named player.");
    }
    
    @Override
    protected void execute(CommandSender cs, String... args) {
        PluginData.clearOldVotes();
        Player player = (Player) cs;
        if(!player.hasPermission(Permission.SCORE_OTHER) || args.length==0) {
            sendScoreMessage(cs,PluginData.calculateScore(player.getUniqueId()));
            return;
        }
        OfflinePlayer p = getOfflinePlayer(cs,args[0]);
        if(p==null) {
            return;
        }
        sendOtherScoreMessage(cs, args[0],
                              PluginData.calculateScore(p.getUniqueId()));
    }

    private void sendScoreMessage(CommandSender cs, double calculatedScore) {
        PluginData.getMessageUtil().sendInfoMessage(cs, "You have got "+(calculatedScore*100)+"% of needed votes.");
    }

    private void sendOtherScoreMessage(CommandSender cs, String name, double calculatedScore) {
        PluginData.getMessageUtil().sendInfoMessage(cs, name+" has got "+(calculatedScore*100)+"% of needed votes.");
    }
    
}
