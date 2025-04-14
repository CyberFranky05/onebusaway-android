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
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import android.util.TypedValue;

import org.onebusaway.android.R;
import org.onebusaway.android.io.elements.ObaArrivalInfo;
import org.onebusaway.android.util.UIUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Helper class to build card-based arrival views for the widget
 */
public class WidgetCardBuilder {

    private static final String TAG = "WidgetCardBuilder";
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("h:mm a", Locale.getDefault());
    private static final int MAX_CARDS = 5; // Maximum number of cards to show

    // Add status color constants
    private static final int COLOR_ON_TIME = Color.parseColor("#008000"); // Green
    private static final int COLOR_DELAYED = Color.parseColor("#b71c1c"); // Red
    private static final int COLOR_SCHEDULED = Color.parseColor("#757575"); // Gray
    private static final int COLOR_NOW = Color.parseColor("#D32F2F"); // Bright red

    // Status indicator resource IDs
    private static final int INDICATOR_ON_TIME = R.drawable.status_indicator_on_time;
    private static final int INDICATOR_DELAYED = R.drawable.status_indicator_delayed;
    private static final int INDICATOR_SCHEDULED = R.drawable.status_indicator_scheduled;
    private static final int INDICATOR_NOW = R.drawable.status_indicator_now;

    /**
     * Builds arrival cards for the widget
     *
     * @param context Application context
     * @param views RemoteViews to update
     * @param arrivals Array of arrival information
     */
    public static void buildArrivalCards(Context context, RemoteViews views, ObaArrivalInfo[] arrivals) {
        // Clear existing cards
        views.removeAllViews(R.id.arrivals_container);
        
        if (arrivals == null || arrivals.length == 0) {
            // Show no arrivals message
            views.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
            views.setViewVisibility(R.id.arrivals_container, View.GONE);
            views.setTextViewText(R.id.no_arrivals, "No upcoming arrivals at this time.");
            return;
        }
        
        // Show arrivals container, hide no arrivals message
        views.setViewVisibility(R.id.no_arrivals, View.GONE);
        views.setViewVisibility(R.id.arrivals_container, View.VISIBLE);
        
        // Limit number of cards to display
        int displayCount = Math.min(arrivals.length, MAX_CARDS);
        
        // Create a card for each arrival
        for (int i = 0; i < displayCount; i++) {
            ObaArrivalInfo arrival = arrivals[i];
            if (arrival == null) continue;
            
            // Create a RemoteViews for the card
            RemoteViews cardView = new RemoteViews(context.getPackageName(), R.layout.widget_arrival_card);
            
            // Set route name and color
            String routeName = !TextUtils.isEmpty(arrival.getShortName()) ? 
                arrival.getShortName() : arrival.getRouteId();
            cardView.setTextViewText(R.id.route_name, routeName);
            
            // Try to set the route color if available
            try {
                int routeColor = arrival.getRouteColor() != 0 ? 
                    arrival.getRouteColor() : Color.parseColor("#1976D2"); // Default blue
                
                // Set the background color of the badge
                cardView.setInt(R.id.route_name, "setBackgroundColor", routeColor);
                
                // Set text color to white for contrast
                cardView.setTextColor(R.id.route_name, Color.WHITE);
            } catch (Exception e) {
                Log.e(TAG, "Error setting route color", e);
                // Use default colors if there's an error
            }
            
            // Set destination
            String headsign = !TextUtils.isEmpty(arrival.getHeadsign()) ?
                arrival.getHeadsign() : "Unknown destination";
            cardView.setTextViewText(R.id.destination, headsign);
            
            // Calculate arrival time and status
            long eta = arrival.getPredictedArrivalTime();
            boolean isPredicted = (eta != 0);
            if (!isPredicted) {
                eta = arrival.getScheduledArrivalTime();
            }
            
            // Get arrival status from API
            String arrivalStatus = arrival.getStatus();
            
            // Calculate time difference between scheduled and predicted times
            long scheduled = arrival.getScheduledArrivalTime();
            long predicted = arrival.getPredictedArrivalTime();
            long deviation = 0;
            
            // Only calculate deviation if we have predicted times
            if (predicted > 0 && scheduled > 0) {
                deviation = predicted - scheduled;
            }
            
            long now = System.currentTimeMillis();
            long minutes = (eta - now) / 60000;
            
            // Format ETA text - just the number for minutes like in the app UI
            String etaText;
            int statusColor;
            int statusIndicator;
            
            if (minutes <= 0) {
                // Arriving now
                etaText = "now";
                statusColor = COLOR_NOW;
                statusIndicator = INDICATOR_NOW;
            } else if (minutes < 60) {
                // Arriving within the hour - just the number for minutes
                etaText = String.valueOf(minutes);
                
                // Determine status color based on prediction and deviation
                if (!isPredicted) {
                    // Scheduled time, no real-time data
                    statusColor = COLOR_SCHEDULED;
                    statusIndicator = INDICATOR_SCHEDULED;
                } else if ("EARLY".equals(arrivalStatus) || deviation < -180000) {
                    // Early arrival (more than 3 minutes)
                    statusColor = COLOR_DELAYED;
                    statusIndicator = INDICATOR_DELAYED;
                } else if ("DELAYED".equals(arrivalStatus) || deviation > 300000) {
                    // Delayed arrival (more than 5 minutes)
                    statusColor = COLOR_DELAYED;
                    statusIndicator = INDICATOR_DELAYED;
                } else {
                    // On time
                    statusColor = COLOR_ON_TIME;
                    statusIndicator = INDICATOR_ON_TIME;
                }
            } else {
                // Arriving later (show time)
                etaText = TIME_FORMAT.format(new Date(eta));
                
                if (!isPredicted) {
                    statusColor = COLOR_SCHEDULED;
                    statusIndicator = INDICATOR_SCHEDULED;
                } else {
                    statusColor = COLOR_ON_TIME;
                    statusIndicator = INDICATOR_ON_TIME;
                }
            }
            
            // Set status indicator (small dot below minutes)

            // Set ETA text and color - this is the large number on the right
            cardView.setTextViewText(R.id.eta, etaText);
            cardView.setTextColor(R.id.eta, statusColor);
            
            // Show or hide the "min" label based on the type of arrival
            if (minutes < 60 && minutes > 0) {
                // Show "min" for normal minute-based arrivals
                cardView.setViewVisibility(R.id.eta_min, View.VISIBLE);
            } else {
                // Hide for "now" or clock time displays
                cardView.setViewVisibility(R.id.eta_min, View.GONE);
            }
            
            // Format detailed status text (time of arrival)
            String arrivalTimeText = "Arriving at " + TIME_FORMAT.format(new Date(eta));
            cardView.setTextViewText(R.id.status, arrivalTimeText);
            
            // Create status pill for early/delay if needed
            if (isPredicted && (
                    "EARLY".equals(arrivalStatus) || deviation < -180000 ||
                    "DELAYED".equals(arrivalStatus) || deviation > 300000)) {
                
                cardView.setViewVisibility(R.id.status_pill, View.VISIBLE);
                
                String pillText;
                int pillColor;
                
                if ("EARLY".equals(arrivalStatus) || deviation < -180000) {
                    int earlyMins = Math.abs((int)(deviation / 60000));
                    pillText = earlyMins + " min early";
                    pillColor = COLOR_DELAYED; // We use red for early too
                } else {
                    int delayMins = (int)(deviation / 60000);
                    pillText = delayMins + " min delay";
                    pillColor = COLOR_DELAYED;
                }
                
                cardView.setTextViewText(R.id.status_pill, pillText);
                cardView.setInt(R.id.status_pill, "setBackgroundColor", pillColor);
            } else {
                cardView.setViewVisibility(R.id.status_pill, View.GONE);
            }
            

            
            // Add the card to the container
            views.addView(R.id.arrivals_container, cardView);
        }
        
        // If we have more arrivals than we can display, add a note
        if (arrivals.length > displayCount) {
            RemoteViews moreView = new RemoteViews(context.getPackageName(), R.layout.widget_more_arrivals);
            moreView.setTextViewText(R.id.more_arrivals_text, "+" + (arrivals.length - displayCount) + 
                    " more arrival" + (arrivals.length - displayCount > 1 ? "s" : ""));
            views.addView(R.id.arrivals_container, moreView);
        }
    }
} 