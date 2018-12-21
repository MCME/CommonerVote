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
public class VoteCommand extends AbstractCommand {
    
    public VoteCommand(String... permissionNodes) {
        super(1, true, permissionNodes);
        setShortDescription(": Votes for a player.");
        setUsageDescription("<playerName>: Give your vote for promoting <playerName> to Commoner.");
    }
    
    @Override
    protected void execute(CommandSender cs, String... args) {
        OfflinePlayer p = getOfflinePlayer(cs,args[0]);
        if(p==null) {
            return;
        }
        if(p.isOnline()) {
            if(p.getPlayer().hasPermission(PluginData.getCommonerPerm())) {
                sendNoValidApplicant(cs);
                return;
            }
        }
        if(PluginData.isApplicationNeeded() && !PluginData.hasApplied(p)) {
            sendNotAppliedError(cs);
            return;
        }
        if(p.getUniqueId().equals(((Player)cs).getUniqueId())) {
            sendDontBeStupidError(cs);
            return;
        }
        String reason = "";
        for(int i = 1; i<args.length; i++) {
            reason = reason + args[i];
        }
        if(!PluginData.getAllowMultipleVoting() && PluginData.hasVoted((Player)cs, p)) {
            PluginData.addVote((Player) cs, p, reason);
            sendAlreadyVotedMessage(cs);
        } else {
            PluginData.addVote((Player) cs, p, reason);
            sendVotedMessage(cs);
        }
    }

    private void sendVotedMessage(CommandSender cs) {
        PluginData.getMessageUtil().sendInfoMessage(cs, "Your vote was registered.");
    }

    private void sendDontBeStupidError(CommandSender cs) {
        PluginData.getMessageUtil().sendErrorMessage(cs, "It's really cringy to vote for yourself.");
    }

    private void sendAlreadyVotedMessage(CommandSender cs) {
        PluginData.getMessageUtil().sendInfoMessage(cs, "Your vote was updated.");
    }

    private void sendNoValidApplicant(CommandSender cs) {
        PluginData.getMessageUtil().sendErrorMessage(cs, "You can't vote for this player.");
    }

}
