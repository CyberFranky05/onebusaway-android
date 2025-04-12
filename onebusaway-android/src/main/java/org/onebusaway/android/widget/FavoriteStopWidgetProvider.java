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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import org.onebusaway.android.R;
import org.onebusaway.android.app.Application;
import org.onebusaway.android.io.elements.ObaArrivalInfo;
import org.onebusaway.android.io.request.ObaArrivalInfoRequest;
import org.onebusaway.android.io.request.ObaArrivalInfoResponse;
import org.onebusaway.android.provider.ObaContract;
import org.onebusaway.android.ui.ArrivalsListActivity;
import org.onebusaway.android.ui.HomeActivity;
import org.onebusaway.android.util.UIUtils;
import org.onebusaway.android.ui.ArrivalsListFragment;
import org.onebusaway.android.io.elements.ObaRegion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;

/**
 * Widget Provider for displaying the top starred stop
 */
public class FavoriteStopWidgetProvider extends AppWidgetProvider {
    private static final String TAG = "FavoriteStopWidget";

    public static final String ACTION_REFRESH = "org.onebusaway.android.widget.ACTION_REFRESH";
    public static final String ACTION_AUTO_REFRESH = "org.onebusaway.android.widget.ACTION_AUTO_REFRESH";
    public static final String ACTION_SHOW_STOP_DROPDOWN = "org.onebusaway.android.widget.ACTION_SHOW_STOP_DROPDOWN";
    public static final String ACTION_SELECT_STOP = "org.onebusaway.android.widget.ACTION_SELECT_STOP";
    public static final String EXTRA_STOP_ID = "org.onebusaway.android.widget.EXTRA_STOP_ID";
    public static final String EXTRA_STOP_NAME = "org.onebusaway.android.widget.EXTRA_STOP_NAME";
    public static final String EXTRA_WIDGET_ID = "org.onebusaway.android.widget.EXTRA_WIDGET_ID";
    
    // Auto-refresh interval in milliseconds (5 minutes)
    private static final long AUTO_REFRESH_INTERVAL_MS = 5 * 60 * 1000;
    
    // Keep track of the currently selected stop for each widget
    private static final java.util.Map<Integer, String> sSelectedStops = new java.util.HashMap<>();
    
    // Constants for arrivals display
    private static final int DEFAULT_ARRIVALS_TO_SHOW = 3;
    private static final int MAX_ARRIVALS_TO_SHOW = 3;
    private static final HashMap<Integer, Integer> sArrivalsToShow = new HashMap<>();
    
    // Intent action for loading more arrivals
    public static final String ACTION_LOAD_MORE = "org.onebusaway.android.widget.ACTION_LOAD_MORE";
    
    private static final String ACTION_DATABASE_CHANGED = "org.onebusaway.android.DATABASE_CHANGED";
    
    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        Log.d(TAG, "onUpdate called for " + appWidgetIds.length + " widgets");
        
        // Update each widget in sequence
        for (int appWidgetId : appWidgetIds) {
            try {
                // Store the number of arrivals we want to display
                if (!sArrivalsToShow.containsKey(appWidgetId)) {
                    sArrivalsToShow.put(appWidgetId, DEFAULT_ARRIVALS_TO_SHOW);
                    Log.d(TAG, "Setting default arrivals count for widget " + appWidgetId);
                }
                
                // First check if all required resources are available
                if (!checkResources(context, appWidgetManager, appWidgetId)) {
                    Log.e(TAG, "Resource check failed for widget " + appWidgetId + ", skipping normal update");
                    continue;
                }
                
                // First force update widget with loading state for immediate feedback
                forceWidgetVisible(context, appWidgetManager, appWidgetId);
                
                // Then start a background process to update with real data after a slight delay
                new Thread(() -> {
                    try {
                        // Small delay to allow the loading state to render
                        Thread.sleep(150);
                        
                        // First update with initial state
                        updateWidgetInitialState(context, appWidgetManager, appWidgetId);
                        
                        // Small delay to make sure UI is updated
                        Thread.sleep(250);
                        
                        // Then update with real data
                        updateWidget(context, appWidgetManager, appWidgetId);
                        
                        // Schedule an automatic refresh for this widget
                        scheduleAutoRefresh(context, appWidgetId);
                    } catch (Exception e) {
                        Log.e(TAG, "Error updating widget " + appWidgetId + ": " + e.getMessage(), e);
                        try {
                            // Try to get a basic display up in case of error
                            RemoteViews errorViews = new RemoteViews(context.getPackageName(), R.layout.widget_favorite_stop);
                            errorViews.setViewVisibility(R.id.widget_layout, View.VISIBLE);
                            errorViews.setTextViewText(R.id.stop_name, "Widget Error");
                            errorViews.setTextViewText(R.id.direction, "Could not update");
                            errorViews.setInt(R.id.widget_layout, "setBackgroundResource", R.drawable.widget_background);
                            appWidgetManager.updateAppWidget(appWidgetId, errorViews);
                        } catch (Exception ex) {
                            Log.e(TAG, "Failed to display error state: " + ex.getMessage(), ex);
                        }
                    }
                }).start();
            } catch (Exception e) {
                Log.e(TAG, "Error in onUpdate for widget " + appWidgetId + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * Force widget to be immediately visible with minimal loading state
     * This helps avoid blank widgets on initial placement
     */
    private void forceWidgetVisible(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        try {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_favorite_stop);
            
            // Make key elements visible
            views.setViewVisibility(R.id.widget_layout, View.VISIBLE);
            views.setViewVisibility(R.id.widget_header, View.VISIBLE);
            views.setViewVisibility(R.id.stop_selector, View.VISIBLE);
            
            // Force backgrounds to be visible
            views.setInt(R.id.widget_layout, "setBackgroundResource", R.drawable.widget_background);
            views.setInt(R.id.widget_header, "setBackgroundResource", R.drawable.widget_header_background);
            
            // Set minimal text
            views.setTextViewText(R.id.stop_name, "OneBusAway");
            views.setTextViewText(R.id.direction, "Loading widget...");
            
            // Update immediately
            appWidgetManager.updateAppWidget(appWidgetId, views);
            Log.d(TAG, "Forced widget " + appWidgetId + " to visible state");
        } catch (Exception e) {
            Log.e(TAG, "Error in forceWidgetVisible: " + e.getMessage(), e);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "onReceive: " + action);
        
        // Force refresh for database changes
        if (ACTION_DATABASE_CHANGED.equals(action)) {
            updateAllWidgets(context);
            return;
        }
        
        // Handle system boot completed to ensure widget starts working after device restart
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            Log.d(TAG, "Device boot completed, updating widgets");
            updateAllWidgets(context);
            return;
        }
        
        // Handle refresh action
        if (ACTION_REFRESH.equals(action)) {
            int appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 
                    AppWidgetManager.INVALID_APPWIDGET_ID);
                    
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID && validateWidgetId(context, appWidgetId)) {
                Log.d(TAG, "Refreshing widget ID: " + appWidgetId);
                AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
                updateWidgetInitialState(context, appWidgetManager, appWidgetId);
                updateWidget(context, appWidgetManager, appWidgetId);
            } else {
                Log.d(TAG, "Refreshing all widgets (ID not specified or invalid)");
                forceRefreshAllWidgets(context);
            }
            return;
        }
        
