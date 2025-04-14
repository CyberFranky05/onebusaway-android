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

import android.app.IntentService;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import org.onebusaway.android.R;
import org.onebusaway.android.app.Application;
import org.onebusaway.android.io.ObaApi;
import org.onebusaway.android.io.elements.ObaArrivalInfo;
import org.onebusaway.android.io.elements.ObaRegion;
import org.onebusaway.android.io.elements.ObaStop;
import org.onebusaway.android.io.request.ObaArrivalInfoRequest;
import org.onebusaway.android.io.request.ObaArrivalInfoResponse;
import org.onebusaway.android.provider.ObaContract;
import org.onebusaway.android.util.RegionUtils;
import org.onebusaway.android.util.UIUtils;
import org.onebusaway.android.widget.WidgetArrivalViewBuilder;
import org.onebusaway.android.widget.WidgetCardBuilder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Service to fetch arrival data for the widget
 */
public class ArrivalsWidgetService extends IntentService {
    private static final String TAG = "ArrivalsWidgetService";
    public static final String ACTION_UPDATE_ARRIVALS = "org.onebusaway.android.widget.ACTION_UPDATE_ARRIVALS";
    public static final String EXTRA_STOP_ID = "org.onebusaway.android.widget.EXTRA_STOP_ID";
    public static final String EXTRA_STOP_NAME = "org.onebusaway.android.widget.EXTRA_STOP_NAME";
    public static final String EXTRA_WIDGET_ID = "org.onebusaway.android.widget.EXTRA_WIDGET_ID";
    
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("h:mm a", Locale.getDefault());
    
    // Background thread executor for API calls
    private static final Executor mBackgroundExecutor = Executors.newSingleThreadExecutor();
    
    // Handler for posting back to main thread
    private static final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public ArrivalsWidgetService() {
        super("ArrivalsWidgetService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null || !ACTION_UPDATE_ARRIVALS.equals(intent.getAction())) {
            Log.d(TAG, "Invalid intent or action");
            return;
        }

        String stopId = intent.getStringExtra(EXTRA_STOP_ID);
        String stopName = intent.getStringExtra(EXTRA_STOP_NAME);
        int widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);

        if (TextUtils.isEmpty(stopId) || widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            Log.e(TAG, "Missing required extras: stopId=" + stopId + ", widgetId=" + widgetId);
            return;
        }

        // Verify the widget exists before proceeding
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(this);
        boolean widgetExists = false;
        
