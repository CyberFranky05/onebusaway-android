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

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.onebusaway.android.R;
import org.onebusaway.android.ui.HomeActivity;
import org.onebusaway.android.widget.FavoriteStopManager.FavoriteStop;
import org.onebusaway.android.widget.FavoriteStopsAdapter.OnStopSelectedListener;

import java.util.List;

/**
 * Widget Provider for displaying favorite bus stop information
 */
public class FavoriteStopWidgetProvider extends AppWidgetProvider {
    private static final String TAG = "FavoriteStopWidget";
    public static final String ACTION_STOP_SELECTOR = "org.onebusaway.android.widget.ACTION_STOP_SELECTOR";
    public static final String ACTION_REFRESH_WIDGET = "org.onebusaway.android.widget.ACTION_REFRESH_WIDGET";
    private static final String ACTION_OPEN_SETTINGS = "org.onebusaway.android.widget.ACTION_OPEN_SETTINGS";
    public static final String ACTION_AUTO_UPDATE = "org.onebusaway.android.widget.ACTION_AUTO_UPDATE";
    
    // Shared preferences for storing selected stops
    public static final String PREFS_NAME = "org.onebusaway.android.widget.FavoriteStopWidgetProvider";
    private static final String PREF_STOP_ID_PREFIX = "stop_id_";
    private static final String PREF_STOP_NAME_PREFIX = "stop_name_";
    public static final String PREF_AUTO_REFRESH_PREFIX = "auto_refresh_";
    public static final String PREF_REFRESH_INTERVAL_PREFIX = "refresh_interval_";
    
    // Default update interval (30 minutes)
    private static final long DEFAULT_UPDATE_INTERVAL_MS = 30 * 60 * 1000;

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        // Ensure the Application class is initialized
        initializeAppIfNeeded(context);
        
