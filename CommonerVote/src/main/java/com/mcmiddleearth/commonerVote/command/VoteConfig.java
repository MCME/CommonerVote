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
import java.util.logging.Logger;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

/**
 *
 * @author Eriol_Eandur
 */
public class VoteConfig extends AbstractCommand {
    
    public VoteConfig(String... permissionNodes) {
        super(0, true, permissionNodes);
        setShortDescription(": Changes the plugin configuration.");
        setUsageDescription("<configurationKey> <configurationValue>: Sets <configurationKey> to <configurationValue>.");
    }
    
    @Override
    protected void execute(CommandSender cs, String... args) {
        if(args.length<2) {
            sendConfigInfoMessage(cs);
            return;
        }
        String message ="";
        for(int i=1; i<args.length;i++) {
            message = message + args[i]+" ";
        }
Logger.getGlobal().info("config "+message.replace("#", "§"));
        if(PluginData.setConfig(args[0],message.replace("#", "§"))) {
            sendConfigChangedMessage(cs);
            sendConfigInfoMessage(cs);
        } else  {
            sendConfigError(cs);
        }
    }

    private void sendConfigChangedMessage(CommandSender cs) {
        PluginData.getMessageUtil().sendInfoMessage(cs, "Configuration saved:");
    }
    
    private void sendConfigInfoMessage(CommandSender cs) {
        PluginData.getMessageUtil().sendInfoMessage(cs, "Plugin Configuration:");
        ConfigurationSection config = PluginData.getConfig();
        for(String key: config.getKeys(false)) {
            PluginData.getMessageUtil().sendNoPrefixInfoMessage(cs, "- "
                            +key+": "+ChatColor.GREEN+config.get(key).toString().replace("§", "#"));
        }
    }

    private void sendConfigError(CommandSender cs) {
        PluginData.getMessageUtil().sendInfoMessage(cs, "Not a valid key or value.");
    }
    
}