        try {
            int[] appWidgetIds = appWidgetManager.getAppWidgetIds(new ComponentName(this, FavoriteStopWidgetProvider.class));
            for (int id : appWidgetIds) {
                if (id == widgetId) {
                    widgetExists = true;
                    break;
                }
            }
            
            if (!widgetExists) {
                Log.d(TAG, "Widget " + widgetId + " no longer exists, skipping update");
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking if widget exists", e);
            // Continue anyway as a fallback
        }

        Log.d(TAG, "Updating arrivals for stop: " + stopId + " (widget " + widgetId + ")");

        // Get widget manager and views
        RemoteViews views = new RemoteViews(getPackageName(), R.layout.widget_favorite_stop);

        // Show loading indicator
        views.setTextViewText(R.id.stop_name, stopName != null ? stopName : "Loading...");
        views.setTextViewText(R.id.direction, "Loading arrivals data...");
        views.setTextViewText(R.id.no_arrivals, "Checking for arrivals at stop " + stopId);
        
        // First update the widget to show loading state
        appWidgetManager.updateAppWidget(widgetId, views);
        
        // Then notify the list adapter to trigger a data refresh in a try-catch block
        try {
            appWidgetManager.notifyAppWidgetViewDataChanged(widgetId, R.id.arrivals_list);
        } catch (Exception e) {
            Log.e(TAG, "Error notifying data changes", e);
            // Continue anyway
        }
        
        Log.d(TAG, "Loading screen set for widget");

        try {
            // Ensure Application is initialized with a more robust approach
            if (!initializeAppIfNeeded()) {
                Log.e(TAG, "Failed to initialize application properly");
                views.setTextViewText(R.id.direction, "Error: App initialization failed");
                views.setTextViewText(R.id.no_arrivals, "Please open the app once to set up the widget.");
                setRetryRefreshButton(views, stopId, stopName, widgetId);
                appWidgetManager.updateAppWidget(widgetId, views);
                return;
            }
            
            // Verify region is set
            ObaRegion region = Application.get().getCurrentRegion();
            if (region == null) {
                Log.e(TAG, "No region set");
                views.setTextViewText(R.id.direction, "Error: No region selected");
                views.setTextViewText(R.id.no_arrivals, "Please open the app once to select your region.");
                setRetryRefreshButton(views, stopId, stopName, widgetId);
                appWidgetManager.updateAppWidget(widgetId, views);
                
                // Try to load regions in the background
                loadRegionsInBackground();
                
                return;
            }
            
            // Fetch arrival data using ObaArrivalInfoRequest
            Log.d(TAG, "Creating request for stop ID: " + stopId);
            ObaArrivalInfoRequest request = ObaArrivalInfoRequest.newRequest(this, stopId);
            Log.d(TAG, "Executing API call");
            ObaArrivalInfoResponse response = request.call();
            Log.d(TAG, "API call completed");

            if (response == null) {
                Log.e(TAG, "Response is null");
                views.setTextViewText(R.id.direction, "Error: No response");
                views.setTextViewText(R.id.no_arrivals, "Unable to get arrivals.\nPlease try again.");
                setRetryRefreshButton(views, stopId, stopName, widgetId);
                appWidgetManager.updateAppWidget(widgetId, views);
                return;
            }
            
            Log.d(TAG, "Response code: " + response.getCode());
            
            if (response.getCode() != ObaApi.OBA_OK) {
                // Handle error
                Log.e(TAG, "Error response code: " + response.getCode());
                views.setTextViewText(R.id.direction, "Error getting arrivals");
                views.setTextViewText(R.id.no_arrivals, "Unable to get arrivals.\nPlease check your connection and try again.");
                setRetryRefreshButton(views, stopId, stopName, widgetId);
                appWidgetManager.updateAppWidget(widgetId, views);
                return;
            }

            // Process arrival data
            ObaStop stop = response.getStop();
            if (stop == null) {
                Log.e(TAG, "Stop information is null");
                views.setTextViewText(R.id.direction, "Error: Stop not found");
                views.setTextViewText(R.id.no_arrivals, "Could not find information for stop " + stopId);
                setRetryRefreshButton(views, stopId, stopName, widgetId);
                appWidgetManager.updateAppWidget(widgetId, views);
                return;
            }
            
            Log.d(TAG, "Stop info: " + stop.getName() + " (ID: " + stop.getId() + ")");
            
            // Get arrivals information
            ObaArrivalInfo[] arrivals = response.getArrivalInfo();
            int arrivalsCount = arrivals != null ? arrivals.length : 0;
            Log.d(TAG, "Arrivals count: " + arrivalsCount);
            
            // Update the widget with simple text data
            Log.d(TAG, "Updating widget with arrival data");
            
            // Set the stop name
            views.setTextViewText(R.id.stop_name, stopName != null ? stopName : stop.getName());
            
            // Direction info - keep it short to fit on one line
            views.setTextViewText(R.id.direction, stop.getDirection() != null ? 
                    stop.getDirection() : "Arrivals for stop #" + stop.getStopCode());
            
            try {
                if (arrivalsCount == 0) {
                    // No arrivals - show message
                    views.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
                    views.setViewVisibility(R.id.arrivals_container, View.GONE);
                    views.setTextViewText(R.id.no_arrivals, "No upcoming arrivals at this time.");
                } else {
                    // Use the WidgetCardBuilder to create card-based UI
                    WidgetCardBuilder.buildArrivalCards(this, views, arrivals, widgetId);
                    Log.d(TAG, "Created arrival cards for " + arrivalsCount + " arrivals");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error setting up arrival cards", e);
                views.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
                views.setViewVisibility(R.id.arrivals_container, View.GONE);
                views.setTextViewText(R.id.no_arrivals, "Error displaying arrivals. Please try again.");
            }
            
            // Restore refresh button functionality
            setRetryRefreshButton(views, stopId, stopName, widgetId);
            
            // Update widget with the new views
            try {
                appWidgetManager.updateAppWidget(widgetId, views);
                Log.d(TAG, "Widget updated successfully with arrival cards");
            } catch (Exception e) {
                Log.e(TAG, "Error updating widget", e);
            }

            // Update widget with the new views
            try {
                // Update the widget UI
                appWidgetManager.updateAppWidget(widgetId, views);
                
                // Notify the list adapter that the data has changed
                appWidgetManager.notifyAppWidgetViewDataChanged(widgetId, R.id.arrivals_list);
                
                Log.d(TAG, "Widget updated successfully with arrival cards and list notified");
            } catch (Exception e) {
                Log.e(TAG, "Error updating widget", e);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error fetching arrivals", e);
            views.setTextViewText(R.id.direction, "Error getting arrivals");
            views.setTextViewText(R.id.no_arrivals, "An error occurred while getting arrivals.\nPlease try again.");
            setRetryRefreshButton(views, stopId, stopName, widgetId);
            appWidgetManager.updateAppWidget(widgetId, views);
        }
    }
    
    /**
     * Make sure the Application class is initialized properly
     * @return true if initialization was successful or app was already initialized
     */
    private boolean initializeAppIfNeeded() {
        try {
            // Check if Application is already available
            Application app = Application.get();
            if (app != null) {
                // Check if region is already set
                ObaRegion region = app.getCurrentRegion();
                if (region != null) {
                    Log.d(TAG, "Application already initialized with region: " + region.getName());
                    return true;
                } else {
                    Log.d(TAG, "Application initialized but no region set, initializing region");
                    // Initialize region if needed
                    initializeObaRegion();
                    return true;
                }
            } else {
                Log.d(TAG, "Application not initialized, initializing manually");
                // Initialize OBA API with critical configuration
                initializeObaApi();
                
                // Try to init region information
                initializeObaRegion();
                
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
    private void initializeObaApi() {
        try {
            // Get or generate app UUID
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            String uuid = prefs.getString(Application.APP_UID, null);
            if (uuid == null) {
                // Generate one
                uuid = java.util.UUID.randomUUID().toString();
                prefs.edit().putString(Application.APP_UID, uuid).apply();
            }
            
            // Get app version
            PackageManager pm = getPackageManager();
            PackageInfo appInfo = null;
            try {
                appInfo = pm.getPackageInfo(getPackageName(), PackageManager.GET_META_DATA);
                // Initialize OBA API with version and UUID
                ObaApi.getDefaultContext().setAppInfo(appInfo.versionCode, uuid);
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
    private void initializeObaRegion() {
        try {
            // Read the region preference, look it up in the DB, then set the region
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            long id = prefs.getLong(getString(R.string.preference_key_region), -1);
            if (id < 0) {
                Log.d(TAG, "No region preference set");
                return;
            }
            
            // Get region from content provider
            ObaRegion region = ObaContract.Regions.get(this, (int) id);
            if (region == null) {
                Log.d(TAG, "Could not find region with ID " + id);
                return;
            }
            
            // Set the region for the API
            ObaApi.getDefaultContext().setRegion(region);
            Log.d(TAG, "Region set to " + region.getName());
        } catch (Exception e) {
            Log.e(TAG, "Error initializing OBA region", e);
        }
    }
    
    /**
     * Loads regions in the background
     */
    private void loadRegionsInBackground() {
        mBackgroundExecutor.execute(() -> {
            try {
                Log.d(TAG, "Loading regions in background");
                List<ObaRegion> regions = RegionUtils.getRegions(getApplicationContext(), false);
                if (regions != null && !regions.isEmpty()) {
                    Log.d(TAG, "Found " + regions.size() + " regions");
                    // Select the first one for now
                    ObaRegion defaultRegion = regions.get(0);
                    Application.get().setCurrentRegion(defaultRegion);
                    Log.d(TAG, "Set default region: " + defaultRegion.getName());
                } else {
                    Log.e(TAG, "No regions found");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading regions", e);
            }
        });
    }
    
    /**
     * Set up the refresh button to retry loading arrivals
     */
    private void setRetryRefreshButton(RemoteViews views, String stopId, String stopName, int widgetId) {
        Intent refreshIntent = new Intent(this, FavoriteStopWidgetProvider.class);
        refreshIntent.setAction(FavoriteStopWidgetProvider.ACTION_REFRESH_WIDGET);
        refreshIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        PendingIntent refreshPendingIntent = PendingIntent.getBroadcast(this, widgetId + 2000, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent);
    }

    /**
     * Request an update for the widget
     * @param context The context
     * @param stopId Stop ID to update
     * @param stopName Stop name (can be null)
     * @param widgetId Widget ID to update
     */
    public static void requestUpdate(Context context, String stopId, String stopName, int widgetId) {
        Log.d(TAG, "Requesting update for widget " + widgetId + ", stop " + stopId + " (" + stopName + ")");
        
        // Use application context to prevent leaks
        Context appContext = context.getApplicationContext();
        
        // Create a JobInfo to run the service
        ComponentName serviceName = new ComponentName(appContext, ArrivalsWidgetJobService.class);
        
        PersistableBundle extras = new PersistableBundle();
        extras.putString(EXTRA_STOP_ID, stopId);
        extras.putString(EXTRA_STOP_NAME, stopName != null ? stopName : "");
        extras.putInt(EXTRA_WIDGET_ID, widgetId);
        
        // Set a reasonable deadline - 10 seconds is much better than 1 second
        JobInfo jobInfo = new JobInfo.Builder(widgetId + 10000, serviceName)
                .setExtras(extras)
                .setMinimumLatency(0) // Start immediately
                .setOverrideDeadline(10000) // But wait maximum 10 seconds
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY) // Require network
                .build();
        
        // Schedule the job
        JobScheduler scheduler = (JobScheduler) appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        int result = scheduler.schedule(jobInfo);
        
        if (result == JobScheduler.RESULT_SUCCESS) {
            Log.d(TAG, "Job scheduled successfully for widget " + widgetId);
        } else {
            Log.e(TAG, "Failed to schedule job for widget " + widgetId);
            
            // Fall back to direct update if job scheduling fails
            try {
                // Update the widget directly
                updateWidget(appContext, stopId, stopName, widgetId);
            } catch (Exception e) {
                Log.e(TAG, "Error during direct widget update", e);
            }
        }
    }

    /**
     * Direct update method for the widget
     */
    private static void updateWidget(Context context, String stopId, String stopName, int widgetId) {
        Log.d(TAG, "Direct update for widget " + widgetId);
        
        try {
            // Verify the widget exists before notifying
            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
            int[] appWidgetIds = appWidgetManager.getAppWidgetIds(new ComponentName(context, FavoriteStopWidgetProvider.class));
            boolean widgetExists = false;
            
            for (int id : appWidgetIds) {
                if (id == widgetId) {
                    widgetExists = true;
                    break;
                }
            }
            
            if (widgetExists) {
                // Update the ListView only if the widget still exists
                appWidgetManager.notifyAppWidgetViewDataChanged(widgetId, R.id.arrivals_list);
                Log.d(TAG, "Successfully notified data changes for widget " + widgetId);
            } else {
                Log.d(TAG, "Widget " + widgetId + " no longer exists, skipping update");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating widget data", e);
        }
    }
} 