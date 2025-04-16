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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
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
import org.onebusaway.android.widget.WidgetUtil;

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
    public static final String ACTION_ITEM_CLICK = "org.onebusaway.android.widget.ACTION_ITEM_CLICK";
    
    // Intent extras
    public static final String EXTRA_ARRIVAL_INFO = "org.onebusaway.android.widget.EXTRA_ARRIVAL_INFO";
    
    // Shared preferences for storing selected stops
    public static final String PREFS_NAME = "org.onebusaway.android.widget.FavoriteStopWidgetProvider";
    private static final String PREF_STOP_ID_PREFIX = "stop_id_";
    private static final String PREF_STOP_NAME_PREFIX = "stop_name_";
    public static final String PREF_AUTO_REFRESH_PREFIX = "auto_refresh_";
    public static final String PREF_REFRESH_INTERVAL_PREFIX = "refresh_interval_";
    
    // More reasonable default update interval (5 minutes)
    private static final long DEFAULT_UPDATE_INTERVAL_MS = 5 * 60 * 1000;
    
    // Minimum update interval (1 minute) to prevent excessive API calls
    private static final long MIN_UPDATE_INTERVAL_MS = 60 * 1000;

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        // Disable OneSignal in widget processes to prevent login errors
        WidgetUtil.disableOneSignalInWidgetProcess(context);
        
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
     * @return true if initialization was successful or app was already initialized
     */
    public static boolean initializeAppIfNeeded(Context context) {
        try {
            // Check if Application is already available
            org.onebusaway.android.app.Application app = org.onebusaway.android.app.Application.get();
            if (app != null) {
                Log.d(TAG, "Application already initialized");
                return true;
            } else {
                Log.d(TAG, "Application not initialized, initializing manually");
                // We need to manually initialize critical parts of the OBA system
                // Get application context to avoid memory leaks
                Context appContext = context.getApplicationContext();
                
                // Initialize OBA API with critical configuration
                initializeObaApi(appContext);
                
                // Try to init region information
                initializeObaRegion(appContext);
                
                Log.d(TAG, "Manual initialization completed");
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing app", e);
            return false;
        }
    }
    
    /**
     * Initialize OBA API with minimal required configuration
     */
    private static void initializeObaApi(Context context) {
        try {
            // Get or generate app UUID
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            String uuid = prefs.getString(org.onebusaway.android.app.Application.APP_UID, null);
            if (uuid == null) {
                // Generate one
                uuid = java.util.UUID.randomUUID().toString();
                prefs.edit().putString(org.onebusaway.android.app.Application.APP_UID, uuid).apply();
            }
            
            // Get app version
            PackageManager pm = context.getPackageManager();
            try {
                PackageInfo appInfo = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_META_DATA);
                // Initialize OBA API with version and UUID
                org.onebusaway.android.io.ObaApi.getDefaultContext().setAppInfo(appInfo.versionCode, uuid);
                Log.d(TAG, "OBA API initialized with version " + appInfo.versionCode);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Could not get package info", e);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing OBA API", e);
        }
    }
    
    /**
     * Initialize OBA region data
     */
    private static void initializeObaRegion(Context context) {
        try {
            // Read the region preference, look it up in the DB, then set the region
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            long id = prefs.getLong(context.getString(org.onebusaway.android.R.string.preference_key_region), -1);
            if (id < 0) {
                Log.d(TAG, "No region preference set");
                return;
            }
            
            // Get region from content provider
            org.onebusaway.android.io.elements.ObaRegion region = 
                    org.onebusaway.android.provider.ObaContract.Regions.get(context, (int) id);
            if (region == null) {
                Log.d(TAG, "Could not find region with ID " + id);
                return;
            }
            
            // Set the region for the API
            org.onebusaway.android.io.ObaApi.getDefaultContext().setRegion(region);
            Log.d(TAG, "Region set to " + region.getName());
        } catch (Exception e) {
            Log.e(TAG, "Error initializing OBA region", e);
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
        // Disable OneSignal in widget processes to prevent login errors
        WidgetUtil.disableOneSignalInWidgetProcess(context);
        
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

        // Set up the scrollable list for arrivals
        Log.d(TAG, "Setting up scrollable arrivals list");
        
        // Always ensure the ListView is visible and the legacy container is hidden
        views.setViewVisibility(R.id.arrivals_container, View.GONE);
        views.setViewVisibility(R.id.arrivals_list, View.VISIBLE);
        
        // The empty view is displayed when the collection has no items
        views.setEmptyView(R.id.arrivals_list, R.id.no_arrivals);
        
        // Check if we have a saved stop for this widget
        String stopId = getStopIdForWidget(context, appWidgetId);
        String stopName = getStopNameForWidget(context, appWidgetId);
        
        Log.d(TAG, "Saved stop for widget " + appWidgetId + ": ID=" + stopId + ", name=" + stopName);
        
        if (stopId != null) {
            // We have a saved stop, update the widget with that stop's data
            views.setTextViewText(R.id.stop_name, stopName != null ? stopName : "Loading...");
            views.setTextViewText(R.id.direction, "Loading arrivals...");
            views.setTextViewText(R.id.no_arrivals, "Please wait while we fetch data...");
        } else {
            // No saved stop
            Log.d(TAG, "No saved stop, showing default view");
            
            // Show loading state
            views.setTextViewText(R.id.stop_name, "OneBusAway");
            views.setTextViewText(R.id.direction, "Initializing widget...");
            views.setTextViewText(R.id.no_arrivals, "Finding your favorite stops...");
        }
        
        // Create an empty/null pending intent for the list items - this makes them non-clickable
        // Use a dummy broadcast intent instead of null to properly disable click actions
        Intent dummyIntent = new Intent(context, FavoriteStopWidgetProvider.class);
        dummyIntent.setAction("dummy.do.nothing.action");
        PendingIntent dummyPendingIntent = PendingIntent.getBroadcast(
                context, 
                appWidgetId + 3000, 
                dummyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setPendingIntentTemplate(R.id.arrivals_list, dummyPendingIntent);

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
        
        // First update the widget UI with our already populated views
        try {
            Log.d(TAG, "Updating widget UI for " + appWidgetId);
            appWidgetManager.updateAppWidget(appWidgetId, views);
        } catch (Exception e) {
            Log.e(TAG, "Error updating widget UI", e);
        }
        
        // Now set up the RemoteAdapter in a separate step
        try {
            // Set up the intent that starts the ArrivalsWidgetListService, which provides the views for this collection
            Intent intent = new Intent(context, ArrivalsWidgetListService.class);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            intent.putExtra("stopId", stopId);
            intent.putExtra("isNarrow", isNarrowWidget(appWidgetManager, appWidgetId));
            intent.putExtra("isFullWidth", isFullWidthWidget(appWidgetManager, appWidgetId));
            intent.putExtra("sizeCategory", getWidgetSizeCategory(appWidgetManager, appWidgetId));
            
            // When intents are compared, the extras are ignored, so we need to put the widget id
            // as data to make the intent unique
            intent.setData(android.net.Uri.parse("widget://" + appWidgetId));
            
            Log.d(TAG, "Setting remote adapter for widget " + appWidgetId);
            // Set up the RemoteViews object to use a RemoteViews adapter
            RemoteViews listViews = new RemoteViews(context.getPackageName(), R.layout.widget_favorite_stop);
            listViews.setRemoteAdapter(R.id.arrivals_list, intent);
            appWidgetManager.updateAppWidget(appWidgetId, listViews);
            
            // Further initialize if we have a stop
            if (stopId != null) {
                // Start a proactive loading strategy with minimal retries
                startProactiveLoading(context, stopId, stopName, appWidgetId);
            } else {
                // No saved stop, get the most frequently used favorite stop
                findAndSetMostFrequentStop(context, appWidgetManager, appWidgetId, views);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting up remote adapter", e);
        }
    }
    
    /**
     * Find and set the most frequent stop for a widget in a background thread
     */
    private void findAndSetMostFrequentStop(Context context, AppWidgetManager appWidgetManager, 
            int appWidgetId, RemoteViews views) {
        Log.d(TAG, "Looking for most frequent stop for widget " + appWidgetId);
        
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
            } else {
                // No favorite stops - display default message
                Log.d(TAG, "No favorite stops found");
                views.setTextViewText(R.id.stop_name, "OneBusAway");
                views.setTextViewText(R.id.direction, "No favorite stops");
                views.setTextViewText(R.id.no_arrivals, "No favorite stops found.\nAdd stops in the app first, then tap on a stop to activate the widget.");
                
                // Update the widget
                try {
                    appWidgetManager.updateAppWidget(appWidgetId, views);
                } catch (Exception e) {
                    Log.e(TAG, "Error updating widget with no stops message", e);
                }
            }
        }).start();
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
        
        Log.d(TAG, "Saved stop for widget " + appWidgetId + ": " + stopId + " (" + stopName + ")");
        
        // Update the widget to reflect the changes
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
        
        // Create a safe update sequence - first update the widget UI
        FavoriteStopWidgetProvider provider = new FavoriteStopWidgetProvider();
        provider.updateWidget(context, appWidgetManager, appWidgetId);
        
        // AFTER updating the widget UI, then notify data changes for the list
        try {
            Log.d(TAG, "Notifying data changes for arrivals list");
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.arrivals_list);
        } catch (Exception e) {
            Log.e(TAG, "Error notifying widget data changes", e);
        }
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
        private AppWidgetManager mAppWidgetManager;
        private boolean mIsFinishing = false;

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
                finish();
                return;
            }
            
            // Initialize widget manager - do this BEFORE showing bottom sheet
            mAppWidgetManager = AppWidgetManager.getInstance(getApplicationContext());
            
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
                finishSafely();
            });
            
            // Show the dialog
            mBottomSheetDialog.show();
            
            // Expand the bottom sheet
            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from((View) bottomSheetView.getParent());
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            Log.d(TAG, "Bottom sheet expanded");
        }
        
        /**
         * Finish the activity safely to prevent leaks
         */
        private void finishSafely() {
            if (!isFinishing() && !mIsFinishing) {
                mIsFinishing = true;
                
                // Make sure we clean up any ongoing operations with the AppWidgetManager
                mAppWidgetManager = null;
                
                // Close the dialog first
                if (mBottomSheetDialog != null && mBottomSheetDialog.isShowing()) {
                    mBottomSheetDialog.dismiss();
                    mBottomSheetDialog = null;
                }
                
                // Finish the activity
                finish();
            }
        }

        @Override
        protected void onPause() {
            super.onPause();
            // Clear references to the widget manager in onPause
            // This helps prevent service connection leaks
            mAppWidgetManager = null;
        }
        
        @Override
        protected void onStop() {
            super.onStop();
            // If activity is stopping, make sure we finish it completely
            if (!isFinishing()) {
                finishSafely();
            }
        }

        @Override
        protected void onDestroy() {
            Log.d(TAG, "StopSelectorActivity being destroyed");
            
            // Make sure we clear any references that could cause leaks
            mAppWidgetManager = null;
            
            // Make sure the dialog is dismissed when the activity is destroyed
            if (mBottomSheetDialog != null && mBottomSheetDialog.isShowing()) {
                Log.d(TAG, "Dismissing bottom sheet dialog");
                mBottomSheetDialog.dismiss();
                mBottomSheetDialog = null;
            }
            
            super.onDestroy();
        }

        @Override
        public void onStopSelected(FavoriteStop stop) {
            Log.d(TAG, "Stop selected: " + stop.getStopName() + " (ID: " + stop.getStopId() + ")");
            
            // Cache the values we need
            int widgetId = mAppWidgetId;
            String stopId = stop.getStopId();
            String stopName = stop.getStopName();
            
            // Save the selected stop
            saveStopForWidget(getApplicationContext(), widgetId, stopId, stopName);
            Log.d(TAG, "Stop saved for widget " + widgetId);
            
            // Request an update for the widget with the new stop - use application context
            Log.d(TAG, "Requesting update with selected stop");
            ArrivalsWidgetService.requestUpdate(getApplicationContext(), stopId, stopName, widgetId);
            
            // Finish safely to avoid leaks
            finishSafely();
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
     * Start a proactive loading strategy with minimal retries.
     * This ensures the widget loads data without excessive API calls.
     */
    private void startProactiveLoading(Context context, String stopId, String stopName, int appWidgetId) {
        // Use a more efficient executor instead of raw threads
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        
        executor.submit(() -> {
            try {
                // First attempt - immediate
                Log.d(TAG, "First attempt to load arrivals data");
                ArrivalsWidgetService.requestUpdate(context.getApplicationContext(), stopId, stopName, appWidgetId);
                
                // Wait a little to give the request time to execute before notifying
                Thread.sleep(250); // Reduced wait time
                
                // Notify the adapter about data change only if widget still exists
                if (isWidgetValid(context, appWidgetId, stopId)) {
                    try {
                        AppWidgetManager.getInstance(context).notifyAppWidgetViewDataChanged(appWidgetId, R.id.arrivals_list);
                        Log.d(TAG, "Notified arrivals list data change");
                    } catch (Exception e) {
                        Log.e(TAG, "Error notifying widget data changes (1st attempt)", e);
                    }
                } else {
                    Log.d(TAG, "Widget no longer valid, skipping notification");
                    return;
                }
                
                // Wait for a shorter time before second attempt - 1.5 seconds is enough
                Thread.sleep(1500);
                
                // Validate the widget and stop ID still exist
                if (!isWidgetValid(context, appWidgetId, stopId)) {
                    Log.d(TAG, "Widget or stop ID changed, aborting retry sequence");
                    return;
                }
                
                // Second attempt - this should be sufficient with our improved caching
                Log.d(TAG, "Second attempt to load arrivals data");
                ArrivalsWidgetService.requestUpdate(context.getApplicationContext(), stopId, stopName, appWidgetId);
                
                // Wait a little to give the request time to execute before notifying
                Thread.sleep(250); // Reduced wait time
                
                // Notify the adapter about data change only if widget still exists
                if (isWidgetValid(context, appWidgetId, stopId)) {
                    try {
                        AppWidgetManager.getInstance(context).notifyAppWidgetViewDataChanged(appWidgetId, R.id.arrivals_list);
                        Log.d(TAG, "Notified arrivals list data change (2nd attempt)");
                    } catch (Exception e) {
                        Log.e(TAG, "Error notifying widget data changes (2nd attempt)", e);
                    }
                }
                
                // Schedule a background refresh after a short delay (10 seconds) to ensure
                // the widget has the most current data if the initial requests were slow
                Thread.sleep(8000);
                
                if (isWidgetValid(context, appWidgetId, stopId)) {
                    Log.d(TAG, "Final background refresh to ensure current data");
                    ArrivalsWidgetService.requestUpdate(context.getApplicationContext(), stopId, stopName, appWidgetId);
                    AppWidgetManager.getInstance(context).notifyAppWidgetViewDataChanged(appWidgetId, R.id.arrivals_list);
                }
                
                // Schedule periodic updates if enabled
                scheduleWidgetUpdate(context, appWidgetId);
            } catch (Exception e) {
                Log.e(TAG, "Error during proactive loading", e);
                try {
                    // One final attempt if there was an error, but don't notify changes
                    // since that might be causing the errors
                    ArrivalsWidgetService.requestUpdate(context.getApplicationContext(), stopId, stopName, appWidgetId);
                    scheduleWidgetUpdate(context, appWidgetId);
                } catch (Exception ex) {
                    Log.e(TAG, "Final attempt failed", ex);
                }
            } finally {
                // Always shut down the executor to avoid leaking resources
                executor.shutdown();
            }
        });
    }
    
    /**
     * Check if the widget is still valid with the same stop ID
     */
    private boolean isWidgetValid(Context context, int appWidgetId, String stopId) {
        try {
            // Check if the widget still exists
            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
            int[] appWidgetIds = appWidgetManager.getAppWidgetIds(new ComponentName(context, FavoriteStopWidgetProvider.class));
            boolean widgetExists = false;
            
            for (int id : appWidgetIds) {
                if (id == appWidgetId) {
                    widgetExists = true;
                    break;
                }
            }
            
            if (!widgetExists) {
                Log.d(TAG, "Widget " + appWidgetId + " no longer exists");
                return false;
            }
            
            // Check if the stop ID is still the same
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
            String currentStopId = prefs.getString(PREF_STOP_ID_PREFIX + appWidgetId, null);
            
            if (currentStopId == null || !currentStopId.equals(stopId)) {
                Log.d(TAG, "Stop ID changed or removed");
                return false;
            }
            
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error checking widget validity", e);
            return false;
        }
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
            // Get refresh interval in minutes, default to 5 minutes
            int refreshIntervalMinutes = prefs.getInt(PREF_REFRESH_INTERVAL_PREFIX + appWidgetId, 5);
            
            // Ensure minimum interval to prevent excessive updates
            refreshIntervalMinutes = Math.max(1, refreshIntervalMinutes);
            
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
                    // For Android 12+, use inexact alarms which are better for battery
                    alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
                    Log.d(TAG, "Set inexact alarm for widget " + appWidgetId + " (Android 12+)");
                } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    // For Android 6-11, prioritize doze-compatible alarms
                    if (refreshIntervalMinutes <= 10) {
                        // For shorter intervals, use more precise timing
                        alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
                        Log.d(TAG, "Set exact and allow idle alarm for widget " + appWidgetId);
                    } else {
                        // For longer intervals, battery optimization is more important
                        alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
                        Log.d(TAG, "Set inexact alarm for widget " + appWidgetId);
                    }
                } else {
                    // For older versions, use setExact
                    alarmManager.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
                    Log.d(TAG, "Set exact alarm for widget " + appWidgetId);
                }
                
                // Also register a backup repeating alarm to ensure updates happen
                // even if the exact/idle alarms are delayed by the system
                long backupIntervalMs = Math.max(refreshIntervalMs * 3, 15 * 60 * 1000); // At least 15 minutes
                alarmManager.setRepeating(android.app.AlarmManager.RTC_WAKEUP, 
                        System.currentTimeMillis() + backupIntervalMs, 
                        backupIntervalMs, 
                        pendingIntent);
                Log.d(TAG, "Set backup repeating alarm every " + (backupIntervalMs / 60000) + " minutes");
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

    /**
     * Determine if the widget is in a full width layout
     */
    public static boolean isFullWidthWidget(AppWidgetManager appWidgetManager, int appWidgetId) {
        Bundle options = appWidgetManager.getAppWidgetOptions(appWidgetId);
        if (options != null) {
            int minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            
            // Consider the widget "full width" if it's more than 500dp wide
            return minWidth > 500;
        }
        return false;
    }

    /**
     * Get the widget size category
     * @return 0 for small, 1 for medium, 2 for large, 3 for full-width
     */
    public static int getWidgetSizeCategory(AppWidgetManager appWidgetManager, int appWidgetId) {
        Bundle options = appWidgetManager.getAppWidgetOptions(appWidgetId);
        if (options != null) {
            int minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            
            if (minWidth < 250) {
                return 0; // Small
            } else if (minWidth < 320) {
                return 1; // Medium
            } else if (minWidth < 500) {
                return 2; // Large
            } else {
                return 3; // Full-width
            }
        }
        return 1; // Default to medium if we can't determine
    }
} 