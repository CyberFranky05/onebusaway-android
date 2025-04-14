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
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import android.appwidget.AppWidgetManager;

import org.onebusaway.android.R;
import org.onebusaway.android.io.elements.ObaArrivalInfo;
import org.onebusaway.android.io.request.ObaArrivalInfoRequest;
import org.onebusaway.android.io.request.ObaArrivalInfoResponse;
import org.onebusaway.android.provider.ObaContract;
import org.onebusaway.android.util.UIUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * RemoteViewsService that provides a scrollable arrival list for the widget
 */
public class ArrivalsWidgetListService extends RemoteViewsService {
    private static final String TAG = "ArrivalsWidgetListSvc";

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        // Always trigger onDataSetChanged when the service starts
        // This ensures the list is always populated and scrollable
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(this);
        int appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 
                AppWidgetManager.INVALID_APPWIDGET_ID);
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            try {
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.arrivals_list);
            } catch (Exception e) {
                Log.e(TAG, "Error notifying data changes during factory creation", e);
            }
        }
        
        return new ArrivalsRemoteViewsFactory(this.getApplicationContext(), intent);
    }

    /**
     * RemoteViewsFactory for arrivals list items
     */
    private static class ArrivalsRemoteViewsFactory implements RemoteViewsService.RemoteViewsFactory {
        private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("h:mm a", Locale.getDefault());
        private static final int MAX_ARRIVALS = 10;
        
        // Color constants
        private static final int COLOR_ON_TIME = Color.parseColor("#008000"); // Green
        private static final int COLOR_DELAYED = Color.parseColor("#b71c1c"); // Red
        private static final int COLOR_SCHEDULED = Color.parseColor("#757575"); // Gray
        private static final int COLOR_NOW = Color.parseColor("#D32F2F"); // Bright red

        // Status constants
        private static final String DEVIATION_EARLY = "early";
        private static final String DEVIATION_DELAYED = "delayed";
        private static final String DEVIATION_ON_TIME = "ontime";
        
        // Intent extras for the widget
        private static final String EXTRA_ARRIVAL_INFO = "arrival_info";

        // Cache the application context to prevent memory leaks
        private final Context mContext;
        private final int mWidgetId;
        private String mStopId;
        // Use ArrayList for better performance with indexed access
        private final List<ObaArrivalInfo> mArrivals = new ArrayList<>();
        private final boolean mIsNarrow;
        private final int mSizeCategory; // 0=small, 1=medium, 2=large, 3=full-width
        
        // Always use maximum available arrivals for scrolling
        // This ensures proper scrolling behavior even if the widget height changes
        private final int mMaxArrivalsToShow = MAX_ARRIVALS;

        // Keep a cache of RemoteViews to improve performance
        private final int mLayoutResourceId;

        public ArrivalsRemoteViewsFactory(Context context, Intent intent) {
            // Always use application context to prevent memory leaks
            mContext = context.getApplicationContext();
            mWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            mStopId = intent.getStringExtra("stopId");
            mIsNarrow = intent.getBooleanExtra("isNarrow", false);
            mSizeCategory = intent.getIntExtra("sizeCategory", 1); // Default to medium if not specified
            
            // Just use the standard layout for all sizes - we'll adjust the content dynamically
            mLayoutResourceId = R.layout.widget_arrival_card;
            
            Log.d(TAG, "Created factory for widget ID: " + mWidgetId + 
                  ", stop ID: " + mStopId + ", size category: " + mSizeCategory +
                  ", max arrivals: " + mMaxArrivalsToShow);
        }

        @Override
        public void onCreate() {
            // Pre-allocate the arrivals list
            mArrivals.clear();
        }

        @Override
        public void onDataSetChanged() {
            // Load arrival data
            mArrivals.clear();
            
            if (TextUtils.isEmpty(mStopId)) {
                Log.d(TAG, "No stop ID provided, skipping arrivals fetch");
                return;
            }
            
            try {
                // First, ensure the app is properly initialized
                FavoriteStopWidgetProvider.initializeAppIfNeeded(mContext);
                
                // Get current stop ID
                String stopId = FavoriteStopWidgetProvider.getStopIdForWidget(mContext, mWidgetId);
                if (stopId == null) {
                    stopId = mStopId;
                } else {
                    mStopId = stopId;
                }
                
                if (TextUtils.isEmpty(stopId)) {
                    Log.d(TAG, "No stop ID available for widget " + mWidgetId);
                    return;
                }
                
                Log.d(TAG, "Loading arrivals for stop: " + stopId);
                
                // Fetch arrival data
                ObaArrivalInfoRequest request = ObaArrivalInfoRequest.newRequest(mContext, stopId);
                ObaArrivalInfoResponse response = request.call();
                
                if (response == null || response.getCode() != 200) {
                    Log.e(TAG, "Error fetching arrivals");
                    return;
                }
                
                ObaArrivalInfo[] arrivals = response.getArrivalInfo();
                if (arrivals != null && arrivals.length > 0) {
                    // Add arrivals to our list (limit to MAX_ARRIVALS)
                    int count = Math.min(arrivals.length, MAX_ARRIVALS);
                    for (int i = 0; i < count; i++) {
                        mArrivals.add(arrivals[i]);
                    }
                    Log.d(TAG, "Loaded " + mArrivals.size() + " arrivals");
                } else {
                    Log.d(TAG, "No arrivals available for stop");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading arrivals", e);
            }
        }

        @Override
        public void onDestroy() {
            // Clean up resources here
        }

        @Override
        public int getCount() {
            // Return the number of items in the data set
            return mArrivals.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            // Position will be -1 sometimes when called from the framework
            if (position < 0 || position >= mArrivals.size()) {
                return null;
            }

            // Get arrival info
            ObaArrivalInfo arrival = mArrivals.get(position);
            
            // Create remote views for this arrival
            RemoteViews rv = new RemoteViews(mContext.getPackageName(), R.layout.widget_arrival_card);
            
            // Route information
            rv.setTextViewText(R.id.route_name, arrival.getShortName());
            
            // Set route color if available
            int color = getRouteColor(arrival);
            rv.setTextColor(R.id.route_name, color);
            rv.setInt(R.id.route_name, "setBackgroundColor", color);
            
            // Set headsign/destination
            rv.setTextViewText(R.id.destination, arrival.getHeadsign());
            
            // Calculate ETA in minutes
            long eta = calculateEta(arrival);
            rv.setTextViewText(R.id.eta, formatMinutes(eta));
            
            // Set ETA color based on status
            int statusColor = getStatusColor(arrival);
            rv.setTextColor(R.id.eta, statusColor);
            rv.setTextColor(R.id.eta_min, statusColor);
            
            // Status text
            String statusText = formatStatusText(arrival, eta);
            rv.setTextViewText(R.id.status, statusText);
            
            // Set click intent for this arrival
            Intent intent = new Intent();
            intent.putExtra(EXTRA_ARRIVAL_INFO, arrival);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mWidgetId);
            rv.setOnClickFillInIntent(R.id.route_name, intent);
            
            return rv;
        }

        @Override
        public RemoteViews getLoadingView() {
            // Return a view to display while the list is loading
            return null;
        }

        @Override
        public int getViewTypeCount() {
            // Return the number of types of views that will be created by this factory
            return 1;
        }

        @Override
        public long getItemId(int position) {
            // Return the stable ID of the item at position
            return position;
        }

        @Override
        public boolean hasStableIds() {
            // Return true if the IDs are stable across changes to the adapter's data
            return true;
        }

        /**
         * Calculate ETA in minutes
         */
        private long calculateEta(ObaArrivalInfo arrival) {
            long now = System.currentTimeMillis();
            long arrivalTime = arrival.getPredicted() ? 
                    arrival.getPredictedArrivalTime() : 
                    arrival.getScheduledArrivalTime();
            
            // Convert to minutes, round up if necessary
            return Math.max(0, (arrivalTime - now) / 60000);
        }
        
        /**
         * Format minutes for display
         */
        private String formatMinutes(long minutes) {
            if (minutes <= 0) {
                return "0";
            }
            return String.valueOf(minutes);
        }

        /**
         * Format status text based on arrival info
         */
        private String formatStatusText(ObaArrivalInfo arrival, long etaMinutes) {
            if (etaMinutes <= 0) {
                return "Arriving now";
            }
            
            SimpleDateFormat timeFormat = new SimpleDateFormat("h:mm a", Locale.getDefault());
            long arrivalTime = arrival.getPredicted() ? 
                    arrival.getPredictedArrivalTime() : 
                    arrival.getScheduledArrivalTime();
            
            String time = timeFormat.format(arrivalTime);
            
            if (arrival.getPredicted()) {
                return "Arriving at " + time;
            } else {
                return "Scheduled: " + time;
            }
        }

        /**
         * Get color for route display
         */
        private int getRouteColor(ObaArrivalInfo arrival) {
            try {
                return Color.parseColor("#" + arrival.getRouteColor());
            } catch (Exception e) {
                return Color.BLACK;
            }
        }
        
        /**
         * Get color based on arrival status
         */
        private int getStatusColor(ObaArrivalInfo arrival) {
            long eta = calculateEta(arrival);
            if (eta < 0) {
                return COLOR_NOW;
            }
            
            String status = arrival.getStatus();
            if (DEVIATION_EARLY.equals(status)) {
                return COLOR_DELAYED; // We use red for early too
            } else if (DEVIATION_DELAYED.equals(status)) {
                return COLOR_DELAYED;
            } else {
                return COLOR_ON_TIME;
            }
        }
    }
}