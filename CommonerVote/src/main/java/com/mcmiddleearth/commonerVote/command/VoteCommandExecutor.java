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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import lombok.Getter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 *
 * @author Ivanpl, Eriol_Eandur
 */
public class VoteCommandExecutor implements CommandExecutor {

    @Getter
    private final Map <String, AbstractCommand> commands = new LinkedHashMap <>();
    
    public VoteCommandExecutor() {
        addCommandHandler("", new VoteCommand(Permission.VOTE));
        addCommandHandler("review", new VoteReview(Permission.REVIEW));
        addCommandHandler("score", new VoteScore(Permission.SCORE));
        addCommandHandler("clear", new VoteClear(Permission.CLEAR));
        addCommandHandler("apply", new VoteApply(Permission.APPLY));
        addCommandHandler("config", new VoteConfig(Permission.CONFIG));
        addCommandHandler("withdraw", new VoteWithdraw(Permission.VOTE));
        addCommandHandler("help", new VoteHelp(new String[0]));
    }
    
    @Override
    public boolean onCommand(CommandSender cs, Command cmnd, String string, String[] strings) {
        if(!string.equalsIgnoreCase("vote")) {
            return false;
        }
        if(strings == null || strings.length == 0) {
            sendNoSubcommandErrorMessage(cs);
            return true;
        }
        if(commands.containsKey(strings[0].toLowerCase())) {
            commands.get(strings[0].toLowerCase()).handle(cs, Arrays.copyOfRange(strings, 1, strings.length));
        } else {
            commands.get("").handle(cs, Arrays.copyOfRange(strings, 0, strings.length));
        }
        return true;
    }
    
    private void sendNoSubcommandErrorMessage(CommandSender cs) {
        PluginData.getMessageUtil().sendErrorMessage(cs, "Not enough arguments for this command.");
    }
    
    private void sendSubcommandNotFoundErrorMessage(CommandSender cs) {
        PluginData.getMessageUtil().sendErrorMessage(cs, "Subcommand not found.");
    }
    
    private void addCommandHandler(String name, AbstractCommand handler) {
        commands.put(name, handler);
    }
    
}
