/* 
 *  Copyright (C) 2015 Minecraft Middle Earth
 * 
 *  This file is part of PlotBuild.
 * 
 *  PlotBuild is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  PlotBuild is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with PlotBuild.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.mcmiddleearth.commonerVote.command;

import com.mcmiddleearth.commonerVote.data.PluginData;
import com.mcmiddleearth.pluginutil.NumericUtil;
import com.mcmiddleearth.pluginutil.message.FancyMessage;
import com.mcmiddleearth.pluginutil.message.MessageType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 *
 * @author Eriol_Eandur
 */
public class VoteHelp extends AbstractCommand{
    
    public VoteHelp(String... permissionNodes) {
        super(0, false, permissionNodes);
        setShortDescription(": displays help about vote commands.");
        setUsageDescription(" [command | page#]: Shows a description for [command]. If [command] is not specified a list of short descriptions for all vote commands is shown. Point at a description with mouse cursor for detailed help. Click to get the command in chat.");
    }
    
    @Override
    protected void execute(CommandSender cs, String... args) {
        Map <String, AbstractCommand> commands = ((VoteCommandExecutor)Bukkit.getPluginCommand("vote").getExecutor())
                                                           .getCommands();
        if(args.length>0 && args[0].equalsIgnoreCase("vote")) {
            args[0]="";
        } 
        if(args.length>0 && !NumericUtil.isInt(args[0])){
            AbstractCommand command = commands.get(args[0]);
            if(command==null) {
                sendNoSuchCommandMessage(cs, args[0]);
            }
            else {
                if(command.hasPermissions(cs)) {
                    PluginData.getMessageUtil().sendInfoMessage(cs, "Help for:\n"
                                                   +ChatColor.GREEN+" /vote "+args[0]
                                                   +" "+getUsageMessage(command));
                } else {
                   command.sendNoPermsErrorMessage(cs);
                }
            }
        }
        else {
            int page = 1;
            if(args.length>0 && NumericUtil.isInt(args[0])) {
                page = NumericUtil.getInt(args[0]);
            }
            FancyMessage header = new FancyMessage(MessageType.INFO,
                                                    PluginData.getMessageUtil())
                                            .addSimple("Help for /vote command ");
            List<FancyMessage> messages = new ArrayList<>();
            for(String key:commands.keySet()) {
                if(commands.get(key).hasPermissions(cs)) {
                    FancyMessage message = new FancyMessage(MessageType.INFO_NO_PREFIX,
                                                            PluginData.getMessageUtil())
                            .addFancy(" "+ChatColor.GREEN+"/vote "+key
                                         +ChatColor.AQUA+getShortMessage(commands.get(key)),
                                      "/vote "+key+" ",
                                      PluginData.getMessageUtil()
                                              .hoverFormat("/vote "+key+" "+getUsageMessage(commands.get(key)),
                                                           ":",true));
                    messages.add(message);
                }
            }
            PluginData.getMessageUtil().sendFancyListMessage((Player)cs, header, messages,
                                                             "/vote help ", page);
        }
    }

    private String getUsageMessage(AbstractCommand command) {
        String description = command.getUsageDescription();
        if(description==null){
            description = getShortMessage(command);
        }
        return description;
    }
    
    private String getShortMessage(AbstractCommand command) {
        String description = command.getShortDescription();
        if(description==null) {
            return ": There is no help for this command.";
        } else {
            return description;
        }
    }
    private void sendNoSuchCommandMessage(CommandSender cs, String arg) {
        PluginData.getMessageUtil().sendErrorMessage(cs, "Command not found.");    
    }

}
