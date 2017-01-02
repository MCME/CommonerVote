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
import lombok.Getter;
import lombok.Setter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 *
 * @author Ivanpl, Eriol_Eandur
 */
public abstract class AbstractCommand {
    
    private final String[] permissionNodes;
    
    @Getter
    private final int minArgs;
    
    private boolean playerOnly = true;
    
    @Getter
    @Setter
    private String usageDescription, shortDescription;
    
    @Setter
    private boolean additionalPermissionsEnabled = false;
    
    public AbstractCommand(int minArgs, boolean playerOnly, String... permissionNodes) {
        this.minArgs = minArgs;
        this.playerOnly = playerOnly;
        this.permissionNodes = permissionNodes;
    }
    
    public void handle(CommandSender cs, String... args) {
        Player p = null;
        if(cs instanceof Player) {
            p = (Player) cs;
        }
        
        if(p == null && playerOnly) {
            sendPlayerOnlyErrorMessage(cs);
            return;
        }
        
        if(p != null && !hasPermissions(p)) {
            sendNoPermsErrorMessage(p);
            return;
        }
        
        if(args.length < minArgs) {
            sendMissingArgumentErrorMessage(cs);
            return;
        }
        
        execute(cs, args);
    }
    
    protected abstract void execute(CommandSender cs, String... args);
    
    protected void sendPlayerOnlyErrorMessage(CommandSender cs) {
        PluginData.getMessageUtil().sendErrorMessage(cs, "You have to be logged in to run this command.");
    }
    
    protected void sendNoPermsErrorMessage(CommandSender cs) {
        PluginData.getMessageUtil().sendErrorMessage(cs, "You don't have permission to run this command.");
    }
    
    protected void sendMissingArgumentErrorMessage(CommandSender cs) {
        PluginData.getMessageUtil().sendErrorMessage(cs, "You're missing arguments for this command.");
    }
    
    protected void sendPlayerNotFoundMessage(CommandSender cs) {
        PluginData.getMessageUtil().sendErrorMessage(cs, "Player not found or more than one player found. For players who are offline you have to type in the full name");
    }
        
    private boolean hasPermissions(Player p) {
        if(!p.hasPermission(Permission.USER)) {
            return false;
        }
        if(permissionNodes != null && !additionalPermissionsEnabled) {
            for(String permission : permissionNodes) {
                if (!p.hasPermission(permission)) {
                    return false;
                }
            }
        }
        return true;
    }
    
}
