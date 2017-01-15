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
import java.util.UUID;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
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
            List<UUID> promoteablePlayers = PluginData.getPromoteablePlayers();
            int page = 1;
            if(args.length>1 && NumericUtil.isInt(args[1])) {
                page = NumericUtil.getInt(args[1]);
            }
            FancyMessage header = new FancyMessage(MessageType.INFO,
                                                    PluginData.getMessageUtil())
                                            .addSimple("Players with enough votes for  "
                                                       +PluginData.getCommonerGroup()+" ");
            List<FancyMessage> messages = new ArrayList<>();
            for(UUID id:promoteablePlayers) {
                int score = PluginData.calculateScore(id);
                String name = Bukkit.getOfflinePlayer(id).getName();
                FancyMessage message = new FancyMessage(MessageType.INFO_NO_PREFIX,
                                                        PluginData.getMessageUtil())
                                                .addFancy("- "+ChatColor.GREEN+name
                                                           +ChatColor.AQUA + " has "
                                                           +ChatColor.GREEN+score+ChatColor.AQUA+" votes.",
                                                        "/promote "+name,
                                                        ChatColor.YELLOW+"Click for promotion.");
                messages.add(message);
            }
            PluginData.getMessageUtil().sendFancyListMessage((Player)cs, header, messages,
                                                             "/vote review", page);
            return;
        }
        OfflinePlayer p = getOfflinePlayer(cs,args[0]);
        if(p==null) {
            return;
        }
        if(PluginData.isApplicationNeeded() && !PluginData.hasApplied(p)) {
            sendNotAppliedError(cs);
            return;
        }
        List<Vote> votes = PluginData.getVotes(p);
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
    }
    
    private void sendNoVotesMessage(CommandSender cs) {
        PluginData.getMessageUtil().sendInfoMessage(cs,"This player has no votes yet.");
    }
    
}
