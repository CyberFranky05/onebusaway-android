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
import android.util.LruCache;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import android.appwidget.AppWidgetManager;

import org.onebusaway.android.R;
import org.onebusaway.android.io.elements.ObaArrivalInfo;
import org.onebusaway.android.io.request.ObaArrivalInfoRequest;
import org.onebusaway.android.io.request.ObaArrivalInfoResponse;
import org.onebusaway.android.provider.ObaContract;
import org.onebusaway.android.ui.ArrivalInfo;
import org.onebusaway.android.util.UIUtils;
import org.onebusaway.android.widget.WidgetUtil;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeoutException;
import android.os.Handler;
import android.os.Looper;

/**
 * RemoteViewsService that provides a scrollable arrival list for the widget
 */
public class ArrivalsWidgetListService extends RemoteViewsService {
    private static final String TAG = "ArrivalsWidgetListSvc";
    
    // Static cache to share data across service instances
    private static final Map<String, CachedArrivals> sArrivalsCache = new ConcurrentHashMap<>();
    private static final Map<String, Integer> sRouteColorCache = new ConcurrentHashMap<>();
    
    // Shorter cache timeout - 15 seconds to ensure fresh data while avoiding excessive API calls
    private static final long CACHE_TIMEOUT_MS = 15 * 1000;
    
    // Add a flag to prevent multiple simultaneous requests for the same stop
    private static final Set<String> sCurrentlyFetching = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    // Background executor for cache cleanup
    private static final ScheduledExecutorService sCacheCleanupExecutor = 
            Executors.newSingleThreadScheduledExecutor();
    
    // Network request executor - limit to 2 concurrent requests
    private static final ExecutorService sNetworkExecutor = Executors.newFixedThreadPool(2);
    
    static {
        // Schedule cache cleanup every 2 minutes
        sCacheCleanupExecutor.scheduleAtFixedRate(() -> {
            try {
                cleanupCache();
            } catch (Exception e) {
                Log.e(TAG, "Error during cache cleanup", e);
            }
        }, 2, 2, TimeUnit.MINUTES);
    }
    
    /**
     * Clean up expired cache entries
     */
    private static void cleanupCache() {
        long now = System.currentTimeMillis();
        int removedEntries = 0;
        
        // Remove expired entries from arrivals cache
        for (Iterator<Map.Entry<String, CachedArrivals>> it = sArrivalsCache.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, CachedArrivals> entry = it.next();
            if (now - entry.getValue().timestamp > CACHE_TIMEOUT_MS) {
                it.remove();
                removedEntries++;
            }
        }
        
        // Log cache stats
        Log.d(TAG, "Cache cleanup completed. Removed " + removedEntries + " entries. Remaining entries: " + 
                sArrivalsCache.size() + ", route colors: " + sRouteColorCache.size());
    }

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        Log.d(TAG, "Creating RemoteViewsFactory");
        
        // Disable OneSignal in widget processes to prevent login errors
        WidgetUtil.disableOneSignalInWidgetProcess(this);
        
        // Check for required parameters to avoid NPE
        if (intent == null) {
            Log.e(TAG, "Intent is null when creating factory");
            return new ArrivalsRemoteViewsFactory(this.getApplicationContext(), new Intent());
        }
        
