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
package com.mcmiddleearth.commonerVote;

import com.mcmiddleearth.commonerVote.data.PluginData;
import com.mcmiddleearth.commonerVote.command.VoteCommandExecutor;
import com.mcmiddleearth.commonerVote.listener.PlayerListener;
import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;

/**
 *
 * @author Ivan1pl, Eriol_Eandur
 */
public class CommonerVotePlugin extends JavaPlugin {
    
    @Getter
    private static CommonerVotePlugin pluginInstance;
    
    @Override
    public void onEnable() {
        pluginInstance = this;
        this.saveDefaultConfig();
        getServer().getPluginManager().registerEvents(new PlayerListener(), this);
        getCommand("vote").setExecutor(new VoteCommandExecutor());
        PluginData.loadData();
        getLogger().info("Enabled!");
    }
    
}
