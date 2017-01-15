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

import com.mcmiddleearth.pluginutil.NumericUtil;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import lombok.Getter;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.entity.Player;

/**
 *
 * @author Eriol_Eandur
 */
public final class Vote implements ConfigurationSerializable {
    
    @Getter
    private final UUID voter;
    
    @Getter
    private final int weight;
    
    @Getter
    private final long timestamp;
    
    @Getter
    private final String reason;
    
    public Vote(Player voter, int weight, String reason) { 
        this.voter = voter.getUniqueId();
        this.weight = weight;
        timestamp = System.currentTimeMillis();
        this.reason = reason;
    }
    
    private Vote(UUID voter, int weight, long timestamp, String reason) {
        this.voter = voter;
        this.weight = weight;
        this.timestamp = timestamp;
        this.reason = reason;
    }
    
    public boolean isValid() {
        if(PluginData.getStorageTime()>0) {
            return System.currentTimeMillis()-timestamp<PluginData.getStorageTime();
        } else {
            return true;
        }
    }

    @Override
    public Map<String, Object> serialize() {
        Map<String, Object> result = new HashMap<>();
        result.put("voter", voter.toString());
        result.put("weight", weight);
        result.put("timestamp",timestamp);
        result.put("reason",reason);
        return result;
    }
    
    public static Vote deserialize(Map<String,Object> data) {
        Object id = data.get("voter");
        if(id==null || !(id instanceof String) || UUID.fromString((String)id)==null) {
            return null;
        }
        Object weight = data.get("weight");
        if(weight==null|| !(weight instanceof Integer)) {
            return null;
        }
        Object time = data.get("timestamp");
        if(time==null|| !(time instanceof Long)) {
            return null;
        }
        Object reason = data.get("reason");
        if(reason==null|| !(reason instanceof String)) {
            return null;
        }
        Vote result = new Vote(UUID.fromString((String)id),(Integer)weight,(Long)time, (String) reason);
        return result;
    }
    
    
}