        int appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 
                AppWidgetManager.INVALID_APPWIDGET_ID);
        
        Log.d(TAG, "Creating factory for widget ID: " + appWidgetId);
        
        // Safe to create factory now - don't notify at this stage
        return new ArrivalsRemoteViewsFactory(this.getApplicationContext(), intent);
    }
    
    /**
     * Class to hold cached arrival data
     */
    private static class CachedArrivals {
        final List<ObaArrivalInfo> arrivals;
        final long timestamp;
        int hitCount = 0;
        
        CachedArrivals(List<ObaArrivalInfo> arrivals) {
            this.arrivals = arrivals;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TIMEOUT_MS;
        }
        
        void registerHit() {
            hitCount++;
        }
    }

    /**
     * RemoteViewsFactory for arrivals list items
     */
    private static class ArrivalsRemoteViewsFactory implements RemoteViewsService.RemoteViewsFactory {
        private static final String TAG = "ArrivalsWidgetList";
        
        private static final int MAX_ARRIVALS = 10; // Limit number of arrivals to improve performance
        
        // Color constants
        private static final int COLOR_ON_TIME = Color.parseColor("#008000"); // Green
        private static final int COLOR_DELAYED = Color.parseColor("#b71c1c"); // Red
        private static final int COLOR_SCHEDULED = Color.parseColor("#757575"); // Gray
        private static final int COLOR_NOW = Color.parseColor("#D32F2F"); // Bright red
        
        private Context mContext;
        private String mStopId;
        private String mStopName;
        private int mAppWidgetId;
        private org.onebusaway.android.io.request.ObaArrivalInfoResponse mResponse;
        private List<org.onebusaway.android.ui.ArrivalInfo> mArrivalInfo = new ArrayList<>();
        private long mLastUpdated = 0;
        
        public ArrivalsRemoteViewsFactory(Context context, Intent intent) {
            mContext = context.getApplicationContext();
            mAppWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            mStopId = intent.getStringExtra("stopId");
            mStopName = intent.getStringExtra("stopName");
            
            Log.d(TAG, "RemoteViewsFactory initialized: widgetId=" + mAppWidgetId + ", stopId=" + mStopId + ", stopName=" + mStopName);
        }

        @Override
        public void onCreate() {
            Log.d(TAG, "onCreate");
        }

        @Override
        public void onDestroy() {
            Log.d(TAG, "onDestroy");
            mArrivalInfo.clear();
        }

        @Override
        public int getCount() {
            return mArrivalInfo.size();
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= mArrivalInfo.size()) {
                Log.e(TAG, "Invalid position: " + position);
                return null;
            }
            
            org.onebusaway.android.ui.ArrivalInfo info = mArrivalInfo.get(position);
            RemoteViews rv = new RemoteViews(mContext.getPackageName(), R.layout.arrival_list_widget_item);
            
            // Set the route name (number)
            rv.setTextViewText(R.id.route_name, info.getInfo().getShortName());
            
            // Set the route color
            int colorCode = getRouteColor(info);
            rv.setTextColor(R.id.route_name, colorCode);
            
            // Set the headsign
            rv.setTextViewText(R.id.headsign, info.getInfo().getHeadsign());
            
            // Set arrival time
            rv.setTextViewText(R.id.eta, info.getTimeText());
            
            // Set status text and color
            rv.setTextViewText(R.id.status, info.getStatusText());
            int statusColor = getStatusColor(info);
            rv.setTextColor(R.id.status, statusColor);
            
            // Set the list item click intent with position data for handling
            Intent fillInIntent = new Intent();
            fillInIntent.putExtra(FavoriteStopWidgetProvider.EXTRA_ARRIVAL_INFO, position);
            rv.setOnClickFillInIntent(R.id.arrival_item, fillInIntent);
            
            // Make the entire item clickable
            rv.setOnClickFillInIntent(R.id.arrival_item_layout, fillInIntent);
            
            return rv;
        }

        /**
         * Get the appropriate color for a route
         */
        private int getRouteColor(org.onebusaway.android.ui.ArrivalInfo info) {
            if (mResponse != null && mResponse.getRefs() != null) {
                org.onebusaway.android.io.elements.ObaRoute route = mResponse.getRefs().getRoute(info.getInfo().getRouteId());
                if (route != null && !TextUtils.isEmpty(route.getColor())) {
                    return Color.parseColor("#" + route.getColor());
                }
            }
            // Default color if no route color available
            return mContext.getResources().getColor(R.color.default_route_color);
        }

        /**
         * Get color based on arrival status
         */
        private int getStatusColor(org.onebusaway.android.ui.ArrivalInfo info) {
            // Get arrival status from ArrivalInfo
            if (info.getPredicted()) {
                // Real-time prediction
                if (info.getEta() <= 0) {
                    return COLOR_NOW; // Arriving now or arrived
                } else if (info.getColor() == UIUtils.OnTimeColor) {
                    return COLOR_ON_TIME;
                } else if (info.getColor() == UIUtils.EarlyColor || 
                        info.getColor() == UIUtils.DelayedColor) {
                    return COLOR_DELAYED;
                }
            }
            // Default to scheduled
            return COLOR_SCHEDULED;
        }

        @Override
        public RemoteViews getLoadingView() {
            // Use default loading view
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        /**
         * Called when the data for the widget should be updated
         */
        @Override
        public void onDataSetChanged() {
            Log.d(TAG, "onDataSetChanged");
            
            // Don't refresh data too frequently (at most every 30 seconds)
            long currentTime = System.currentTimeMillis();
            if (currentTime - mLastUpdated < MIN_REFRESH_INTERVAL_MS && mArrivalInfo.size() > 0) {
                Log.d(TAG, "Using cached data (updated " + (currentTime - mLastUpdated) + "ms ago)");
                return;
            }
            
            // Clear existing data
            mArrivalInfo.clear();
            
            if (mStopId == null) {
                Log.e(TAG, "No stop ID");
                return;
            }
            
            // Fetch arrival times
            org.onebusaway.android.io.request.ObaArrivalInfoRequest.Builder builder = 
                    new org.onebusaway.android.io.request.ObaArrivalInfoRequest.Builder(mContext, mStopId);
            
            // Use default values instead of explicit minutes setting
            org.onebusaway.android.io.request.ObaArrivalInfoRequest request = builder.build();
            
            try {
                mResponse = request.call();
                if (mResponse.getCode() == org.onebusaway.android.io.ObaApi.OBA_OK) {
                    org.onebusaway.android.io.elements.ObaStop stop = mResponse.getStop();
                    final org.onebusaway.android.io.elements.ObaArrivalInfo[] arrivals = mResponse.getArrivalInfo();
                    
                    if (arrivals != null && arrivals.length > 0) {
                        // Convert ObaArrivalInfo to ArrivalInfo using utility method
                        mArrivalInfo = org.onebusaway.android.util.ArrivalInfoUtils.convertObaArrivalInfo(
                                mContext,
                                arrivals, 
                                null, 
                                System.currentTimeMillis(), 
                                true);
                        
                        // Limit the number of arrivals to improve performance
                        if (mArrivalInfo.size() > MAX_ARRIVALS) {
                            mArrivalInfo = mArrivalInfo.subList(0, MAX_ARRIVALS);
                        }
                        
                        mLastUpdated = currentTime;
                        Log.d(TAG, "Updated arrivals data: found " + mArrivalInfo.size() + " arrivals");
                    } else {
                        Log.d(TAG, "No arrivals found");
                    }
                } else {
                    Log.e(TAG, "Error code: " + mResponse.getCode());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching arrivals: " + e.getMessage(), e);
            }
        }
        
        // Minimum time in ms between refreshes to prevent excessive API calls
        private static final long MIN_REFRESH_INTERVAL_MS = 30 * 1000; // 30 seconds
    }
}