        // Update each widget
        for (int appWidgetId : appWidgetIds) {
            // Log widget size information
            logWidgetSize(context, appWidgetManager, appWidgetId);
            
            // Update the widget UI immediately
            updateWidget(context, appWidgetManager, appWidgetId);
        }
    }
    
    /**
     * Make sure the Application class is initialized properly
     */
    private void initializeAppIfNeeded(Context context) {
        try {
            // Ensure OBA API is initialized
            org.onebusaway.android.app.Application app = org.onebusaway.android.app.Application.get();
            if (app != null) {
                Log.d(TAG, "Application already initialized");
            } else {
                Log.e(TAG, "Could not get Application instance");
            }
        } catch (Exception e) {
            Log.d(TAG, "Error checking application state", e);
        }
    }
    
    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager, 
            int appWidgetId, Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
        
        // Log new widget size
        logWidgetSize(context, appWidgetManager, appWidgetId);
        
        // Update the widget with the new size
        updateWidget(context, appWidgetManager, appWidgetId);
    }
    
    @Override
    public void onReceive(Context context, Intent intent) {
        // Ensure the Application class is initialized
        initializeAppIfNeeded(context);
        
        super.onReceive(context, intent);
        
        // Handle refresh action
        if (ACTION_REFRESH_WIDGET.equals(intent.getAction())) {
            int appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            
            Log.d(TAG, "Received refresh action for widget ID: " + appWidgetId);
            
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                // Load the saved stop ID
                String stopId = getStopIdForWidget(context, appWidgetId);
                String stopName = getStopNameForWidget(context, appWidgetId);
                
                Log.d(TAG, "Retrieved saved stop ID: " + stopId + ", name: " + stopName);
                
                if (stopId != null) {
                    // Update display to show loading state first
                    AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
                    RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_favorite_stop);
                    views.setTextViewText(R.id.direction, "Loading arrivals...");
                    appWidgetManager.updateAppWidget(appWidgetId, views);
                    
                    // Request arrivals update
                    Log.d(TAG, "Requesting arrivals update");
                    ArrivalsWidgetService.requestUpdate(context, stopId, stopName, appWidgetId);
                } else {
                    // No stop selected yet, just update the widget
                    Log.d(TAG, "No saved stop found, updating widget");
                    AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
                    updateWidget(context, appWidgetManager, appWidgetId);
                }
            } else {
                Log.e(TAG, "Invalid widget ID in refresh action");
            }
        } else if (ACTION_OPEN_SETTINGS.equals(intent.getAction())) {
            int appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            
            Log.d(TAG, "Received settings action for widget ID: " + appWidgetId);
            
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                // Open the settings activity
                Intent settingsIntent = new Intent(context, WidgetSettingsActivity.class);
                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                settingsIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
                context.startActivity(settingsIntent);
            }
        }
    }

    private void updateWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        Log.d(TAG, "Updating widget UI for ID: " + appWidgetId);
        
        // Create RemoteViews for the layout
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_favorite_stop);

        // Set up click intent for the widget to open the app
        Intent appIntent = new Intent(context, HomeActivity.class);
        appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent appPendingIntent = PendingIntent.getActivity(context, 0, appIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_layout, appPendingIntent);

        // Set up settings intent (using header area)
        Intent settingsIntent = new Intent(context, FavoriteStopWidgetProvider.class);
        settingsIntent.setAction(ACTION_OPEN_SETTINGS);
        settingsIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        PendingIntent settingsPendingIntent = PendingIntent.getBroadcast(context, appWidgetId + 1000, settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // Use the widget header for settings
        views.setOnClickPendingIntent(R.id.widget_header, settingsPendingIntent);

        // Set up click intent for the stop selector
        Intent selectorIntent = new Intent(context, FavoriteStopWidgetProvider.StopSelectorActivity.class);
        selectorIntent.setAction(ACTION_STOP_SELECTOR);
        selectorIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        PendingIntent selectorPendingIntent = PendingIntent.getActivity(context, appWidgetId, selectorIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.stop_selector, selectorPendingIntent);
        
        // Set up refresh click intent with unique requestCode
        Intent refreshIntent = new Intent(context, FavoriteStopWidgetProvider.class);
        refreshIntent.setAction(ACTION_REFRESH_WIDGET);
        refreshIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        PendingIntent refreshPendingIntent = PendingIntent.getBroadcast(context, appWidgetId + 2000, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent);

        // Check if we have a saved stop for this widget
        String stopId = getStopIdForWidget(context, appWidgetId);
        String stopName = getStopNameForWidget(context, appWidgetId);
        
        Log.d(TAG, "Saved stop for widget " + appWidgetId + ": ID=" + stopId + ", name=" + stopName);
        
        if (stopId != null) {
            // We have a saved stop, update the widget with that stop's data
            views.setTextViewText(R.id.stop_name, stopName != null ? stopName : "Loading...");
            views.setTextViewText(R.id.direction, "Loading arrivals...");
            views.setTextViewText(R.id.no_arrivals, "Please wait while we fetch data...");
            
            // Update the widget UI immediately
            appWidgetManager.updateAppWidget(appWidgetId, views);
            
            Log.d(TAG, "Widget UI updated with saved stop, requesting arrivals");
            
            // Start a proactive loading strategy with multiple retries if needed
            startProactiveLoading(context, stopId, stopName, appWidgetId);
        } else {
            // No saved stop, get the most frequently used favorite stop
            Log.d(TAG, "No saved stop, looking for most frequent stop");
            
            // Show loading state
            views.setTextViewText(R.id.stop_name, "OneBusAway");
            views.setTextViewText(R.id.direction, "Initializing widget...");
            views.setTextViewText(R.id.no_arrivals, "Finding your favorite stops...");
            appWidgetManager.updateAppWidget(appWidgetId, views);
            
            // Run in a separate thread to avoid blocking
            new Thread(() -> {
                // Try to find a favorite stop
                FavoriteStop favoriteStop = FavoriteStopManager.getMostFrequentStop(context);
                
                if (favoriteStop != null) {
                    // Display the favorite stop information
                    Log.d(TAG, "Found most frequent stop: " + favoriteStop.getStopName() + " (ID: " + favoriteStop.getStopId() + ")");
                    
                    // Save this stop for the widget right away
                    Log.d(TAG, "Saving most frequent stop for widget");
                    saveStopForWidget(context, appWidgetId, favoriteStop.getStopId(), favoriteStop.getStopName());
                    
                    // Update views with stop info
                    views.setTextViewText(R.id.stop_name, favoriteStop.getStopName());
                    views.setTextViewText(R.id.direction, "Loading arrivals...");
                    views.setTextViewText(R.id.no_arrivals, "Please wait while we fetch data...");
                    
                    // Update the widget right away
                    appWidgetManager.updateAppWidget(appWidgetId, views);
                    
                    // Start a proactive loading strategy with multiple retries
                    startProactiveLoading(context, favoriteStop.getStopId(), favoriteStop.getStopName(), appWidgetId);
                } else {
                    // No favorite stops - display default message
                    Log.d(TAG, "No favorite stops found");
                    views.setTextViewText(R.id.stop_name, "OneBusAway");
                    views.setTextViewText(R.id.direction, "No favorite stops");
                    views.setTextViewText(R.id.no_arrivals, "No favorite stops found.\nAdd stops in the app first, then tap on a stop to activate the widget.");
                    
                    // Update the widget
                    appWidgetManager.updateAppWidget(appWidgetId, views);
                }
            }).start();
        }
    }

    @Override
    public void onEnabled(Context context) {
        // Called when first widget is created
        super.onEnabled(context);
        
        // Ensure the Application is initialized when widget is first created
        initializeAppIfNeeded(context);
    }

    @Override
    public void onDisabled(Context context) {
        // Called when last widget is removed
        super.onDisabled(context);
    }
    
    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        // Called when widgets are deleted
        SharedPreferences.Editor prefs = context.getSharedPreferences(PREFS_NAME, 0).edit();
        
        for (int appWidgetId : appWidgetIds) {
            prefs.remove(PREF_STOP_ID_PREFIX + appWidgetId);
            prefs.remove(PREF_STOP_NAME_PREFIX + appWidgetId);
        }
        
        prefs.apply();
        super.onDeleted(context, appWidgetIds);
    }
    
    /**
     * Save the selected stop for this widget
     */
    public static void saveStopForWidget(Context context, int appWidgetId, String stopId, String stopName) {
        SharedPreferences.Editor prefs = context.getSharedPreferences(PREFS_NAME, 0).edit();
        prefs.putString(PREF_STOP_ID_PREFIX + appWidgetId, stopId);
        prefs.putString(PREF_STOP_NAME_PREFIX + appWidgetId, stopName);
        prefs.apply();
        
        // Update the widget to reflect the changes
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
        FavoriteStopWidgetProvider provider = new FavoriteStopWidgetProvider();
        provider.updateWidget(context, appWidgetManager, appWidgetId);
    }
    
    /**
     * Get the saved stop ID for this widget
     */
    public static String getStopIdForWidget(Context context, int appWidgetId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        return prefs.getString(PREF_STOP_ID_PREFIX + appWidgetId, null);
    }
    
    /**
     * Get the saved stop name for this widget
     */
    public static String getStopNameForWidget(Context context, int appWidgetId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        return prefs.getString(PREF_STOP_NAME_PREFIX + appWidgetId, null);
    }

    /**
     * Activity to display the bottom sheet for stop selection
     */
    public static class StopSelectorActivity extends android.app.Activity implements OnStopSelectedListener {
        private int mAppWidgetId;
        private BottomSheetDialog mBottomSheetDialog;

        @Override
        protected void onCreate(android.os.Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            
            Log.d(TAG, "StopSelectorActivity created");
            
            // Make activity background transparent
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            
            // Get the widget ID from the intent
            Intent intent = getIntent();
            if (intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)) {
                mAppWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 
                        AppWidgetManager.INVALID_APPWIDGET_ID);
                Log.d(TAG, "Got widget ID from intent: " + mAppWidgetId);
            } else {
                Log.e(TAG, "No widget ID in intent");
            }
            
            // Show the bottom sheet with favorite stops
            showStopSelector();
        }
        
        private void showStopSelector() {
            Log.d(TAG, "Showing stop selector bottom sheet");
            
            // Create and show the bottom sheet
            mBottomSheetDialog = new BottomSheetDialog(this);
            View bottomSheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_stops, null);
            mBottomSheetDialog.setContentView(bottomSheetView);
            
            // Set up RecyclerView
            RecyclerView recyclerView = bottomSheetView.findViewById(R.id.stops_recycler_view);
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
            
            // Create adapter and set data
            FavoriteStopsAdapter adapter = new FavoriteStopsAdapter(this);
            recyclerView.setAdapter(adapter);
            
            // Get all favorite stops
            List<FavoriteStop> stops = FavoriteStopManager.getAllFavoriteStops(this);
            Log.d(TAG, "Found " + stops.size() + " favorite stops");
            adapter.setFavoriteStops(stops);
            
            // Set callback for when the dialog is dismissed
            mBottomSheetDialog.setOnDismissListener(dialog -> {
                Log.d(TAG, "Bottom sheet dismissed");
                finish();
            });
            
            // Show the dialog
            mBottomSheetDialog.show();
            
            // Expand the bottom sheet
            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from((View) bottomSheetView.getParent());
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            Log.d(TAG, "Bottom sheet expanded");
        }

        @Override
        protected void onDestroy() {
            Log.d(TAG, "StopSelectorActivity being destroyed");
            // Make sure the dialog is dismissed when the activity is destroyed
            if (mBottomSheetDialog != null && mBottomSheetDialog.isShowing()) {
                Log.d(TAG, "Dismissing bottom sheet dialog");
                mBottomSheetDialog.dismiss();
            }
            super.onDestroy();
        }

        @Override
        public void onStopSelected(FavoriteStop stop) {
            Log.d(TAG, "Stop selected: " + stop.getStopName() + " (ID: " + stop.getStopId() + ")");
            
            // Save the selected stop
            saveStopForWidget(this, mAppWidgetId, stop.getStopId(), stop.getStopName());
            Log.d(TAG, "Stop saved for widget " + mAppWidgetId);
            
            // Request an update for the widget with the new stop
            Log.d(TAG, "Requesting update with selected stop");
            ArrivalsWidgetService.requestUpdate(this, stop.getStopId(), stop.getStopName(), mAppWidgetId);
            
            // Close the dialog
            if (mBottomSheetDialog != null) {
                Log.d(TAG, "Dismissing dialog after selection");
                mBottomSheetDialog.dismiss();
            }
            
            // Close the activity
            Log.d(TAG, "Finishing activity after selection");
            finish();
        }
    }

    /**
     * Log the current size of the widget
     */
    private void logWidgetSize(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        Bundle options = appWidgetManager.getAppWidgetOptions(appWidgetId);
        if (options != null) {
            // Portrait mode dimensions
            int minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            int maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
            
            // Landscape mode dimensions
            int maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0);
            int minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
            
            Log.d(TAG, "Widget ID " + appWidgetId + " size (dp):");
            Log.d(TAG, "  Portrait: " + minWidth + " x " + maxHeight);
            Log.d(TAG, "  Landscape: " + maxWidth + " x " + minHeight);
            
            // Calculate number of cells (approximation)
            int cellWidth = minWidth / 70 + 1; // 70dp is approximately a cell width
            int cellHeight = minHeight / 70 + 1; // 70dp is approximately a cell height
            
            Log.d(TAG, "  Approximate cell size: " + cellWidth + " x " + cellHeight + " cells");
            
            // Get actual device screen density for pixel calculation
            float density = context.getResources().getDisplayMetrics().density;
            Log.d(TAG, "  Screen density: " + density);
            Log.d(TAG, "  Size in pixels: " + Math.round(minWidth * density) + " x " + Math.round(minHeight * density));
        } else {
            Log.d(TAG, "Widget ID " + appWidgetId + ": Unable to get size information");
        }
    }
    
    /**
     * Start a proactive loading strategy with multiple retry attempts.
     * This ensures the widget loads data even if initial attempts fail
     */
    private void startProactiveLoading(Context context, String stopId, String stopName, int appWidgetId) {
        // Create a background thread for loading with exponential backoff
        new Thread(() -> {
            try {
                // First attempt - immediate
                Log.d(TAG, "First attempt to load arrivals data");
                ArrivalsWidgetService.requestUpdate(context, stopId, stopName, appWidgetId);
                
                // Wait and then do a second attempt after 3 seconds
                Thread.sleep(3000);
                
                // Check if we already have the widget preferences
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
                String currentStopId = prefs.getString(PREF_STOP_ID_PREFIX + appWidgetId, null);
                
                // If the stopId has changed or been removed, abort further retries
                if (currentStopId == null || !currentStopId.equals(stopId)) {
                    Log.d(TAG, "Stop ID changed or removed, aborting retry sequence");
                    return;
                }
                
                Log.d(TAG, "Second attempt to load arrivals data");
                ArrivalsWidgetService.requestUpdate(context, stopId, stopName, appWidgetId);
                
                // Final attempt after another 5 seconds (8 seconds total)
                Thread.sleep(5000);
                
                // Check again if the stopId is still valid
                currentStopId = prefs.getString(PREF_STOP_ID_PREFIX + appWidgetId, null);
                if (currentStopId == null || !currentStopId.equals(stopId)) {
                    Log.d(TAG, "Stop ID changed or removed, aborting final retry");
                    return;
                }
                
                Log.d(TAG, "Final attempt to load arrivals data");
                ArrivalsWidgetService.requestUpdate(context, stopId, stopName, appWidgetId);
                
                // After all loading attempts, schedule periodic updates if enabled
                scheduleWidgetUpdate(context, appWidgetId);
            } catch (InterruptedException e) {
                Log.e(TAG, "Proactive loading interrupted", e);
            } catch (Exception e) {
                Log.e(TAG, "Error during proactive loading", e);
                // Try one more time even if we hit an error
                try {
                    ArrivalsWidgetService.requestUpdate(context, stopId, stopName, appWidgetId);
                    scheduleWidgetUpdate(context, appWidgetId);
                } catch (Exception ex) {
                    Log.e(TAG, "Final attempt failed", ex);
                }
            }
        }).start();
    }
    
    /**
     * Schedule automatic updates for the widget if enabled
     */
    private void scheduleWidgetUpdate(Context context, int appWidgetId) {
        // Check if auto-refresh is enabled for this widget
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        boolean autoRefresh = prefs.getBoolean(PREF_AUTO_REFRESH_PREFIX + appWidgetId, true);
        
        // Cancel any existing alarms
        cancelWidgetUpdates(context, appWidgetId);
        
        if (autoRefresh) {
            // Get refresh interval in minutes
            int refreshIntervalMinutes = prefs.getInt(PREF_REFRESH_INTERVAL_PREFIX + appWidgetId, 30);
            long refreshIntervalMs = refreshIntervalMinutes * 60 * 1000L;
            
            Log.d(TAG, "Scheduling widget " + appWidgetId + " updates every " + refreshIntervalMinutes + " minutes");
            
            // Create pending intent
            Intent intent = new Intent(context, FavoriteStopWidgetProvider.class);
            intent.setAction(ACTION_AUTO_UPDATE);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, appWidgetId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            
            // Schedule recurring alarm with recommended approach for different API levels
            android.app.AlarmManager alarmManager = (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            long triggerTime = System.currentTimeMillis() + refreshIntervalMs;
            
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    // For Android 12+, check if we can schedule exact alarms
                    if (alarmManager.canScheduleExactAlarms()) {
                        // Use setExactAndAllowWhileIdle for more precise timing if allowed
                        alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
                        Log.d(TAG, "Set exact and allow idle alarm for widget " + appWidgetId);
                    } else {
                        // Fall back to setAndAllowWhileIdle
                        alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
                        Log.d(TAG, "Set and allow idle alarm for widget " + appWidgetId);
                    }
                } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    // For Android 6+, use setExactAndAllowWhileIdle
                    alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
                    Log.d(TAG, "Set exact and allow idle alarm for widget " + appWidgetId);
                } else {
                    // For older versions, use setExact
                    alarmManager.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
                    Log.d(TAG, "Set exact alarm for widget " + appWidgetId);
                }
            } catch (Exception e) {
                // If any of the above fail, fall back to the most basic approach
                Log.e(TAG, "Error setting preferred alarm type, falling back to basic alarm", e);
                alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
                Log.d(TAG, "Set basic alarm for widget " + appWidgetId);
            }
        } else {
            Log.d(TAG, "Auto-refresh disabled for widget " + appWidgetId);
        }
    }

    /**
     * Cancel any existing alarms for the widget
     */
    private void cancelWidgetUpdates(Context context, int appWidgetId) {
        // Cancel any existing alarms
        android.app.AlarmManager alarmManager = (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, FavoriteStopWidgetProvider.class);
        intent.setAction(ACTION_AUTO_UPDATE);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, appWidgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarmManager.cancel(pendingIntent);
    }

    /**
     * Determine if the widget is in a narrow layout
     */
    public static boolean isNarrowWidget(AppWidgetManager appWidgetManager, int appWidgetId) {
        Bundle options = appWidgetManager.getAppWidgetOptions(appWidgetId);
        if (options != null) {
            int minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            
            // Consider the widget "narrow" if it's less than 250dp wide
            return minWidth < 250;
        }
        return false;
    }
} 