        // Handle stop selection
        if (ACTION_SELECT_STOP.equals(action)) {
            int appWidgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            String stopId = intent.getStringExtra(EXTRA_STOP_ID);
            String stopName = intent.getStringExtra(EXTRA_STOP_NAME);
            
            Log.d(TAG, "Received ACTION_SELECT_STOP for widget " + appWidgetId + 
                   ", stop ID: " + stopId + ", stop name: " + stopName);
            
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID && stopId != null) {
                // Validate widget ID
                if (!validateWidgetId(context, appWidgetId)) {
                    Log.e(TAG, "Widget ID is not valid: " + appWidgetId);
                    
                    // Try to refresh all widgets anyway
                    forceRefreshAllWidgets(context);
                    return;
                }
                
                Log.d(TAG, "Setting selected stop for widget " + appWidgetId + ": " + stopId);
                
                // Store the selected stop for this widget
                sSelectedStops.put(appWidgetId, stopId);
                
                // Update widget with the selected stop
                AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
                updateWidgetWithStopId(context, appWidgetManager, appWidgetId, stopId);
                
                // Log current selected stops map for debugging
                StringBuilder sb = new StringBuilder("Current selected stops: ");
                for (Integer widgetId : sSelectedStops.keySet()) {
                    sb.append("[Widget ").append(widgetId).append(" -> ")
                      .append(sSelectedStops.get(widgetId)).append("] ");
                }
                Log.d(TAG, sb.toString());
            } else {
                Log.e(TAG, "Invalid widget ID or stop ID in ACTION_SELECT_STOP. Widget ID: " + 
                      appWidgetId + ", Stop ID: " + stopId);
            }
            return;
        }
        
        // Handle debug action for force refreshing
        if ("org.onebusaway.android.DEBUG_UPDATE_WIDGET".equals(action)) {
            Log.d(TAG, "Debug force refresh requested");
            forceRefreshAllWidgets(context);
            return;
        }
        
        super.onReceive(context, intent);
    }

    @Override
    public void onEnabled(Context context) {
        super.onEnabled(context);
        Log.d(TAG, "Widget provider enabled - registering receivers");
        
        // Register to receive database updates
        try {
            IntentFilter filter = new IntentFilter(ACTION_DATABASE_CHANGED);
            context.getApplicationContext().registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Log.d(TAG, "Received database change notification, updating all widgets");
                    updateAllWidgets(context);
                }
            }, filter);
        } catch (Exception e) {
            Log.e(TAG, "Error registering database receiver: " + e.getMessage(), e);
        }
    }

    /**
     * Updates all widget instances
     */
    private void updateAllWidgets(Context context) {
        try {
            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
            int[] appWidgetIds = appWidgetManager.getAppWidgetIds(new ComponentName(context, FavoriteStopWidgetProvider.class));
            Log.d(TAG, "Updating all " + appWidgetIds.length + " widgets");
            
            for (int appWidgetId : appWidgetIds) {
                forceCompleteRefresh(context, appWidgetManager, appWidgetId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating all widgets: " + e.getMessage(), e);
        }
    }

    @Override
    public void onDisabled(Context context) {
        Log.d(TAG, "onDisabled called");
        // Cancel any pending auto-refresh alarms
        cancelAllAutoRefresh(context);
        // Clean up all maps
        sSelectedStops.clear();
        sArrivalsToShow.clear();
        super.onDisabled(context);
    }
    
    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        Log.d(TAG, "onDeleted called for " + appWidgetIds.length + " widgets");
        // Cancel auto-refresh for each deleted widget
        for (int appWidgetId : appWidgetIds) {
            cancelAutoRefresh(context, appWidgetId);
            sSelectedStops.remove(appWidgetId); // Clean up selected stops map
            sArrivalsToShow.remove(appWidgetId); // Clean up arrivals count map
        }
        super.onDeleted(context, appWidgetIds);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager, 
                                        int appWidgetId, Bundle newOptions) {
        Log.d(TAG, "onAppWidgetOptionsChanged called for widget: " + appWidgetId);
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
        
        // When the widget is resized, refresh it to ensure proper display
        updateWidget(context, appWidgetManager, appWidgetId);
    }

    /**
     * Updates widget with initial state, ensuring all UI elements are correctly set up
     */
    private void updateWidgetInitialState(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        try {
            Log.d(TAG, "Setting up initial state for widget ID: " + appWidgetId);
            
            // Create a new RemoteViews with the layout
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_favorite_stop);
            
            // First, make all key layout elements visible to ensure proper sizing
            views.setViewVisibility(R.id.widget_layout, View.VISIBLE);
            views.setViewVisibility(R.id.widget_header, View.VISIBLE);
            views.setViewVisibility(R.id.stop_selector, View.VISIBLE);
            views.setViewVisibility(R.id.dropdown_arrow, View.VISIBLE);
            views.setViewVisibility(R.id.widget_refresh, View.VISIBLE);
            
            // Explicitly set the background to ensure it's rendered
            views.setInt(R.id.widget_layout, "setBackgroundResource", R.drawable.widget_background);
            views.setInt(R.id.widget_header, "setBackgroundResource", R.drawable.widget_header_background);
            
            // Set default loading message while we initialize
            views.setTextViewText(R.id.stop_name, "Loading...");
            views.setTextViewText(R.id.direction, "Initializing widget");
            
            // Control visibility of no_starred_stops - initially show
            views.setViewVisibility(R.id.no_starred_stops, View.VISIBLE);
            views.setTextViewText(R.id.no_starred_stops, "Loading widget data...");
            
            // Hide arrivals list, show loading message for arrivals
            views.setViewVisibility(R.id.arrivals_list, View.GONE);
            views.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
            views.setTextViewText(R.id.no_arrivals, "Please wait...");
            
            // Set up app icon to launch main activity
            Intent startAppIntent = new Intent(context, HomeActivity.class);
            startAppIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent startAppPendingIntent = PendingIntent.getActivity(context, 0, startAppIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.app_icon, startAppPendingIntent);
            views.setOnClickPendingIntent(R.id.no_starred_stops, startAppPendingIntent);
            
            // Set up refresh button
            Intent refreshIntent = new Intent(context, FavoriteStopWidgetProvider.class);
            refreshIntent.setAction(ACTION_REFRESH);
            PendingIntent refreshPendingIntent = PendingIntent.getBroadcast(context, 0, refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent);
            
            // Log update and update widget
            Log.d(TAG, "Updating initial widget " + appWidgetId + " at " + System.currentTimeMillis());
            appWidgetManager.updateAppWidget(appWidgetId, views);
        } catch (Exception e) {
            Log.e(TAG, "Error in updateWidgetInitialState: " + e.getMessage(), e);
            // Try to display an error message
            try {
                RemoteViews errorViews = new RemoteViews(context.getPackageName(), R.layout.widget_favorite_stop);
                errorViews.setViewVisibility(R.id.widget_layout, View.VISIBLE);
                errorViews.setTextViewText(R.id.stop_name, "Widget Error");
                errorViews.setTextViewText(R.id.direction, "Please check logs");
                errorViews.setInt(R.id.widget_layout, "setBackgroundResource", R.drawable.widget_background);
                appWidgetManager.updateAppWidget(appWidgetId, errorViews);
            } catch (Exception ex) {
                Log.e(TAG, "Failed to display error state: " + ex.getMessage(), ex);
            }
        }
    }

    private void updateWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        Log.d(TAG, "updateWidget(" + appWidgetId + ") started");
        
        // Create new RemoteViews to avoid caching issues
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_favorite_stop);
        
        // Explicitly force all key elements to be visible to ensure proper sizing
        views.setViewVisibility(R.id.widget_layout, View.VISIBLE);
        views.setViewVisibility(R.id.widget_header, View.VISIBLE);
        views.setViewVisibility(R.id.stop_selector, View.VISIBLE);
        views.setViewVisibility(R.id.dropdown_arrow, View.VISIBLE);
        views.setViewVisibility(R.id.widget_refresh, View.VISIBLE);
        views.setViewVisibility(R.id.no_starred_stops, View.VISIBLE);
        
        // Explicitly set backgrounds to ensure they're rendered
        views.setInt(R.id.widget_layout, "setBackgroundResource", R.drawable.widget_background);
        views.setInt(R.id.widget_header, "setBackgroundResource", R.drawable.widget_header_background);
        
        // Set default content
        views.setTextViewText(R.id.stop_name, "LOADING...");
        views.setTextViewText(R.id.direction, "Please wait");
        views.setTextViewText(R.id.no_starred_stops, "Widget loading...");
        
        // Set intent to open the app when widget header is clicked
        Intent startAppIntent = new Intent(context, HomeActivity.class);
        startAppIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent startAppPendingIntent = PendingIntent.getActivity(context, 0, startAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.app_icon, startAppPendingIntent);
        
        // Set intent for refreshing the widget
        Intent refreshIntent = new Intent(context, FavoriteStopWidgetProvider.class);
        refreshIntent.setAction(ACTION_REFRESH);
        refreshIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        PendingIntent refreshPendingIntent = PendingIntent.getBroadcast(context, appWidgetId, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent);
        
        // Update the widget immediately to show loading state
        appWidgetManager.updateAppWidget(appWidgetId, views);
        
        Log.d(TAG, "Initial loading state set for widget: " + appWidgetId);
        
        // Load stop data in a background thread but with shorter delay to improve responsiveness
        new Thread(() -> {
            try {
                // Small delay to ensure widget is rendered before loading data
                Thread.sleep(250);
                loadStopData(context, appWidgetManager, appWidgetId);
            } catch (Exception e) {
                Log.e(TAG, "Error in updateWidget thread: " + e.getMessage(), e);
            }
        }).start();
    }

    private void loadStopData(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        Log.d(TAG, "loadStopData started for widget: " + appWidgetId);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_favorite_stop);
        
        // Force visibility of key elements for proper sizing
        views.setViewVisibility(R.id.widget_layout, View.VISIBLE);
        views.setViewVisibility(R.id.widget_header, View.VISIBLE);
        views.setViewVisibility(R.id.stop_selector, View.VISIBLE);
        views.setViewVisibility(R.id.dropdown_arrow, View.VISIBLE);
        views.setViewVisibility(R.id.widget_refresh, View.VISIBLE);
        
        // Explicitly set backgrounds to ensure they're rendered
        views.setInt(R.id.widget_layout, "setBackgroundResource", R.drawable.widget_background);
        views.setInt(R.id.widget_header, "setBackgroundResource", R.drawable.widget_header_background);
        
        // Set loading text
        views.setTextViewText(R.id.stop_name, "LOADING STOPS...");
        views.setTextViewText(R.id.direction, "Please wait");
        
        // Control visibility of no_starred_stops - only show when needed
        views.setViewVisibility(R.id.no_starred_stops, View.VISIBLE);
        views.setTextViewText(R.id.no_starred_stops, "Loading starred stops...");
        
        // Update status with loading state before database query
        appWidgetManager.updateAppWidget(appWidgetId, views);
        
        // Get the top starred stop from the database
        Cursor c = null;
        long startTime = System.currentTimeMillis();
        
        try {
            String selection = ObaContract.Stops.FAVORITE + "=1";
            ObaRegion region = Application.get().getCurrentRegion();
            if (region != null) {
                selection += " AND " + ObaContract.Stops.REGION_ID + "=" + region.getId();
                Log.d(TAG, "Using region ID: " + region.getId() + " for stop query");
            } else {
                Log.d(TAG, "No current region found for widget query");
            }
            
            // Log query before execution
            Log.d(TAG, "Query selection: " + selection);
            
            c = context.getContentResolver().query(
                    ObaContract.Stops.CONTENT_URI,
                    new String[]{
                            ObaContract.Stops._ID,
                            ObaContract.Stops.CODE,
                            ObaContract.Stops.NAME,
                            ObaContract.Stops.DIRECTION,
                            ObaContract.Stops.UI_NAME,
                            ObaContract.Stops.FAVORITE,
                            ObaContract.Stops.USE_COUNT
                    },
                    selection,
                    null,
                    ObaContract.Stops.USE_COUNT + " DESC LIMIT 1");
            
            int count = (c != null) ? c.getCount() : 0;
            Log.d(TAG, "Query returned " + count + " stops");
            
            if (c != null && c.moveToFirst()) {
                // We have at least one starred stop
                String stopId = c.getString(c.getColumnIndex(ObaContract.Stops._ID));
                String stopCode = c.getString(c.getColumnIndex(ObaContract.Stops.CODE));
                String stopName = c.getString(c.getColumnIndex(ObaContract.Stops.NAME));
                String stopDirection = c.getString(c.getColumnIndex(ObaContract.Stops.DIRECTION));
                String uiName = c.getString(c.getColumnIndex(ObaContract.Stops.UI_NAME));
                
                Log.d(TAG, "Found stop: " + stopId + ", code: " + stopCode + ", name: " + stopName + ", uiName: " + uiName + ", dir: " + stopDirection);
                
                // Make sure to display text even if there are nulls
                String displayName = !TextUtils.isEmpty(uiName) ? uiName : (stopName != null ? stopName : "Starred Stop");
                String displayDirection = stopDirection != null ? UIUtils.getStopDirectionString(stopDirection) : "";
                
                Log.d(TAG, "Widget display texts - name: '" + displayName + "', direction: '" + displayDirection + "'");
                
                // Set the stop name and direction - use a bolder font for the stop name
                views.setTextViewText(R.id.stop_name, displayName.toUpperCase());
                views.setTextViewText(R.id.direction, displayDirection);
                
                // Update status text with timing info
                long elapsed = System.currentTimeMillis() - startTime;
                String formattedTime = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(new java.util.Date());
                views.setTextViewText(R.id.no_starred_stops, "Last updated: " + formattedTime);
                
                // Get arrival information
                views.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
                views.setViewVisibility(R.id.arrivals_list, View.GONE);
                views.setTextViewText(R.id.no_arrivals, "Fetching arrivals...");
                appWidgetManager.updateAppWidget(appWidgetId, views);
                
                loadArrivals(context, appWidgetManager, appWidgetId, views, stopId);
                
                // Create an Intent to launch ArrivalsListActivity when clicked
                Intent intent = new Intent(context, ArrivalsListActivity.class);
                intent.setData(Uri.withAppendedPath(ObaContract.Stops.CONTENT_URI, stopId));
                intent.putExtra(ArrivalsListFragment.STOP_NAME, stopName);
                intent.putExtra(ArrivalsListFragment.STOP_DIRECTION, stopDirection);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, 
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                
                // Make the header and stop name clickable to open stop detail
                views.setOnClickPendingIntent(R.id.widget_header, pendingIntent);
                views.setOnClickPendingIntent(R.id.arrivals_list, pendingIntent);
                views.setOnClickPendingIntent(R.id.no_arrivals, pendingIntent);
                
                Log.d(TAG, "Updating widget with stop data");
                appWidgetManager.updateAppWidget(appWidgetId, views);
            } else {
                Log.d(TAG, "No starred stops found in the database");
                // Update views for the "no starred stops" state
                views.setTextViewText(R.id.stop_name, "NO STARRED STOPS");
                views.setTextViewText(R.id.direction, "Star a stop in the app");
                
                views.setViewVisibility(R.id.arrivals_list, View.GONE);
                views.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
                views.setTextViewText(R.id.no_arrivals, "Add favorites in OBA app");
                
                // Make sure this text is visible when there are no starred stops
                views.setViewVisibility(R.id.no_starred_stops, View.VISIBLE);
                views.setTextViewText(R.id.no_starred_stops, "Tap to open app");
                
                // Set up intent to open main app
                Intent intent = new Intent(context, HomeActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, 
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                
                // Make entire widget clickable to open app
                views.setOnClickPendingIntent(R.id.widget_header, pendingIntent);
                views.setOnClickPendingIntent(R.id.no_arrivals, pendingIntent);
                views.setOnClickPendingIntent(R.id.no_starred_stops, pendingIntent);
                
                appWidgetManager.updateAppWidget(appWidgetId, views);
                Log.d(TAG, "No starred stops found, updated widget with message");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying for starred stops: " + e.getMessage(), e);
            // Update widget with error message
            views.setTextViewText(R.id.stop_name, "Error loading stop");
            views.setTextViewText(R.id.direction, "Check logs for details");
            
            views.setViewVisibility(R.id.arrivals_list, View.GONE);
            views.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
            views.setTextViewText(R.id.no_arrivals, "Error: " + 
                    (e.getMessage() != null ? e.getMessage() : "Unknown error"));
            
            views.setViewVisibility(R.id.no_starred_stops, View.VISIBLE);
            views.setTextViewText(R.id.no_starred_stops, "Error occurred");
            
            // Set up intent to open main app
            Intent intent = new Intent(context, HomeActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, 
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            
            // Make parts of widget clickable to open app
            views.setOnClickPendingIntent(R.id.no_arrivals, pendingIntent);
            views.setOnClickPendingIntent(R.id.no_starred_stops, pendingIntent);
            
            appWidgetManager.updateAppWidget(appWidgetId, views);
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    /**
     * Loads arrival information for a stop and updates the widget
     */
    private void loadArrivals(Context context, AppWidgetManager appWidgetManager, int appWidgetId,
                             RemoteViews views, String stopId) {
        try {
            Log.d(TAG, "Loading arrivals for stop " + stopId);
            
            if (stopId == null) {
                Log.e(TAG, "Cannot load arrivals - stopId is null");
                views.setViewVisibility(R.id.arrivals_list, View.GONE);
                views.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
                views.setTextViewText(R.id.no_arrivals, "Error: Stop ID is missing");
                appWidgetManager.updateAppWidget(appWidgetId, views);
                return;
            }
            
            // Update widget to show we're loading
            views.setViewVisibility(R.id.arrivals_list, View.GONE);
            views.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
            views.setTextViewText(R.id.no_arrivals, "Fetching arrivals...");
            
            // Show refresh animation while loading
            views.setImageViewResource(R.id.widget_refresh, R.drawable.refresh_animation);
            
            appWidgetManager.updateAppWidget(appWidgetId, views);
            
            // Check if we have network connectivity
            if (!isNetworkAvailable(context)) {
                Log.e(TAG, "Network is not available");
                views.setViewVisibility(R.id.arrivals_list, View.GONE);
                views.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
                views.setTextViewText(R.id.no_arrivals, "No network connection");
                appWidgetManager.updateAppWidget(appWidgetId, views);
                
                // Set up retry with network connectivity info
                setupNetworkRetry(context, appWidgetManager, appWidgetId, views, stopId);
                return;
            }
            
            // Run network operation in a background thread
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Get the OBA client
                        Log.d(TAG, "Making API request for stop " + stopId);
                        ObaArrivalInfoResponse response = new ObaArrivalInfoRequest.Builder(context, stopId)
                                .build()
                                .call();
                        Log.d(TAG, "API request completed for stop " + stopId);

                        // Update the widget with the new data on the main thread
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    // Reset refresh button to static icon after loading
                                    views.setImageViewResource(R.id.widget_refresh, R.drawable.ic_refresh_white_24dp);
                                    
                                    ObaArrivalInfo[] arrivalInfoArray = response.getArrivalInfo();
                                    Log.d(TAG, "Received " + (arrivalInfoArray != null ? arrivalInfoArray.length : 0) + " arrivals");
                                    
                                    if (arrivalInfoArray == null || arrivalInfoArray.length == 0) {
                                        // No arrivals
                                        views.setViewVisibility(R.id.arrivals_list, View.GONE);
                                        views.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
                                        views.setTextViewText(R.id.no_arrivals, "No upcoming arrivals");
                                        appWidgetManager.updateAppWidget(appWidgetId, views);
                                        return;
                                    }

                                    // We have arrivals - hide message, show list
                                    views.setViewVisibility(R.id.no_arrivals, View.GONE);
                                    views.setViewVisibility(R.id.arrivals_list, View.VISIBLE);
                                    
                                    // Configure each arrival row
                                    int rowsConfigured = 0;
                                    
                                    for (ObaArrivalInfo arrivalInfo : arrivalInfoArray) {
                                        if (arrivalInfo == null) {
                                            Log.w(TAG, "Skipping null arrival info");
                                            continue;
                                        }
                                        
                                        if (rowsConfigured >= MAX_ARRIVALS_TO_SHOW) break; // Show max 3 arrivals
                                        
                                        rowsConfigured++;
                                        int rowId = rowsConfigured;
                                        
                                        String routeId = arrivalInfo.getRouteId();
                                        String routeName = arrivalInfo.getShortName();
                                        String headsign = arrivalInfo.getHeadsign();
                                        
                                        // Add check for null data
                                        if (routeName == null) routeName = "?";
                                        if (headsign == null) headsign = "Unknown";
                                        
                                        long scheduledTime = arrivalInfo.getScheduledArrivalTime();
                                        long expectedTime = arrivalInfo.getPredictedArrivalTime();
                                        
                                        // Use expected time if available, otherwise use scheduled time
                                        long arrivalTime = (expectedTime > 0) ? expectedTime : scheduledTime;
                                        
                                        // Calculate minutes until arrival
                                        long now = System.currentTimeMillis();
                                        long minutesUntil = (arrivalTime - now) / 60000;
                                        
                                        String timeText;
                                        boolean isPredicted = expectedTime > 0;
                                        if (minutesUntil <= 0) {
                                            timeText = "Due";
                                        } else if (minutesUntil == 1) {
                                            timeText = "1 min";
                                        } else if (minutesUntil < 30) {
                                            timeText = minutesUntil + " min";
                                        } else {
                                            // Format as time for arrivals more than 30 minutes away
                                            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.US);
                                            timeText = sdf.format(new java.util.Date(arrivalTime));
                                        }
                                        
                                        Log.d(TAG, "Row " + rowId + ": Route=" + routeName + ", Headsign=" + headsign + ", Time=" + timeText);
                                        
                                        // Set the row info
                                        int routeViewId = getResourceId(context, "route_" + rowId, "id");
                                        int destViewId = getResourceId(context, "destination_" + rowId, "id");
                                        int timeViewId = getResourceId(context, "time_" + rowId, "id");
                                        int arrivingTimeViewId = getResourceId(context, "arriving_time_" + rowId, "id");
                                        int rowViewId = getResourceId(context, "arrival_row_" + rowId, "id");
                                        int statusViewId = getResourceId(context, "status_" + rowId, "id");
                                        
                                        if (routeViewId == 0 || destViewId == 0 || timeViewId == 0 || arrivingTimeViewId == 0 || rowViewId == 0) {
                                            Log.e(TAG, "Failed to find resource IDs for row " + rowId);
                                            continue;
                                        }
                                        
                                        // Setup route and destination
                                        views.setTextViewText(routeViewId, routeName);
                                        views.setTextColor(routeViewId, context.getResources().getColor(R.color.theme_primary));
                                        
                                        // Set destination with scheduled indicator if needed
                                        String destinationText = headsign;
                                        if (!isPredicted) {
                                            destinationText = "* " + destinationText;
                                        }
                                        views.setTextViewText(destViewId, destinationText);
                                        views.setTextColor(destViewId, context.getResources().getColor(android.R.color.black));
                                        
                                        // Format arrival information with main time in top-right and arriving time below destination
                                        String formattedArrivalTime = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(new java.util.Date(arrivalTime));
                                        
                                        // Get arrival status (early/late/on time) like in the app
                                        String statusText = "";
                                        int statusColor = context.getResources().getColor(R.color.stop_info_ontime);
                                        boolean hasStatusText = false;
                                        
                                        if (isPredicted && expectedTime != scheduledTime) {
                                            long diffMinutes = (expectedTime - scheduledTime) / 60000;
                                            if (diffMinutes < -1) {
                                                statusText = Math.abs(diffMinutes) + " min early";
                                                statusColor = context.getResources().getColor(R.color.stop_info_early);
                                                hasStatusText = true;
                                            } else if (diffMinutes > 1) {
                                                statusText = diffMinutes + " min late";
                                                statusColor = context.getResources().getColor(R.color.stop_info_delayed);
                                                hasStatusText = true;
                                            }
                                        }
                                        
                                        // Set the main time (top right) and color based on schedule status
                                        int timeColor = getArrivalTimeColor(context, isPredicted, scheduledTime, expectedTime);
                                        views.setTextColor(timeViewId, timeColor);
                                        
                                        if (minutesUntil <= 0) {
                                            views.setTextViewText(timeViewId, "DUE NOW");
                                            views.setTextViewText(arrivingTimeViewId, "Arriving now" + statusText);
                                        } else if (minutesUntil == 1) {
                                            views.setTextViewText(timeViewId, "1 MIN");
                                            views.setTextViewText(arrivingTimeViewId, "Arriving: " + formattedArrivalTime + statusText);
                                        } else if (minutesUntil < 30) {
                                            views.setTextViewText(timeViewId, minutesUntil + " MIN");
                                            views.setTextViewText(arrivingTimeViewId, "Arriving: " + formattedArrivalTime + statusText);
                                        } else {
                                            views.setTextViewText(timeViewId, "ARRIVING");
                                            views.setTextViewText(arrivingTimeViewId, formattedArrivalTime + statusText);
                                        }
                                        
                                        // Apply status color to arriving time text
                                        if (!statusText.isEmpty()) {
                                            views.setTextColor(arrivingTimeViewId, statusColor);
                                        } else {
                                            views.setTextColor(arrivingTimeViewId, context.getResources().getColor(R.color.theme_primary));
                                        }

                                        // Make the row visible
                                        views.setViewVisibility(rowViewId, View.VISIBLE);
                                        
                                        // Set up click for each row
                                        try {
                                            Intent intent = new Intent(context, ArrivalsListActivity.class);
                                            intent.setData(Uri.withAppendedPath(ObaContract.Stops.CONTENT_URI, stopId));
                                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                            PendingIntent pendingIntent = PendingIntent.getActivity(context, rowId, intent, 
                                                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                                            views.setOnClickPendingIntent(rowViewId, pendingIntent);
                                        } catch (Exception e) {
                                            Log.e(TAG, "Error setting click listener for row " + rowId + ": " + e.getMessage());
                                        }
                                    }
                                    
                                    // Hide any remaining rows
                                    for (int i = rowsConfigured + 1; i <= MAX_ARRIVALS_TO_SHOW; i++) {
                                        int rowViewId = getResourceId(context, "arrival_row_" + i, "id");
                                        if (rowViewId != 0) {
                                            views.setViewVisibility(rowViewId, View.GONE);
                                        }
                                    }
                                    
                                    // Show legend text if any arrivals are scheduled (not real-time)
                                    boolean hasScheduledArrivals = false;
                                    for (ObaArrivalInfo arrivalInfo : arrivalInfoArray) {
                                        if (arrivalInfo.getPredictedArrivalTime() <= 0) {
                                            hasScheduledArrivals = true;
                                            break;
                                        }
                                    }
                                    views.setViewVisibility(R.id.legend_text, hasScheduledArrivals ? View.VISIBLE : View.GONE);
                                    
                                    // Last updated timestamp
                                    String formattedTime = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(new java.util.Date());
                                    views.setTextViewText(R.id.no_starred_stops, "Updated " + formattedTime);
                                    
                                    // Apply the update
                                    appWidgetManager.updateAppWidget(appWidgetId, views);
                                    Log.d(TAG, "Successfully updated widget with " + rowsConfigured + " arrivals");
                                    
                                } catch (Exception e) {
                                    Log.e(TAG, "Error in arrivals background thread: " + e.getMessage(), e);
                                    views.setViewVisibility(R.id.arrivals_list, View.GONE);
                                    views.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
                                    views.setTextViewText(R.id.no_arrivals, "Could not load arrivals: " + 
                                            (e.getMessage() != null ? e.getMessage() : "Unknown error"));
                                    views.setImageViewResource(R.id.widget_refresh, R.drawable.ic_refresh_white_24dp);
                                    appWidgetManager.updateAppWidget(appWidgetId, views);
                                }
                            }
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Error in arrivals background thread: " + e.getMessage(), e);
                        
                        // Update on the main thread to avoid crashes
                        new Handler(Looper.getMainLooper()).post(() -> {
                            try {
                                views.setViewVisibility(R.id.arrivals_list, View.GONE);
                                views.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
                                views.setTextViewText(R.id.no_arrivals, "Could not load arrivals: " + 
                                        (e.getMessage() != null ? e.getMessage() : "Unknown error"));
                                views.setImageViewResource(R.id.widget_refresh, R.drawable.ic_refresh_white_24dp);
                                appWidgetManager.updateAppWidget(appWidgetId, views);
                            } catch (Exception ex) {
                                Log.e(TAG, "Failed to update widget with error: " + ex.getMessage());
                            }
                        });
                    }
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "Error loading arrivals: " + e.getMessage(), e);
            views.setViewVisibility(R.id.arrivals_list, View.GONE);
            views.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
            views.setTextViewText(R.id.no_arrivals, "Could not load arrivals: " + 
                    (e.getMessage() != null ? e.getMessage() : "Unknown error"));
            views.setImageViewResource(R.id.widget_refresh, R.drawable.ic_refresh_white_24dp);
            appWidgetManager.updateAppWidget(appWidgetId, views);
        }
    }

    /**
     * Check if network is available
     */
    private boolean isNetworkAvailable(Context context) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) 
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        } catch (Exception e) {
            Log.e(TAG, "Error checking network availability: " + e.getMessage());
            return true; // Assume network is available to avoid false negatives
        }
    }

    /**
     * Set up retry with network connectivity info
     */
    private void setupNetworkRetry(Context context, AppWidgetManager appWidgetManager, int appWidgetId, 
                                 RemoteViews views, String stopId) {
        try {
            // Setup a refresh button to retry
            Intent refreshIntent = new Intent(context, FavoriteStopWidgetProvider.class);
            refreshIntent.setAction(ACTION_REFRESH);
            PendingIntent refreshPendingIntent = PendingIntent.getBroadcast(context, 0, refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            
            views.setOnClickPendingIntent(R.id.no_arrivals, refreshPendingIntent);
            
            appWidgetManager.updateAppWidget(appWidgetId, views);
        } catch (Exception e) {
            Log.e(TAG, "Error setting up network retry: " + e.getMessage());
        }
    }

    /**
     * Set up automatic retry after a short delay
     */
    private void setupRetry(Context context, AppWidgetManager appWidgetManager, int appWidgetId, String stopId) {
        try {
            // Schedule a retry after 30 seconds
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Send a refresh broadcast
                        Intent refreshIntent = new Intent(context, FavoriteStopWidgetProvider.class);
                        refreshIntent.setAction(ACTION_REFRESH);
                        context.sendBroadcast(refreshIntent);
                    } catch (Exception e) {
                        Log.e(TAG, "Error in retry handler: " + e.getMessage());
                    }
                }
            }, 30000); // 30 seconds
        } catch (Exception e) {
            Log.e(TAG, "Error setting up automatic retry: " + e.getMessage());
        }
    }

    /**
     * Helper method to get resource ID by name
     */
    private int getResourceId(Context context, String name, String type) {
        return context.getResources().getIdentifier(name, type, context.getPackageName());
    }

    /**
     * Forces a complete refresh of the widget to avoid RemoteViews caching issues
     * This creates a completely new RemoteViews instance and updates all fields
     */
    private void forceCompleteRefresh(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        Log.d(TAG, "Forcing complete widget refresh for ID: " + appWidgetId);
        
        try {
            // Create new RemoteViews to avoid caching issues
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_favorite_stop);
            
            // Set text for loading state
            views.setTextViewText(R.id.stop_name, "Refreshing Widget");
            views.setTextViewText(R.id.direction, "Please wait...");
            
            views.setViewVisibility(R.id.arrivals_list, View.GONE);
            views.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
            views.setTextViewText(R.id.no_arrivals, "Refreshing arrival information...");
            
            views.setTextViewText(R.id.no_starred_stops, "Refresh started at " + 
                new java.text.SimpleDateFormat("h:mm:ss a", java.util.Locale.US).format(new java.util.Date()));
            
            // Show refresh animation
            views.setImageViewResource(R.id.widget_refresh, R.drawable.refresh_animation);
            
            // Set up refresh button
            Intent refreshIntent = new Intent(context, FavoriteStopWidgetProvider.class);
            refreshIntent.setAction(ACTION_REFRESH);
            PendingIntent refreshPendingIntent = PendingIntent.getBroadcast(context, 0, refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent);
            
            // Update immediately with loading state
            appWidgetManager.updateAppWidget(appWidgetId, views);
            
            // Schedule real update after a short delay
            new Thread(() -> {
                try {
                    // Give the widget time to render
                    Thread.sleep(250);
                    updateWidget(context, appWidgetManager, appWidgetId);
                } catch (Exception e) {
                    Log.e(TAG, "Error in force refresh thread: " + e.getMessage(), e);
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "Error in forceCompleteRefresh: " + e.getMessage(), e);
            // Fall back to regular update
            updateWidget(context, appWidgetManager, appWidgetId);
        }
    }

    /**
     * Updates a widget with a specific stop ID
     */
    private void updateWidgetWithStopId(Context context, AppWidgetManager appWidgetManager, 
                                      int appWidgetId, String stopId) {
        Log.d(TAG, "Updating widget " + appWidgetId + " with stop ID: " + stopId);
        
        // Create new RemoteViews and show loading state
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_favorite_stop);
        views.setTextViewText(R.id.stop_name, "Loading stop...");
        views.setTextViewText(R.id.direction, "");
        views.setViewVisibility(R.id.arrivals_list, View.GONE);
        views.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
        views.setTextViewText(R.id.no_arrivals, "Fetching information...");
        
        // Set refresh icon animation to indicate loading
        views.setImageViewResource(R.id.widget_refresh, R.drawable.refresh_animation);
        
        // Make all key views visible to ensure proper sizing
        views.setViewVisibility(R.id.widget_layout, View.VISIBLE);
        views.setViewVisibility(R.id.widget_header, View.VISIBLE);
        views.setViewVisibility(R.id.stop_selector, View.VISIBLE);
        views.setViewVisibility(R.id.dropdown_arrow, View.VISIBLE);
        views.setViewVisibility(R.id.widget_refresh, View.VISIBLE);
        
        // Force the background to be visible
        views.setInt(R.id.widget_layout, "setBackgroundResource", R.drawable.widget_background);
        
        // Set up dropdown button
        Intent dropdownIntent = new Intent(context, StopSelectorActivity.class);
        dropdownIntent.putExtra(EXTRA_WIDGET_ID, appWidgetId);
        dropdownIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent dropdownPendingIntent = PendingIntent.getActivity(context, appWidgetId, dropdownIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.dropdown_arrow, dropdownPendingIntent);
        views.setOnClickPendingIntent(R.id.stop_selector, dropdownPendingIntent);
        views.setOnClickPendingIntent(R.id.widget_header, dropdownPendingIntent);
        
        // Set up refresh button
        Intent refreshIntent = new Intent(context, FavoriteStopWidgetProvider.class);
        refreshIntent.setAction(ACTION_REFRESH);
        refreshIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        PendingIntent refreshPendingIntent = PendingIntent.getBroadcast(context, appWidgetId, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent);
        
        // Update the widget with loading state
        appWidgetManager.updateAppWidget(appWidgetId, views);
        
        // Load stop info and arrivals in a background thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Get stop information - all in one query for performance
                    Cursor c = context.getContentResolver().query(
                            Uri.withAppendedPath(ObaContract.Stops.CONTENT_URI, stopId),
                            new String[]{
                                    ObaContract.Stops.NAME,
                                    ObaContract.Stops.DIRECTION,
                                    ObaContract.Stops.UI_NAME
                            },
                            null, null, null);
                    
                    String stopName = "";
                    String stopDirection = "";
                    String uiName = "";
                    
                    if (c != null && c.moveToFirst()) {
                        stopName = c.getString(c.getColumnIndex(ObaContract.Stops.NAME));
                        stopDirection = c.getString(c.getColumnIndex(ObaContract.Stops.DIRECTION));
                        uiName = c.getString(c.getColumnIndex(ObaContract.Stops.UI_NAME));
                        c.close();
                        
                        // Format display texts
                        String displayName = !TextUtils.isEmpty(uiName) ? uiName : (stopName != null ? stopName : stopId);
                        String displayDirection = stopDirection != null ? UIUtils.getStopDirectionString(stopDirection) : "";
                        
                        // Update the stop info in the RemoteViews
                        RemoteViews updatedViews = new RemoteViews(context.getPackageName(), R.layout.widget_favorite_stop);
                        
                        // Make all key views visible
                        updatedViews.setViewVisibility(R.id.widget_layout, View.VISIBLE);
                        updatedViews.setViewVisibility(R.id.widget_header, View.VISIBLE);
                        updatedViews.setViewVisibility(R.id.stop_selector, View.VISIBLE);
                        updatedViews.setViewVisibility(R.id.dropdown_arrow, View.VISIBLE);
                        updatedViews.setViewVisibility(R.id.widget_refresh, View.VISIBLE);
                        
                        // Set background
                        updatedViews.setInt(R.id.widget_layout, "setBackgroundResource", R.drawable.widget_background);
                        
                        // Set the stop name and direction
                        updatedViews.setTextViewText(R.id.stop_name, displayName.toUpperCase());
                        updatedViews.setTextViewText(R.id.direction, displayDirection);
                        
                        // Set up dropdown button
                        updatedViews.setOnClickPendingIntent(R.id.dropdown_arrow, dropdownPendingIntent);
                        updatedViews.setOnClickPendingIntent(R.id.stop_selector, dropdownPendingIntent);
                        updatedViews.setOnClickPendingIntent(R.id.widget_header, dropdownPendingIntent);
                        
                        // Set up refresh button
                        updatedViews.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent);
                        
                        // Create an Intent to launch ArrivalsListActivity when clicked
                        Intent intent = new Intent(context, ArrivalsListActivity.class);
                        intent.setData(Uri.withAppendedPath(ObaContract.Stops.CONTENT_URI, stopId));
                        intent.putExtra(ArrivalsListFragment.STOP_NAME, stopName);
                        intent.putExtra(ArrivalsListFragment.STOP_DIRECTION, stopDirection);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, 
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                        
                        // Make arrivals lists clickable
                        updatedViews.setOnClickPendingIntent(R.id.arrivals_list, pendingIntent);
                        updatedViews.setOnClickPendingIntent(R.id.no_arrivals, pendingIntent);
                        
                        // Show loading for arrivals
                        updatedViews.setViewVisibility(R.id.arrivals_list, View.GONE);
                        updatedViews.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
                        updatedViews.setTextViewText(R.id.no_arrivals, "Fetching arrivals...");
                        
                        // Show refresh animation while loading
                        updatedViews.setImageViewResource(R.id.widget_refresh, R.drawable.refresh_animation);
                        
                        // Set legend text to hidden initially, it will be updated when arrivals are loaded
                        updatedViews.setViewVisibility(R.id.legend_text, View.GONE);
                        
                        // Update the widget with stop info immediately
                        appWidgetManager.updateAppWidget(appWidgetId, updatedViews);
                        
                        // Now load arrivals directly
                        loadArrivalsDirectly(context, appWidgetManager, appWidgetId, stopId, displayName, stopDirection);
                    } else {
                        Log.e(TAG, "Could not find stop with ID: " + stopId);
                        
                        RemoteViews errorViews = new RemoteViews(context.getPackageName(), R.layout.widget_favorite_stop);
                        errorViews.setTextViewText(R.id.stop_name, "Error: Stop not found");
                        errorViews.setTextViewText(R.id.direction, "");
                        errorViews.setViewVisibility(R.id.arrivals_list, View.GONE);
                        errorViews.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
                        errorViews.setTextViewText(R.id.no_arrivals, "Could not find stop with ID: " + stopId);
                        errorViews.setImageViewResource(R.id.widget_refresh, R.drawable.ic_refresh_white_24dp);
                        
                        // Set up refresh button
                        errorViews.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent);
                        
                        appWidgetManager.updateAppWidget(appWidgetId, errorViews);
                        
                        if (c != null) {
                            c.close();
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error loading stop info: " + e.getMessage(), e);
                    
                    RemoteViews errorViews = new RemoteViews(context.getPackageName(), R.layout.widget_favorite_stop);
                    errorViews.setTextViewText(R.id.stop_name, "Error loading stop");
                    errorViews.setTextViewText(R.id.direction, "");
                    errorViews.setViewVisibility(R.id.arrivals_list, View.GONE);
                    errorViews.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
                    errorViews.setTextViewText(R.id.no_arrivals, "Error: " + e.getMessage());
                    errorViews.setImageViewResource(R.id.widget_refresh, R.drawable.ic_refresh_white_24dp);
                    
                    // Set up refresh button
                    errorViews.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent);
                    
                    appWidgetManager.updateAppWidget(appWidgetId, errorViews);
                }
            }
        }).start();
    }

    /**
     * Optimized version that loads arrivals directly without using additional handlers
     */
    private void loadArrivalsDirectly(Context context, AppWidgetManager appWidgetManager, 
                                     int appWidgetId, String stopId, String displayName, String stopDirection) {
        try {
            Log.d(TAG, "Loading arrivals for stop " + stopId);
            
            if (stopId == null) {
                Log.e(TAG, "Cannot load arrivals - stopId is null");
                return;
            }
            
            // Get the current number of arrivals to show or use default
            int arrivalsToShow = sArrivalsToShow.getOrDefault(appWidgetId, DEFAULT_ARRIVALS_TO_SHOW);
            Log.d(TAG, "Will show up to " + arrivalsToShow + " arrivals for widget " + appWidgetId);
            
            // Check if we have network connectivity
            if (!isNetworkAvailable(context)) {
                Log.e(TAG, "Network is not available");
                
                RemoteViews noNetworkViews = new RemoteViews(context.getPackageName(), R.layout.widget_favorite_stop);
                noNetworkViews.setTextViewText(R.id.stop_name, displayName);
                noNetworkViews.setTextViewText(R.id.direction, stopDirection);
                noNetworkViews.setViewVisibility(R.id.arrivals_list, View.GONE);
                noNetworkViews.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
                noNetworkViews.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
                noNetworkViews.setTextViewText(R.id.no_arrivals, "No network connection");
                noNetworkViews.setImageViewResource(R.id.widget_refresh, R.drawable.ic_refresh_white_24dp);
                
                appWidgetManager.updateAppWidget(appWidgetId, noNetworkViews);
                return;
            }
            
            // Get the OBA client directly on this thread - performance optimization
            try {
                ObaArrivalInfoResponse response = new ObaArrivalInfoRequest.Builder(context, stopId)
                        .build()
                        .call();
                
                // Process results directly
                RemoteViews arrivalsViews = new RemoteViews(context.getPackageName(), R.layout.widget_favorite_stop);
                
                // Reset refresh button to static icon
                arrivalsViews.setImageViewResource(R.id.widget_refresh, R.drawable.ic_refresh_white_24dp);
                
                // Set stop name and direction
                arrivalsViews.setTextViewText(R.id.stop_name, displayName);
                arrivalsViews.setTextViewText(R.id.direction, stopDirection);
                
                // Set up dropdown and refresh
                Intent dropdownIntent = new Intent(context, StopSelectorActivity.class);
                dropdownIntent.putExtra(EXTRA_WIDGET_ID, appWidgetId);
                dropdownIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent dropdownPendingIntent = PendingIntent.getActivity(context, appWidgetId, dropdownIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                arrivalsViews.setOnClickPendingIntent(R.id.dropdown_arrow, dropdownPendingIntent);
                arrivalsViews.setOnClickPendingIntent(R.id.stop_selector, dropdownPendingIntent);
                arrivalsViews.setOnClickPendingIntent(R.id.widget_header, dropdownPendingIntent);
                
                // Set up refresh button
                Intent refreshIntent = new Intent(context, FavoriteStopWidgetProvider.class);
                refreshIntent.setAction(ACTION_REFRESH);
                refreshIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
                PendingIntent refreshPendingIntent = PendingIntent.getBroadcast(context, appWidgetId, refreshIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                arrivalsViews.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent);
                
                // Create an Intent to launch ArrivalsListActivity when clicked
                Intent intent = new Intent(context, ArrivalsListActivity.class);
                intent.setData(Uri.withAppendedPath(ObaContract.Stops.CONTENT_URI, stopId));
                intent.putExtra(ArrivalsListFragment.STOP_NAME, displayName);
                intent.putExtra(ArrivalsListFragment.STOP_DIRECTION, stopDirection);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, 
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                
                // Process arrival info
                ObaArrivalInfo[] arrivalInfoArray = response.getArrivalInfo();
                if (arrivalInfoArray == null || arrivalInfoArray.length == 0) {
                    // No arrivals
                    arrivalsViews.setViewVisibility(R.id.arrivals_list, View.GONE);
                    arrivalsViews.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
                    arrivalsViews.setTextViewText(R.id.no_arrivals, "No upcoming arrivals");
                    arrivalsViews.setOnClickPendingIntent(R.id.no_arrivals, pendingIntent);
                } else {
                    // We have arrivals - hide message, show list
                    arrivalsViews.setViewVisibility(R.id.no_arrivals, View.GONE);
                    arrivalsViews.setViewVisibility(R.id.arrivals_list, View.VISIBLE);
                    
                    // Configure each arrival row
                    int rowsConfigured = 0;
                    
                    for (ObaArrivalInfo arrivalInfo : arrivalInfoArray) {
                        if (arrivalInfo == null) {
                            Log.w(TAG, "Skipping null arrival info");
                            continue;
                        }
                        
                        if (rowsConfigured >= arrivalsToShow) break; // Only show the specified number of arrivals
                        
                        rowsConfigured++;
                        int rowId = rowsConfigured;
                        
                        String routeId = arrivalInfo.getRouteId();
                        String routeName = arrivalInfo.getShortName();
                        String headsign = arrivalInfo.getHeadsign();
                        
                        // Add check for null data
                        if (routeName == null) routeName = "?";
                        if (headsign == null) headsign = "Unknown";
                        
                        long scheduledTime = arrivalInfo.getScheduledArrivalTime();
                        long expectedTime = arrivalInfo.getPredictedArrivalTime();
                        
                        // Use expected time if available, otherwise use scheduled time
                        long arrivalTime = (expectedTime > 0) ? expectedTime : scheduledTime;
                        
                        // Calculate minutes until arrival
                        long now = System.currentTimeMillis();
                        long minutesUntil = (arrivalTime - now) / 60000;
                        
                        String timeText;
                        boolean isPredicted = expectedTime > 0;
                        if (minutesUntil <= 0) {
                            timeText = "Due";
                        } else if (minutesUntil == 1) {
                            timeText = "1 min";
                        } else if (minutesUntil < 30) {
                            timeText = minutesUntil + " min";
                        } else {
                            // Format as time for arrivals more than 30 minutes away
                            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.US);
                            timeText = sdf.format(new java.util.Date(arrivalTime));
                        }
                        
                        Log.d(TAG, "Row " + rowId + ": Route=" + routeName + ", Headsign=" + headsign + ", Time=" + timeText);
                        
                        // Set the row info
                        int routeViewId = getResourceId(context, "route_" + rowId, "id");
                        int destViewId = getResourceId(context, "destination_" + rowId, "id");
                        int timeViewId = getResourceId(context, "time_" + rowId, "id");
                        int arrivingTimeViewId = getResourceId(context, "arriving_time_" + rowId, "id");
                        int rowViewId = getResourceId(context, "arrival_row_" + rowId, "id");
                        int statusViewId = getResourceId(context, "status_" + rowId, "id");
                        
                        if (routeViewId == 0 || destViewId == 0 || timeViewId == 0 || arrivingTimeViewId == 0 || rowViewId == 0) {
                            Log.e(TAG, "Failed to find resource IDs for row " + rowId);
                            continue;
                        }
                        
                        // Set route and destination 
                        arrivalsViews.setTextViewText(routeViewId, routeName);
                        arrivalsViews.setTextColor(routeViewId, context.getResources().getColor(R.color.theme_primary));
                        
                        // Set destination with scheduled indicator if needed
                        String destinationText = headsign;
                        if (!isPredicted) {
                            destinationText = "* " + destinationText;
                        }
                        arrivalsViews.setTextViewText(destViewId, destinationText);
                        arrivalsViews.setTextColor(destViewId, context.getResources().getColor(android.R.color.black));
                        
                        // Format arrival information with main time in top-right and arriving time below destination
                        String formattedArrivalTime = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(new java.util.Date(arrivalTime));
                        
                        // Get arrival status (early/late/on time) like in the app
                        String statusText = "";
                        int statusColor = context.getResources().getColor(R.color.stop_info_ontime);
                        boolean hasStatusText = false;
                        
                        if (isPredicted && expectedTime != scheduledTime) {
                            long diffMinutes = (expectedTime - scheduledTime) / 60000;
                            if (diffMinutes < -1) {
                                statusText = Math.abs(diffMinutes) + " min early";
                                statusColor = context.getResources().getColor(R.color.stop_info_early);
                                hasStatusText = true;
                            } else if (diffMinutes > 1) {
                                statusText = diffMinutes + " min late";
                                statusColor = context.getResources().getColor(R.color.stop_info_delayed);
                                hasStatusText = true;
                            }
                        }
                        
                        // Set the main time (top right) and color based on schedule status
                        int timeColor = getArrivalTimeColor(context, isPredicted, scheduledTime, expectedTime);
                        arrivalsViews.setTextColor(timeViewId, timeColor);
                        
                        // Setup status indicator similar to main app
                        if (statusViewId != 0) {
                            arrivalsViews.setViewVisibility(statusViewId, View.VISIBLE);
                            arrivalsViews.setTextViewText(statusViewId, statusText);
                            arrivalsViews.setInt(statusViewId, "setBackgroundColor", statusColor);
                            // Don't include status in arriving time text since it's in the indicator
                            statusText = "";
                        } else if (statusViewId != 0) {
                            arrivalsViews.setViewVisibility(statusViewId, View.GONE);
                        }
                        
                        if (minutesUntil <= 0) {
                            arrivalsViews.setTextViewText(timeViewId, "DUE NOW");
                            arrivalsViews.setTextViewText(arrivingTimeViewId, "Arriving now" + statusText);
                        } else if (minutesUntil == 1) {
                            arrivalsViews.setTextViewText(timeViewId, "1 MIN");
                            arrivalsViews.setTextViewText(arrivingTimeViewId, "Arriving: " + formattedArrivalTime + statusText);
                        } else if (minutesUntil < 30) {
                            arrivalsViews.setTextViewText(timeViewId, minutesUntil + " MIN");
                            arrivalsViews.setTextViewText(arrivingTimeViewId, "Arriving: " + formattedArrivalTime + statusText);
                        } else {
                            arrivalsViews.setTextViewText(timeViewId, "ARRIVING");
                            arrivalsViews.setTextViewText(arrivingTimeViewId, formattedArrivalTime + statusText);
                        }
                        
                        // Apply status color to arriving time text if status text is still present
                        if (!statusText.isEmpty()) {
                            arrivalsViews.setTextColor(arrivingTimeViewId, statusColor);
                        } else {
                            arrivalsViews.setTextColor(arrivingTimeViewId, context.getResources().getColor(R.color.theme_primary));
                        }

                        // Make the row visible
                        arrivalsViews.setViewVisibility(rowViewId, View.VISIBLE);
                        
                        // Set up click for each row
                        try {
                            Intent rowIntent = new Intent(context, ArrivalsListActivity.class);
                            rowIntent.setData(Uri.withAppendedPath(ObaContract.Stops.CONTENT_URI, stopId));
                            rowIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            PendingIntent rowPendingIntent = PendingIntent.getActivity(context, rowId, rowIntent, 
                                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                            arrivalsViews.setOnClickPendingIntent(rowViewId, rowPendingIntent);
                        } catch (Exception e) {
                            Log.e(TAG, "Error setting click listener for row " + rowId + ": " + e.getMessage());
                        }
                    }
                    
                    // Hide any remaining rows
                    for (int i = rowsConfigured + 1; i <= MAX_ARRIVALS_TO_SHOW; i++) {
                        int rowViewId = getResourceId(context, "arrival_row_" + i, "id");
                        if (rowViewId != 0) {
                            arrivalsViews.setViewVisibility(rowViewId, View.GONE);
                        }
                    }
                    
                    // Configure the "Load More" button if there are more arrivals available
                    if (arrivalInfoArray.length > rowsConfigured && rowsConfigured < MAX_ARRIVALS_TO_SHOW) {
                        // More arrivals available to display
                        Log.d(TAG, "More arrivals available but maximum display limit reached");
                    } else {
                        // No more arrivals or reached maximum display limit
                        Log.d(TAG, "No more arrivals to display");
                    }
                    
                    // Show legend text if any arrivals are scheduled (not real-time)
                    boolean hasScheduledArrivals = false;
                    for (ObaArrivalInfo arrivalInfo : arrivalInfoArray) {
                        if (arrivalInfo.getPredictedArrivalTime() <= 0) {
                            hasScheduledArrivals = true;
                            break;
                        }
                    }
                    arrivalsViews.setViewVisibility(R.id.legend_text, hasScheduledArrivals ? View.VISIBLE : View.GONE);
                    
                    // Make the arrivals list clickable
                    arrivalsViews.setOnClickPendingIntent(R.id.arrivals_list, pendingIntent);
                }
                
                // Last updated timestamp
                String formattedTime = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(new java.util.Date());
                arrivalsViews.setViewVisibility(R.id.no_starred_stops, View.VISIBLE);
                arrivalsViews.setTextViewText(R.id.no_starred_stops, "Updated " + formattedTime);
                
                // Force the background to be visible
                arrivalsViews.setInt(R.id.widget_layout, "setBackgroundResource", R.drawable.widget_background);
                
                // Update the widget
                appWidgetManager.updateAppWidget(appWidgetId, arrivalsViews);
                Log.d(TAG, "Successfully updated widget with stop data");
                
            } catch (Exception e) {
                Log.e(TAG, "Error in arrivals background thread: " + e.getMessage(), e);
                
                RemoteViews errorViews = new RemoteViews(context.getPackageName(), R.layout.widget_favorite_stop);
                errorViews.setTextViewText(R.id.stop_name, displayName);
                errorViews.setTextViewText(R.id.direction, stopDirection);
                errorViews.setViewVisibility(R.id.arrivals_list, View.GONE);
                errorViews.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
                errorViews.setTextViewText(R.id.no_arrivals, "Could not load arrivals: " + 
                        (e.getMessage() != null ? e.getMessage() : "Unknown error"));
                errorViews.setImageViewResource(R.id.widget_refresh, R.drawable.ic_refresh_white_24dp);
                
                // Set up refresh button
                Intent refreshIntent = new Intent(context, FavoriteStopWidgetProvider.class);
                refreshIntent.setAction(ACTION_REFRESH);
                refreshIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
                PendingIntent refreshPendingIntent = PendingIntent.getBroadcast(context, appWidgetId, refreshIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                errorViews.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent);
                
                appWidgetManager.updateAppWidget(appWidgetId, errorViews);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in loadArrivalsDirectly: " + e.getMessage(), e);
        }
    }

    /**
     * Set up automatic refresh for a widget
     */
    private void scheduleAutoRefresh(Context context, int appWidgetId) {
        try {
            Log.d(TAG, "Scheduling auto-refresh for widget ID: " + appWidgetId);
            
            // Create an intent for the auto-refresh broadcast
            Intent intent = new Intent(context, FavoriteStopWidgetProvider.class);
            intent.setAction(ACTION_AUTO_REFRESH);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            
            // Create a unique pending intent for this widget ID
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context, 
                    appWidgetId,  // Use appWidgetId as request code to make it unique
                    intent, 
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            
            // Get the alarm manager service
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            
            if (alarmManager != null) {
                // Cancel any existing alarm first
                alarmManager.cancel(pendingIntent);
                
                // Schedule a new alarm in 5 minutes
                long triggerTime = SystemClock.elapsedRealtime() + AUTO_REFRESH_INTERVAL_MS;
                
                // Use inexact repeating for better battery efficiency
                alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME,
                        triggerTime,
                        pendingIntent);
                
                Log.d(TAG, "Auto-refresh scheduled for widget ID: " + appWidgetId + 
                        " at " + new java.util.Date(System.currentTimeMillis() + AUTO_REFRESH_INTERVAL_MS));
            } else {
                Log.e(TAG, "Failed to get AlarmManager service");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error scheduling auto-refresh: " + e.getMessage(), e);
        }
    }

    /**
     * Cancel auto-refresh for a specific widget
     */
    private void cancelAutoRefresh(Context context, int appWidgetId) {
        try {
            Intent intent = new Intent(context, FavoriteStopWidgetProvider.class);
            intent.setAction(ACTION_AUTO_REFRESH);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context, 
                    appWidgetId, 
                    intent, 
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                alarmManager.cancel(pendingIntent);
                Log.d(TAG, "Auto-refresh canceled for widget ID: " + appWidgetId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error canceling auto-refresh: " + e.getMessage(), e);
        }
    }

    /**
     * Cancel all auto-refresh alarms
     */
    private void cancelAllAutoRefresh(Context context) {
        try {
            // Get all widget IDs
            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
            int[] appWidgetIds = appWidgetManager.getAppWidgetIds(
                    new ComponentName(context, FavoriteStopWidgetProvider.class));
            
            // Cancel auto-refresh for each widget
            for (int appWidgetId : appWidgetIds) {
                cancelAutoRefresh(context, appWidgetId);
            }
            Log.d(TAG, "Canceled all auto-refresh alarms");
        } catch (Exception e) {
            Log.e(TAG, "Error canceling all auto-refresh alarms: " + e.getMessage(), e);
        }
    }

    /**
     * Force refresh all available widgets
     */
    private void forceRefreshAllWidgets(Context context) {
        try {
            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
            ComponentName thisWidget = new ComponentName(context, FavoriteStopWidgetProvider.class);
            int[] appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget);
            
            Log.d(TAG, "Force refreshing " + appWidgetIds.length + " widgets");
            
            for (int appWidgetId : appWidgetIds) {
                updateWidgetInitialState(context, appWidgetManager, appWidgetId);
                updateWidget(context, appWidgetManager, appWidgetId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in forceRefreshAllWidgets: " + e.getMessage(), e);
        }
    }

    /**
     * Static method that can be called from anywhere in the app to test widget visibility
     * This method bypasses the normal data loading process and shows a test pattern
     */
    public static void testWidgetVisibility(Context context) {
        try {
            Log.d(TAG, "Testing widget visibility");
            
            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
            ComponentName thisWidget = new ComponentName(context, FavoriteStopWidgetProvider.class);
            int[] appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget);
            
            if (appWidgetIds == null || appWidgetIds.length == 0) {
                Log.d(TAG, "No widgets found to test");
                return;
            }
            
            Log.d(TAG, "Testing visibility for " + appWidgetIds.length + " widgets");
            
            for (int appWidgetId : appWidgetIds) {
                // Create a simple RemoteViews with high-contrast test pattern
                RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_favorite_stop);
                
                // Set backgrounds to ensure visibility
                views.setInt(R.id.widget_layout, "setBackgroundResource", R.drawable.widget_background);
                
                // Make all views visible
                views.setViewVisibility(R.id.widget_layout, View.VISIBLE);
                views.setViewVisibility(R.id.widget_header, View.VISIBLE);
                views.setViewVisibility(R.id.stop_selector, View.VISIBLE);
                views.setViewVisibility(R.id.dropdown_arrow, View.VISIBLE);
                views.setViewVisibility(R.id.widget_refresh, View.VISIBLE);
                
                // Set test pattern text
                views.setTextViewText(R.id.stop_name, "TEST PATTERN");
                views.setTextColor(R.id.stop_name, 0xFFFFFFFF);
                
                views.setTextViewText(R.id.direction, "Widget Visibility Test");
                views.setTextColor(R.id.direction, 0xFFEEEEEE);
                
                // Show message with timestamp
                views.setViewVisibility(R.id.arrivals_list, View.GONE);
                views.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
                views.setTextViewText(R.id.no_arrivals, "Widget Test - Tap to refresh");
                views.setTextColor(R.id.no_arrivals, 0xFF000000);
                
                // Show time of test
                String formattedTime = new java.text.SimpleDateFormat("h:mm:ss a", java.util.Locale.US).format(new java.util.Date());
                views.setViewVisibility(R.id.no_starred_stops, View.VISIBLE);
                views.setTextViewText(R.id.no_starred_stops, "Test at " + formattedTime);
                views.setTextColor(R.id.no_starred_stops, 0xFF666666);
                
                // Set up click to refresh
                Intent refreshIntent = new Intent(context, FavoriteStopWidgetProvider.class);
                refreshIntent.setAction(ACTION_REFRESH);
                refreshIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
                PendingIntent refreshPendingIntent = PendingIntent.getBroadcast(context, appWidgetId, refreshIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                
                // Make entire widget clickable for refresh
                views.setOnClickPendingIntent(R.id.widget_layout, refreshPendingIntent);
                views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent);
                views.setOnClickPendingIntent(R.id.no_arrivals, refreshPendingIntent);
                
                // Update the widget
                appWidgetManager.updateAppWidget(appWidgetId, views);
                Log.d(TAG, "Test pattern applied to widget ID: " + appWidgetId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in testWidgetVisibility: " + e.getMessage(), e);
        }
    }

    /**
     * Helper class to hold starred stop information
     */
    public static class StarredStop implements android.os.Parcelable {
        private final String stopId;
        private final String displayName;
        
        public StarredStop(String stopId, String displayName) {
            this.stopId = stopId;
            this.displayName = displayName;
        }
        
        public String getStopId() {
            return stopId;
        }
        
        public String getDisplayName() {
            return displayName;
        }
        
        // Parcelable implementation
        protected StarredStop(android.os.Parcel in) {
            stopId = in.readString();
            displayName = in.readString();
        }
        
        @Override
        public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeString(stopId);
            dest.writeString(displayName);
        }
        
        @Override
        public int describeContents() {
            return 0;
        }
        
        public static final android.os.Parcelable.Creator<StarredStop> CREATOR = new android.os.Parcelable.Creator<StarredStop>() {
            @Override
            public StarredStop createFromParcel(android.os.Parcel in) {
                return new StarredStop(in);
            }
            
            @Override
            public StarredStop[] newArray(int size) {
                return new StarredStop[size];
            }
        };
    }

    /**
     * Helper method to verify that the widget ID is valid and exists
     */
    private boolean validateWidgetId(Context context, int appWidgetId) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            Log.e(TAG, "Invalid widget ID: " + appWidgetId);
            return false;
        }
        
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
        int[] appWidgetIds = appWidgetManager.getAppWidgetIds(
                new ComponentName(context, FavoriteStopWidgetProvider.class));
        
        boolean found = false;
        for (int id : appWidgetIds) {
            if (id == appWidgetId) {
                found = true;
                break;
            }
        }
        
        if (!found) {
            Log.e(TAG, "Widget ID " + appWidgetId + " not found in active widgets list: " + 
                    Arrays.toString(appWidgetIds));
            return false;
        }
        
        return true;
    }

    /**
     * Formats arrival time to match app style
     */
    private String formatArrivalTime(long arrivalTime, boolean isPredicted) {
        long now = System.currentTimeMillis();
        long minutesUntil = (arrivalTime - now) / 60000;
        
        if (minutesUntil <= 0) {
            return "Due";
        } else if (minutesUntil == 1) {
            return "1 min";
        } else if (minutesUntil < 30) {
            return minutesUntil + " min";
        } else {
            // Format as time for arrivals more than 30 minutes away
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.US);
            return sdf.format(new java.util.Date(arrivalTime));
        }
    }

    /**
     * Determines the text color for an arrival time based on schedule status
     * Implementation matches ArrivalInfoUtils.computeColor from the main app
     */
    private int getArrivalTimeColor(Context context, boolean isPredicted, long scheduledTime, long predictedTime) {
        if (isPredicted && predictedTime > 0) {
            // Real-time prediction - use status colors from the main app
            long delay = predictedTime - scheduledTime;
            if (delay > 0) {
                // Bus is delayed/late
                return context.getResources().getColor(R.color.stop_info_delayed);
            } else if (delay < 0) {
                // Bus is early
                return context.getResources().getColor(R.color.stop_info_early);
            } else {
                // Bus is on time
                return context.getResources().getColor(R.color.stop_info_ontime);
            }
        } else {
            // Scheduled time - use scheduled color
            return context.getResources().getColor(R.color.stop_info_scheduled_time);
        }
    }
    
    /**
     * Simple helper for backwards compatibility
     */
    private int getArrivalTimeColor(Context context, boolean isPredicted) {
        if (isPredicted) {
            return context.getResources().getColor(R.color.stop_info_ontime);
        } else {
            return context.getResources().getColor(R.color.stop_info_scheduled_time);
        }
    }

    /**
     * Additional method to check if required resources are available
     * This helps diagnose common issues with missing drawables
     */
    private boolean checkResources(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        Log.d(TAG, "Checking resources for widget " + appWidgetId);
        try {
            // Check critical drawables
            int[] resourcesToCheck = {
                R.drawable.widget_background,
                R.drawable.widget_header_background,
                R.drawable.card_background,
                R.drawable.ic_refresh_white_24dp,
                R.drawable.ic_arrow_drop_down_white_24dp
            };
            
            Resources res = context.getResources();
            List<String> missingResources = new ArrayList<>();
            
            for (int resId : resourcesToCheck) {
                try {
                    res.getDrawable(resId, null);
                } catch (Exception e) {
                    Log.e(TAG, "Missing resource: " + context.getResources().getResourceName(resId), e);
                    missingResources.add(context.getResources().getResourceName(resId));
                }
            }
            
            if (!missingResources.isEmpty()) {
                Log.e(TAG, "Widget is missing resources: " + missingResources);
                
                // Update the widget with error info
                RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_favorite_stop);
                views.setViewVisibility(R.id.widget_layout, View.VISIBLE);
                views.setTextViewText(R.id.stop_name, "Widget Resources Error");
                views.setTextViewText(R.id.direction, "Resources missing: " + missingResources.size());
                views.setViewVisibility(R.id.arrivals_list, View.GONE);
                views.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
                views.setTextViewText(R.id.no_arrivals, "The widget is missing required resources. Please check the app installation.");
                
                // Set up app icon intent
                Intent appIntent = new Intent(context, HomeActivity.class);
                appIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent pendingAppIntent = PendingIntent.getActivity(context, 0, appIntent, 
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                views.setOnClickPendingIntent(R.id.widget_header, pendingAppIntent);
                views.setOnClickPendingIntent(R.id.no_arrivals, pendingAppIntent);
                
                // Try to set solid color backgrounds as fallback
                views.setInt(R.id.widget_layout, "setBackgroundColor", 0xFFEEEEEE);
                views.setInt(R.id.widget_header, "setBackgroundColor", 0xFF2196F3);
                
                appWidgetManager.updateAppWidget(appWidgetId, views);
                return false;
            }
            
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error checking resources: " + e.getMessage(), e);
            return false;
        }
    }
} 