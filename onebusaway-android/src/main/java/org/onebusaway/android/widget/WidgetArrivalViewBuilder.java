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
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import org.onebusaway.android.R;
import org.onebusaway.android.io.elements.ObaArrivalInfo;
import org.onebusaway.android.util.ArrivalInfoUtils;
import org.onebusaway.android.util.UIUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Helper class to build arrival views for the widget
 */
public class WidgetArrivalViewBuilder {

    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("h:mm a", Locale.getDefault());

    /**
     * Creates RemoteViews for an arrival item that can be added to the widget
     *
     * @param context Application context
     * @param arrival Arrival information to display
     * @return RemoteViews for the arrival item
     */
    public static RemoteViews buildArrivalView(Context context, ObaArrivalInfo arrival) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_arrival_item);

        // Get route information
        String routeName = arrival.getShortName();
        if (TextUtils.isEmpty(routeName)) {
            routeName = arrival.getRouteId();
        }

        // Get destination
        String headsign = arrival.getHeadsign();
        if (TextUtils.isEmpty(headsign)) {
            headsign = "No destination info";
        }

        // Get route color - use default if none provided
        int color = arrival.getRouteColor();
        if (color == 0) {
            color = context.getResources().getColor(R.color.theme_primary);
        }

        // Calculate ETA
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

        // Format status text
        String statusText = isPredicted ? "Real-time" : "Scheduled";

        // Set text in views
        views.setTextViewText(R.id.route_name, routeName);
        views.setTextViewText(R.id.destination, headsign);
        views.setTextViewText(R.id.eta, etaText);
        views.setTextViewText(R.id.status, statusText);

        // Set route color for badge
        views.setInt(R.id.route_name, "setBackgroundColor", color);

        // Set ETA text color based on real-time/scheduled
        int etaColor = isPredicted ? Color.BLACK : context.getResources().getColor(R.color.stop_info_scheduled_time);
        views.setTextColor(R.id.eta, etaColor);

        return views;
    }

    /**
     * Adds arrival views to the widget
     *
     * @param views RemoteViews for the widget
     * @param context Application context
     * @param arrivals Array of arrival information
     * @param maxArrivals Maximum number of arrivals to display
     */
    public static void addArrivalsToWidget(RemoteViews views, Context context, ObaArrivalInfo[] arrivals, int maxArrivals) {
        // Clear previous views
        views.removeAllViews(R.id.arrivals_container);
        
        if (arrivals == null || arrivals.length == 0) {
            // Show no arrivals message
            views.setTextViewText(R.id.no_arrivals, "No upcoming arrivals at this time");
            views.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
            return;
        }

        // Hide no arrivals message
        views.setViewVisibility(R.id.no_arrivals, View.GONE);
        
        // Add arrival views, limiting to maxArrivals
        int count = Math.min(arrivals.length, maxArrivals);
        for (int i = 0; i < count; i++) {
            RemoteViews arrivalView = buildArrivalView(context, arrivals[i]);
            views.addView(R.id.arrivals_container, arrivalView);
        }
    }
} 