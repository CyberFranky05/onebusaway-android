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
                
                // Format actual arrival time
                String arrivalTimeText = TIME_FORMAT.format(new Date(eta));
                
                // Determine status text
                String statusText;
                if (isPredicted) {
                    statusText = "Arriving in " + etaText;
                } else {
                    statusText = "Scheduled: " + etaText;
                }
                
                // Determine short route name + destination text (truncate if needed)
                String destinationText = routeName + " → " + headsign;
                if (destinationText.length() > 24) {
                    destinationText = destinationText.substring(0, 21) + "...";
                }
                
                // ROW 1: Route name (left) and ETA (right)
                arrivalsText.append(destinationText);
                
                // Calculate padding for right-aligned ETA
                int routeEtaPadding = Math.max(0, 34 - destinationText.length() - etaText.length());
                for (int p = 0; p < routeEtaPadding; p++) {
                    arrivalsText.append(" ");
                }
                arrivalsText.append(etaText).append("\n");
                
                // ROW 2: Status info (left) and arrival time (right)
                arrivalsText.append(statusText);
                
                // Calculate padding for right-aligned arrival time
                String arrivalText = "Arrives at " + arrivalTimeText;
                int statusArrivalPadding = Math.max(0, 34 - statusText.length() - arrivalText.length());
                for (int p = 0; p < statusArrivalPadding; p++) {
                    arrivalsText.append(" ");
                }
                arrivalsText.append(arrivalText);
                
                // Add border between arrivals
                if (i < displayCount - 1) {
                    arrivalsText.append("\n");
                    arrivalsText.append("_________________________________");
                    arrivalsText.append("\n");
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