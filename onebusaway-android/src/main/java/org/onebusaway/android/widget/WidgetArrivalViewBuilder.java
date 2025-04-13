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
import android.graphics.Color;
import android.text.TextUtils;
import android.view.View;
import android.widget.RemoteViews;

import org.onebusaway.android.R;
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
        
        // Build arrival text, limiting to maxArrivals
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
                
                // Line 1: Route number and destination
                arrivalsText.append("■ ").append(routeName).append(" → ");
                // Truncate long destination names
                if (headsign.length() > 20) {
                    arrivalsText.append(headsign.substring(0, 17)).append("...");
                } else {
                    arrivalsText.append(headsign);
                }
                arrivalsText.append("\n");
                
                // Line 2: Arrival time with indent
                arrivalsText.append("   ");
                if (isPredicted) {
                    arrivalsText.append("Arriving in ").append(etaText);
                } else {
                    arrivalsText.append("Scheduled: ").append(etaText);
                }
                
                // Add spacing between entries
                if (i < displayCount - 1) {
                    arrivalsText.append("\n\n");
                }
            }
        }
        
        // If we have more arrivals than we can display, add a note
        if (arrivals.length > displayCount) {
            arrivalsText.append("\n\n").append(arrivals.length - displayCount)
                .append(" more arrival").append(arrivals.length - displayCount > 1 ? "s" : "");
        }
        
        return arrivalsText.toString();
    }

    /**
     * Updates the widget with arrival information
     *
     * @param views RemoteViews for the widget
     * @param context Application context
     * @param arrivals Array of arrival information
     * @param maxArrivals Maximum number of arrivals to display
     */
    public static void updateArrivalsInWidget(RemoteViews views, Context context, ObaArrivalInfo[] arrivals, int maxArrivals) {
        if (arrivals == null || arrivals.length == 0) {
            // Show no arrivals message
            views.setTextViewText(R.id.no_arrivals, "No upcoming arrivals at this time");
            return;
        }

        // Format arrivals as text and set to the TextView
        String arrivalsText = formatArrivalsAsText(context, arrivals, maxArrivals);
        views.setTextViewText(R.id.no_arrivals, arrivalsText);
    }
} 