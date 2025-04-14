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

import android.content.Context;
import android.text.TextUtils;

import org.onebusaway.android.io.elements.ObaArrivalInfo;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Helper class to build arrival text for the widget
 */
public class WidgetArrivalViewBuilder {

    private static final String TAG = "WidgetArrivalBuilder";
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("h:mm a", Locale.getDefault());

    /**
     * Formats arrival information as text for use in the widget
     *
     * @param context Application context
     * @param arrivals Array of arrival information
     * @param maxArrivals Maximum number of arrivals to display
     * @return Formatted text for display in the widget
     */
    public static String formatArrivalsAsText(Context context, ObaArrivalInfo[] arrivals, int maxArrivals) {
        if (arrivals == null || arrivals.length == 0) {
            return "No upcoming arrivals at this time.";
        }
        
        StringBuilder arrivalsText = new StringBuilder();
        int displayCount = Math.min(arrivals.length, maxArrivals);
        
        for (int i = 0; i < displayCount; i++) {
            ObaArrivalInfo arrival = arrivals[i];
            if (arrival != null) {
                // Get route number
                String routeName = !TextUtils.isEmpty(arrival.getShortName()) ? 
                    arrival.getShortName() : arrival.getRouteId();
                
                // Get destination (headsign)
                String headsign = !TextUtils.isEmpty(arrival.getHeadsign()) ?
                    arrival.getHeadsign() : "Unknown destination";
                
                // Calculate arrival time
                long eta = arrival.getPredictedArrivalTime();
                boolean isPredicted = true;
                if (eta == 0) {
                    eta = arrival.getScheduledArrivalTime();
                    isPredicted = false;
                }
                
                long now = System.currentTimeMillis();
                long minutes = (eta - now) / 60000;
                
                // Format time
                String etaText;
                if (minutes <= 0) {
                    etaText = "now";
                } else if (minutes < 60) {
                    etaText = minutes + " min";
                } else {
                    etaText = TIME_FORMAT.format(new Date(eta));
                }
                
                // Format arrival time for better readability
                String arrivalTimeText = TIME_FORMAT.format(new Date(eta));
                
                // Get status text with appropriate formatting
                String statusText = isPredicted ? "Est: " + etaText : "Sched: " + etaText;
                
                // Format arrival time for the full row
                String destinationText = routeName + " → " + headsign;
                if (destinationText.length() > 28) {
                    // Truncate if too long
                    destinationText = destinationText.substring(0, 25) + "...";
                }
                
                // Add route and ETA
                arrivalsText.append(destinationText).append("\n");
                arrivalsText.append(statusText).append(" (").append(arrivalTimeText).append(")");
                
                // Add divider between arrivals
                if (i < displayCount - 1) {
                    arrivalsText.append("\n\n");
                }
            }
        }
        
        // If we have more arrivals than we can display, add a note
        if (arrivals.length > displayCount) {
            arrivalsText.append("\n\n+ ").append(arrivals.length - displayCount)
                .append(" more arrival").append(arrivals.length - displayCount > 1 ? "s" : "");
        }
        
        return arrivalsText.toString();
    }
} 