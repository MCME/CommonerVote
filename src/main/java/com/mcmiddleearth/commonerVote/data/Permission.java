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

/**
 *
 * @author Eriol_Eandur
 */
public final class Permission {
    
    private Permission() { }
    
    public static final String CLEAR = "commonervote.clear";
    public static final String REVIEW = "commonervote.review";
    
    public static final String STAFF = "commonervote.staff";
    public static final String VOTE = "commonervote.vote";

    public static final String SCORE = "commonervote.score";
    public static final String SCORE_OTHER = "commonervote.score.other";
    public static final String APPLY = "commonervote.apply";
    public static final String CONFIG = "commonervote.config";
    public static final String EXEMPT = "commonervote.exempt";

}
