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
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

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
                
                // Create a new route badge background with the specific color
                int red = Color.red(routeColor);
                int green = Color.green(routeColor);
                int blue = Color.blue(routeColor);
                String colorString = String.format("#%02X%02X%02X", red, green, blue);
                
                // We can't directly set a dynamic background color with custom shape 
                // in RemoteViews, so we'll just use setTextColor instead
                cardView.setTextColor(R.id.route_name, Color.WHITE);
                cardView.setTextColor(R.id.eta, routeColor);
            } catch (Exception e) {
                Log.e(TAG, "Error setting route color", e);
                // Use default colors if there's an error
            }
            
            // Set destination
            String headsign = !TextUtils.isEmpty(arrival.getHeadsign()) ?
                arrival.getHeadsign() : "Unknown destination";
            cardView.setTextViewText(R.id.destination, headsign);
            
            // Calculate arrival time
            long eta = arrival.getPredictedArrivalTime();
            boolean isPredicted = true;
            if (eta == 0) {
                eta = arrival.getScheduledArrivalTime();
                isPredicted = false;
            }
            
            long now = System.currentTimeMillis();
            long minutes = (eta - now) / 60000;
            
            // Format ETA text
            String etaText;
            if (minutes <= 0) {
                etaText = "now";
            } else if (minutes < 60) {
                etaText = minutes + " min";
            } else {
                etaText = TIME_FORMAT.format(new Date(eta));
            }
            cardView.setTextViewText(R.id.eta, etaText);
            
            // Format detailed status text
            String arrivalTimeText = TIME_FORMAT.format(new Date(eta));
            String statusType = isPredicted ? "Estimated" : "Scheduled";
            String statusText = statusType + ": " + etaText + " (" + arrivalTimeText + ")";
            cardView.setTextViewText(R.id.status, statusText);
            
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