/* 
 *  Copyright (C) 2017 Minecraft Middle Earth
 * 
 *  This file is part of CommonerVote.
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
package com.mcmiddleearth.commonerVote.data;

import java.util.UUID;
import lombok.Getter;
import org.bukkit.entity.Player;

/**
 *
 * @author Eriol_Eandur
 */
public final class Vote {
    
    @Getter
    private UUID voter;
    
    @Getter
    private int weight;
    
    @Getter
    private long timestamp;
    
    public Vote(Player voter, int weight) { 
        this.voter = voter.getUniqueId();
        this.weight = weight;
        timestamp = System.currentTimeMillis();
    }
    
    public boolean isValid() {
        return System.currentTimeMillis()-timestamp<PluginData.getStorageTime();
    }
    
    
}
