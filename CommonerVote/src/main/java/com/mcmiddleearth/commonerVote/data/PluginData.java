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
package com.mcmiddleearth.commonerVote.data;

import com.mcmiddleearth.pluginutil.message.MessageUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 *
 * @author Ivan1pl, Eriol_Eandur
 */
public class PluginData {
    
    @Getter
    private final static MessageUtil messageUtil;
    
    private final static Map<UUID,List<Vote>> playerVotes;
    
    @Getter
    private static long storageTime = 30*24*3600*1000; //one month
    
    static {
        messageUtil = new MessageUtil();
        messageUtil.setPluginName("CommonerVote");
        playerVotes = new HashMap<>();
    }
    
    public static void loadData() {
        
    }
    
    public static void saveData() {
        
    }
    
    public static void addVote(Player voter, OfflinePlayer recipient) {
        
    }
    
    public static void withdrawVote(Player voter, OfflinePlayer recipient) {
        
    }
    
}