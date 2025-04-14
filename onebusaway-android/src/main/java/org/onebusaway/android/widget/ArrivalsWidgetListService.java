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
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.onebusaway.android.R;
import org.onebusaway.android.io.elements.ObaArrivalInfo;
import org.onebusaway.android.io.request.ObaArrivalInfoRequest;
import org.onebusaway.android.io.request.ObaArrivalInfoResponse;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RemoteViewsService that provides a scrollable arrival list for the widget
 */
public class ArrivalsWidgetListService extends RemoteViewsService {
    private static final String TAG = "ArrivalsWidgetListSvc";

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
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

        private Context mContext;
        private int mWidgetId;
        private String mStopId;
        private List<ObaArrivalInfo> mArrivals = new ArrayList<>();
        private boolean mIsNarrow = false;

        public ArrivalsRemoteViewsFactory(Context context, Intent intent) {
            mContext = context;
            mWidgetId = intent.getIntExtra("appWidgetId", -1);
            mStopId = intent.getStringExtra("stopId");
            mIsNarrow = intent.getBooleanExtra("isNarrow", false);
            Log.d(TAG, "Created factory for widget ID: " + mWidgetId + ", stop ID: " + mStopId);
        }

        @Override
        public void onCreate() {
            // Nothing to do
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
            mArrivals.clear();
        }

        @Override
        public int getCount() {
            return mArrivals.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position >= mArrivals.size()) {
                return null;
            }
            
            ObaArrivalInfo arrival = mArrivals.get(position);
            
            // Create a RemoteViews for the card
            RemoteViews cardView = new RemoteViews(mContext.getPackageName(), R.layout.widget_arrival_card);
            
            // Set route name and color
            String routeName = !TextUtils.isEmpty(arrival.getShortName()) ? 
                arrival.getShortName() : arrival.getRouteId();
            cardView.setTextViewText(R.id.route_name, routeName);
            
            // Try to set the route color if available
            try {
                int routeColor = arrival.getRouteColor() != 0 ? 
                    arrival.getRouteColor() : Color.parseColor("#1976D2"); // Default blue
                cardView.setInt(R.id.route_name, "setBackgroundColor", routeColor);
                cardView.setTextColor(R.id.route_name, Color.WHITE);
            } catch (Exception e) {
                Log.e(TAG, "Error setting route color", e);
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
            
            // Calculate time difference between now and arrival
            long now = System.currentTimeMillis();
            long minutes = (eta - now) / 60000;
            
            // Format ETA text
            String etaText;
            int statusColor;
            
            if (minutes <= 0) {
                // Arriving now
                etaText = "now";
                statusColor = COLOR_NOW;
            } else if (minutes < 60) {
                // Arriving within the hour - just the number for minutes
                etaText = String.valueOf(minutes);
                
                // Determine status color based on prediction and status
                if (!isPredicted) {
                    statusColor = COLOR_SCHEDULED;
                } else if ("EARLY".equals(arrival.getStatus()) || 
                        (arrival.getScheduledArrivalTime() > 0 && 
                         arrival.getPredictedArrivalTime() - arrival.getScheduledArrivalTime() < -180000)) {
                    statusColor = COLOR_DELAYED;
                } else if ("DELAYED".equals(arrival.getStatus()) ||
                        (arrival.getScheduledArrivalTime() > 0 && 
                         arrival.getPredictedArrivalTime() - arrival.getScheduledArrivalTime() > 300000)) {
                    statusColor = COLOR_DELAYED;
                } else {
                    statusColor = COLOR_ON_TIME;
                }
            } else {
                // Arriving later (show time)
                etaText = TIME_FORMAT.format(new Date(eta));
                statusColor = isPredicted ? COLOR_ON_TIME : COLOR_SCHEDULED;
            }
            
            // Set ETA text and color
            cardView.setTextViewText(R.id.eta, etaText);
            cardView.setTextColor(R.id.eta, statusColor);
            cardView.setTextColor(R.id.eta_min, statusColor);
            
            // Show or hide the "min" label for ETA
            if (minutes < 60 && minutes > 0) {
                cardView.setInt(R.id.eta_min, "setVisibility", 0); // View.VISIBLE
            } else {
                cardView.setInt(R.id.eta_min, "setVisibility", 8); // View.GONE
            }
            
            // Set arrival time text
            String arrivalTimeText;
            if (mIsNarrow) {
                arrivalTimeText = TIME_FORMAT.format(new Date(eta));
                
                // Hide status text in very narrow widgets
                int minWidth = mContext.getResources().getDimensionPixelSize(R.dimen.widget_min_width);
                if (minWidth < 180) {
                    cardView.setInt(R.id.status, "setVisibility", 8); // View.GONE
                }
            } else {
                arrivalTimeText = "Arriving at " + TIME_FORMAT.format(new Date(eta));
            }
            cardView.setTextViewText(R.id.status, arrivalTimeText);
            
            // Set status pill for early/delayed arrivals
            if (isPredicted && 
                (("EARLY".equals(arrival.getStatus()) || 
                  (arrival.getScheduledArrivalTime() > 0 && 
                   arrival.getPredictedArrivalTime() - arrival.getScheduledArrivalTime() < -180000)) || 
                 ("DELAYED".equals(arrival.getStatus()) ||
                  (arrival.getScheduledArrivalTime() > 0 && 
                   arrival.getPredictedArrivalTime() - arrival.getScheduledArrivalTime() > 300000)))) {
                
                cardView.setInt(R.id.status_pill, "setVisibility", 0); // View.VISIBLE
                
                String pillText;
                
                if ("EARLY".equals(arrival.getStatus()) || 
                    (arrival.getScheduledArrivalTime() > 0 && 
                     arrival.getPredictedArrivalTime() - arrival.getScheduledArrivalTime() < -180000)) {
                    
                    long deviation = arrival.getScheduledArrivalTime() - arrival.getPredictedArrivalTime();
                    int earlyMins = (int)(deviation / 60000);
                    
                    if (mIsNarrow) {
                        pillText = "early";
                    } else {
                        pillText = earlyMins + " min early";
                    }
                } else {
                    long deviation = arrival.getPredictedArrivalTime() - arrival.getScheduledArrivalTime();
                    int delayMins = (int)(deviation / 60000);
                    
                    if (mIsNarrow) {
                        pillText = "delay";
                    } else {
                        pillText = delayMins + " min delay";
                    }
                }
                
                cardView.setTextViewText(R.id.status_pill, pillText);
                cardView.setInt(R.id.status_pill, "setBackgroundColor", COLOR_DELAYED);
            } else {
                cardView.setInt(R.id.status_pill, "setVisibility", 8); // View.GONE
            }
            
            // Set up fill-in intent for individual items
            Bundle extras = new Bundle();
            extras.putString("stopId", mStopId);
            extras.putString("routeId", arrival.getRouteId());
            extras.putInt("position", position);
            
            Intent fillInIntent = new Intent();
            fillInIntent.putExtras(extras);
            cardView.setOnClickFillInIntent(R.id.route_name, fillInIntent);
            
            return cardView;
        }

        @Override
        public RemoteViews getLoadingView() {
            return null; // Use default loading view
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }
    }
} 