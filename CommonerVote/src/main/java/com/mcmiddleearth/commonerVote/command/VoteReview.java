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
import com.mcmiddleearth.commonerVote.data.Vote;
import com.mcmiddleearth.pluginutil.NumericUtil;
import com.mcmiddleearth.pluginutil.message.FancyMessage;
import com.mcmiddleearth.pluginutil.message.MessageType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 *
 * @author Eriol_Eandur
 */
public class VoteReview extends AbstractCommand {
    
    public VoteReview(String... permissionNodes) {
        super(0, true, permissionNodes);
        setShortDescription(": Shows all valid votes for a player.");
        setUsageDescription("[playerName]: Shows all valid votes for [playerName]. Without optional argument [playerName] it shows a list of all players ready to be promoted.");
    }
    
    @Override
    protected void execute(CommandSender cs, String... args) {
        PluginData.clearOldVotes();
        if(args.length==0 || NumericUtil.isInt(args[0])) {
            PluginData.getMessageUtil().sendNotEnoughArgumentsError(cs);
            return;
            /*List<UUID> promoteablePlayers = new ArrayList<>();//PluginData.getPromoteablePlayers();////
            PluginData.getPlayerVotes(votes -> {
                promoteablePlayers.addAll(votes.keySet());
                int page = 1;
                if(args.length>0 && NumericUtil.isInt(args[0])) {
                    page = NumericUtil.getInt(args[0]);
                }
                FancyMessage header = new FancyMessage(MessageType.INFO,
                                                        PluginData.getMessageUtil())
                                                .addSimple("Players with valid votes for  "
                                                           +PluginData.getCommonerGroup()+" ");
                List<FancyMessage> messages = new ArrayList<>();
                for(UUID id:promoteablePlayers) {
                    double percentage = PluginData.calculateScore(votes.get(id))*100;
                    String name = Bukkit.getOfflinePlayer(id).getName();
                    FancyMessage message = new FancyMessage(MessageType.INFO_NO_PREFIX,
                                                            PluginData.getMessageUtil())
                                                    .addFancy("- "+ChatColor.GREEN+name
                                                               +ChatColor.AQUA + " has "
                                                               +ChatColor.GREEN+percentage+"%"
                                                               +ChatColor.AQUA+" of needed votes.",
                                                            "/vote review "+name,
                                                            ChatColor.YELLOW+"Click for voter names.");
                    messages.add(message);
                }
                PluginData.getMessageUtil().sendFancyListMessage((Player)cs, header, messages,
                                                                 "/vote review", page);
            }, message -> PluginData.getMessageUtil().sendErrorMessage(cs, message));
            return;*/
        } 
        OfflinePlayer p = getOfflinePlayer(cs,args[0]);
        if(p==null) {
            return;
        }
        if(args.length>1 && args[1].equalsIgnoreCase("-voter")) {
            PluginData.getPlayerVotes(votes -> {
                int page = 1;
                if(args.length>2 && NumericUtil.isInt(args[2])) {
                    page = NumericUtil.getInt(args[2]);
                }
                FancyMessage header = new FancyMessage(MessageType.INFO,
                                                        PluginData.getMessageUtil())
                                                .addSimple(PluginData.getCommonerGroup()+" votes from "+p.getName());
                List<FancyMessage> messages = new ArrayList<>();
                int counter = 0;
                for(UUID recieverUuid:votes.keySet()) {
                    String reciever = Bukkit.getOfflinePlayer(recieverUuid).getName();
                    for(Vote vote: votes.get(recieverUuid)) {
                        if(vote.getVoter().equals(p.getUniqueId())) {
                            int timeAgo = (int)((System.currentTimeMillis()-vote.getTimestamp())/1000/3600/24);
                            String reason = vote.getReason();
                            FancyMessage message = new FancyMessage(MessageType.INFO_NO_PREFIX,
                                                                    PluginData.getMessageUtil())
                                                            .addSimple("- "+ChatColor.GREEN+timeAgo
                                                                       +ChatColor.AQUA + " days ago for "
                                                                       +ChatColor.GREEN+reciever);
                            if(!reason.equals("")) {
                                message.addSimple(". Reason: "+reason);
                            }
                            messages.add(message);
                            counter++;
                        }
                    }
                }
                if(counter==0) {
                    sendNotVotedMessage(cs);
                    return;
                }
                PluginData.getMessageUtil().sendFancyListMessage((Player)cs, header, messages,
                                                                 "/vote review "+p.getName()+" -voter ", page);
            }, message -> PluginData.getMessageUtil().sendErrorMessage(cs, message));
        } else {
            PluginData.getVotes(p, votes -> { 
                /*if(PluginData.isApplicationNeeded() && !PluginData.hasApplied(p)) {
                    sendNotAppliedError(cs); 
                    return;
                }*/
                if(votes==null) {
                    sendNoVotesMessage(cs);
                    return;
                }
                int page = 1;
                if(args.length>1 && NumericUtil.isInt(args[1])) {
                    page = NumericUtil.getInt(args[1]);
                }
                FancyMessage header = new FancyMessage(MessageType.INFO,
                                                        PluginData.getMessageUtil())
                                                .addSimple(PluginData.getCommonerGroup()+" votes for "+p.getName());
                List<FancyMessage> messages = new ArrayList<>();
                for(Vote vote:votes) {
                    int timeAgo = (int)((System.currentTimeMillis()-vote.getTimestamp())/1000/3600/24);
                    String voter = Bukkit.getOfflinePlayer(vote.getVoter()).getName();
                    String reason = vote.getReason();
                    FancyMessage message = new FancyMessage(MessageType.INFO_NO_PREFIX,
                                                            PluginData.getMessageUtil())
                                                    .addSimple("- "+ChatColor.GREEN+timeAgo
                                                               +ChatColor.AQUA + " days ago from "
                                                               +ChatColor.GREEN+voter);
                    if(!reason.equals("")) {
                        message.addSimple(". Reason: "+reason);
                    }
                    messages.add(message);
                }
                PluginData.getMessageUtil().sendFancyListMessage((Player)cs, header, messages,
                                                                 "/vote review "+p.getName()+" ", page);
            }, error -> PluginData.getMessageUtil().sendErrorMessage(cs, error));
        }
    }
    
    private void sendNoVotesMessage(CommandSender cs) {
        PluginData.getMessageUtil().sendInfoMessage(cs,"This player has no votes yet.");
    }
    
    private void sendNotVotedMessage(CommandSender cs) {
        PluginData.getMessageUtil().sendInfoMessage(cs,"This player has not voted.");
    }
    
}
