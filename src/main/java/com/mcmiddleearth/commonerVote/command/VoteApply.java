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
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 *
 * @author Eriol_Eandur
 */
public class VoteApply extends AbstractCommand {
    
    public VoteApply(String... permissionNodes) {
        super(0, true, permissionNodes);
        setShortDescription(": Applys for receiving votes.");
        setUsageDescription(": Applys for receiving votes.");
    }
    
    @Override
    protected void execute(CommandSender cs, String... args) {
        Player player = (Player) cs;
        PluginData.hasApplied(player, applied -> {
            if(applied) {
                sendAlreadyAppliedMessage(cs);
                return;
            }
            PluginData.apply(player);
            sendAppliedMessage(cs);
        }, error -> PluginData.getMessageUtil().sendErrorMessage(cs, error));
    }

    private void sendAlreadyAppliedMessage(CommandSender cs) {
        PluginData.getMessageUtil().sendErrorMessage(cs, "You already applied.");
    }

    private void sendAppliedMessage(CommandSender cs) {
        PluginData.getMessageUtil().sendInfoMessage(cs, "Application saved.");
    }
    
}
