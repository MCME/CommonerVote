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

/**
 *
 * @author Eriol_Eandur
 */
public class VoteClear extends AbstractCommand {
    
    public VoteClear(String... permissionNodes) {
        super(1, true, permissionNodes);
        setShortDescription(": Clears all votes for a player.");
        setUsageDescription("<playerName>: Clears all votes for <playerName>. Also clears the players application.");
    }
    
    @Override
    protected void execute(CommandSender cs, String... args) {
        OfflinePlayer p = getOfflinePlayer(cs,args[0]);
        if(p==null) {
            return;
        }
        PluginData.clearVotes(p);
        sendClearedMessage(cs);
    }

    private void sendClearedMessage(CommandSender cs) {
        PluginData.getMessageUtil().sendInfoMessage(cs, "Votes cleared.");
    }

}
