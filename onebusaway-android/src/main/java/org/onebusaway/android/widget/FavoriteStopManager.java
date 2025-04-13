/*
 * Copyright (C) 2023 OneBusAway
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onebusaway.android.widget;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;

import org.onebusaway.android.provider.ObaContract;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper class to manage favorite stops for widgets
 */
public class FavoriteStopManager {
    private static final String TAG = "FavoriteStopManager";

    // Projection for stops query
    private static final String[] STOPS_PROJECTION = new String[]{
            ObaContract.Stops._ID,
            ObaContract.Stops.UI_NAME,
            ObaContract.Stops.FAVORITE,
            ObaContract.Stops.USE_COUNT
    };

    /**
     * Get the most frequently used favorite stop
     *
     * @param context The context
     * @return A FavoriteStop object with the stop ID and name, or null if no favorites exist
     */
    public static FavoriteStop getMostFrequentStop(Context context) {
        ContentResolver cr = context.getContentResolver();
        
        // Query for favorite stops sorted by use count (most frequent first)
        Cursor c = cr.query(
                ObaContract.Stops.CONTENT_URI,
                STOPS_PROJECTION,
                ObaContract.Stops.FAVORITE + "=1",
                null,
                ObaContract.Stops.USE_COUNT + " DESC"
        );
        
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    // Get the first (most frequent) stop
                    String stopId = c.getString(0);
                    String stopName = c.getString(1);
                    return new FavoriteStop(stopId, stopName);
                }
            } finally {
                c.close();
            }
        }
        
        // Return null if no favorite stops found
        return null;
    }

    /**
     * Get all favorite stops
     *
     * @param context The context
     * @return A list of all favorite stops
     */
    public static List<FavoriteStop> getAllFavoriteStops(Context context) {
        List<FavoriteStop> favoriteStops = new ArrayList<>();
        ContentResolver cr = context.getContentResolver();

        // Query for all favorite stops
        Cursor c = cr.query(
                ObaContract.Stops.CONTENT_URI,
                STOPS_PROJECTION,
                ObaContract.Stops.FAVORITE + "=1",
                null,
                ObaContract.Stops.USE_COUNT + " DESC" // Sort by most frequently used
        );

        if (c != null) {
            try {
                while (c.moveToNext()) {
                    String stopId = c.getString(0);
                    String stopName = c.getString(1);
                    favoriteStops.add(new FavoriteStop(stopId, stopName));
                }
            } finally {
                c.close();
            }
        }

        return favoriteStops;
    }

    /**
     * Simple data class to hold stop information
     */
    public static class FavoriteStop {
        private final String mStopId;
        private final String mStopName;

        public FavoriteStop(String stopId, String stopName) {
            mStopId = stopId;
            mStopName = stopName;
        }

        public String getStopId() {
            return mStopId;
        }

        public String getStopName() {
            return mStopName;
        }
    }
} 