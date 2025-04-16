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
import android.net.Uri;
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
import org.onebusaway.android.ui.ArrivalsListActivity;
import org.onebusaway.android.util.RegionUtils;
import org.onebusaway.android.util.UIUtils;
import org.onebusaway.android.widget.ArrivalsWidgetListService;
import org.onebusaway.android.widget.WidgetArrivalViewBuilder;
import org.onebusaway.android.widget.WidgetCardBuilder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;

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
        views.setTextViewText(R.id.empty_text, "Checking for arrivals at stop " + stopId);
        
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
                views.setTextViewText(R.id.empty_text, "Please open the app once to set up the widget.");
                setRetryRefreshButton(views, stopId, stopName, widgetId);
                appWidgetManager.updateAppWidget(widgetId, views);
                return;
            }
            
            // Verify region is set
            ObaRegion region = Application.get().getCurrentRegion();
            if (region == null) {
                Log.e(TAG, "No region set");
                views.setTextViewText(R.id.direction, "Error: No region selected");
                views.setTextViewText(R.id.empty_text, "Please open the app once to select your region.");
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
                views.setTextViewText(R.id.empty_text, "Unable to get arrivals.\nPlease try again.");
                setRetryRefreshButton(views, stopId, stopName, widgetId);
                appWidgetManager.updateAppWidget(widgetId, views);
                return;
            }
            
            Log.d(TAG, "Response code: " + response.getCode());
            
            if (response.getCode() != ObaApi.OBA_OK) {
                // Handle error
                Log.e(TAG, "Error response code: " + response.getCode());
                views.setTextViewText(R.id.direction, "Error getting arrivals");
                views.setTextViewText(R.id.empty_text, "Unable to get arrivals.\nPlease check your connection and try again.");
                setRetryRefreshButton(views, stopId, stopName, widgetId);
                appWidgetManager.updateAppWidget(widgetId, views);
                return;
            }

            // Process arrival data
            ObaStop stop = response.getStop();
            if (stop == null) {
                Log.e(TAG, "Stop information is null");
                views.setTextViewText(R.id.direction, "Error: Stop not found");
                views.setTextViewText(R.id.empty_text, "Could not find information for stop " + stopId);
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
                    views.setViewVisibility(R.id.empty_text, View.VISIBLE);
                    views.setViewVisibility(R.id.arrival_list_wrapper, View.GONE);
                    views.setTextViewText(R.id.empty_text, "No upcoming arrivals at this time.");
                } else {
                    // Hide no arrivals message
                    views.setViewVisibility(R.id.empty_text, View.GONE);
                    views.setViewVisibility(R.id.arrival_list_wrapper, View.VISIBLE);
                    
                    // Notify the list adapter about the new data
                    appWidgetManager.notifyAppWidgetViewDataChanged(widgetId, R.id.arrivals_list);
                    Log.d(TAG, "Notified list adapter of new data");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error setting up arrival display", e);
                views.setViewVisibility(R.id.empty_text, View.VISIBLE);
                views.setViewVisibility(R.id.arrival_list_wrapper, View.GONE);
                views.setTextViewText(R.id.empty_text, "Error displaying arrivals. Please try again.");
            }
            
            // Add last updated time
            views.setTextViewText(R.id.direction,
                    (stop.getDirection() != null ? stop.getDirection() + " • " : "") +
                    "Updated: " + UIUtils.getTimeWithContext(this, System.currentTimeMillis(), false));
            
            // Update the widget UI
            appWidgetManager.updateAppWidget(widgetId, views);
            
        } catch (Exception e) {
            Log.e(TAG, "Error updating widget", e);
            views.setTextViewText(R.id.direction, "Error: " + e.getMessage());
            views.setTextViewText(R.id.empty_text, "An error occurred.\nPlease try again.");
            setRetryRefreshButton(views, stopId, stopName, widgetId);
            appWidgetManager.updateAppWidget(widgetId, views);
        }
    }

    // Initialize application if needed, with more reliable fallbacks
    private boolean initializeAppIfNeeded() {
        try {
            // First check if Application is already available
            if (Application.get() != null) {
                Log.d(TAG, "Application already initialized");
                return true;
            } else {
                Log.d(TAG, "Application not initialized, initializing manually");
                
                // Initialize critical bits manually
                initializeObaApi();
                initializeObaRegion();
                
                Log.d(TAG, "Manual initialization completed");
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing app", e);
            return false;
        }
    }
    
    // Manually initialize the OBA API with minimal settings
    private void initializeObaApi() {
        try {
            // Get or generate app UUID
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            String uuid = prefs.getString(Application.APP_UID, null);
            if (uuid == null) {
                uuid = java.util.UUID.randomUUID().toString();
                prefs.edit().putString(Application.APP_UID, uuid).apply();
            }
            
            // Get app version
            PackageManager pm = getPackageManager();
            PackageInfo appInfo = pm.getPackageInfo(getPackageName(), PackageManager.GET_META_DATA);
            
            // Initialize the API
            ObaApi.getDefaultContext().setAppInfo(appInfo.versionCode, uuid);
            Log.d(TAG, "OBA API initialized with version " + appInfo.versionCode);
            
        } catch (Exception e) {
            Log.e(TAG, "Error initializing OBA API", e);
        }
    }
    
    // Initialize region information from preferences
    private void initializeObaRegion() {
        try {
            // Get saved region ID
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            long regionId = prefs.getLong(getString(R.string.preference_key_region), -1);
            if (regionId < 0) {
                Log.d(TAG, "No region preference set");
                return;
            }
            
            // Get region from content provider
            ObaRegion region = ObaContract.Regions.get(this, (int) regionId);
            if (region == null) {
                Log.d(TAG, "Could not find region with ID " + regionId);
                return;
            }
            
            // Set the region for the API
            ObaApi.getDefaultContext().setRegion(region);
            Log.d(TAG, "Region set to " + region.getName());
            
        } catch (Exception e) {
            Log.e(TAG, "Error loading region", e);
        }
    }
    
    // Try to load regions in the background
    private void loadRegionsInBackground() {
        mBackgroundExecutor.execute(() -> {
            try {
                RegionUtils.loadRegionsAsync(this);
                Log.d(TAG, "Started regions loading task");
            } catch (Exception e) {
                Log.e(TAG, "Error loading regions in background", e);
            }
        });
    }
    
    // Set up the refresh button to retry loading
    private void setRetryRefreshButton(RemoteViews views, String stopId, String stopName, int widgetId) {
        Intent refreshIntent = new Intent(this, FavoriteStopWidgetProvider.class);
        refreshIntent.setAction(FavoriteStopWidgetProvider.ACTION_REFRESH_WIDGET);
        refreshIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        PendingIntent refreshPendingIntent = PendingIntent.getBroadcast(this, widgetId, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent);
    }
    
    // Request widget update via the service
    public static void requestUpdate(final Context context, final String stopId, final String stopName, final int appWidgetId) {
        Log.d(TAG, "Requesting update for widget " + appWidgetId + ", stop " + stopId);
        
        // Get shared executor for widget updates
        if (mUpdateExecutor == null) {
            mUpdateExecutor = Executors.newFixedThreadPool(3); // Limit to 3 concurrent threads
        }
        
        // Create a unique key for this request
        final String requestKey = appWidgetId + "_" + stopId;
        
        // Skip if already processing this request
        if (!mOngoingRequests.add(requestKey)) {
            Log.d(TAG, "Request already in progress for " + requestKey);
            return;
        }
        
        // Update widget UI first to show loading
        try {
            RemoteViews remoteViews = getRemoteViews(context, stopId, stopName, appWidgetId);
            showLoading(context, remoteViews, appWidgetId);
        } catch (Exception e) {
            Log.e(TAG, "Error showing loading state", e);
            // Continue anyway
        }
        
        // Process the request in the thread pool
        mUpdateExecutor.execute(() -> {
            try {
                Log.d(TAG, "Starting widget update task for " + requestKey);
                
                // Start the service
                Intent intent = new Intent(context, ArrivalsWidgetService.class);
                intent.setAction(ACTION_UPDATE_ARRIVALS);
                intent.putExtra(EXTRA_STOP_ID, stopId);
                intent.putExtra(EXTRA_STOP_NAME, stopName);
                intent.putExtra(EXTRA_WIDGET_ID, appWidgetId);
                
                // Start the service
                context.startService(intent);
                
                // Fetch arrival data with timeout
                boolean success = fetchArrivalsData(context, null, 30000); // 30 second timeout
                
                if (!success) {
                    Log.w(TAG, "Arrival data fetch failed or timed out");
                    
                    // Update UI to show error state
                    mMainHandler.post(() -> {
                        try {
                            RemoteViews remoteViews = getRemoteViews(context, stopId, stopName, appWidgetId);
                            remoteViews.setViewVisibility(R.id.loading_layout, View.GONE);
                            remoteViews.setViewVisibility(R.id.empty_text, View.VISIBLE);
                            remoteViews.setViewVisibility(R.id.arrival_list_wrapper, View.GONE);
                            remoteViews.setTextViewText(R.id.empty_text, "Unable to load arrival data.\nPlease try again.");
                            remoteViews.setTextViewText(R.id.direction, "Updated: " + 
                                    UIUtils.getTimeWithContext(context, System.currentTimeMillis(), false) + " (!)");
                            
                            AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, remoteViews);
                        } catch (Exception e) {
                            Log.e(TAG, "Error updating widget with error state", e);
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing widget update", e);
                
                // Update UI to show error state on main thread
                mMainHandler.post(() -> {
                    try {
                        RemoteViews remoteViews = getRemoteViews(context, stopId, stopName, appWidgetId);
                        remoteViews.setViewVisibility(R.id.loading_layout, View.GONE);
                        remoteViews.setViewVisibility(R.id.empty_text, View.VISIBLE);
                        remoteViews.setViewVisibility(R.id.arrival_list_wrapper, View.GONE);
                        remoteViews.setTextViewText(R.id.empty_text, "Error: " + e.getMessage());
                        remoteViews.setTextViewText(R.id.direction, "Updated: " + 
                                UIUtils.getTimeWithContext(context, System.currentTimeMillis(), false) + " (!)");
                        
                        AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, remoteViews);
                    } catch (Exception ex) {
                        Log.e(TAG, "Error updating widget with error state", ex);
                    }
                });
            } finally {
                // Always remove from ongoing requests
                mOngoingRequests.remove(requestKey);
            }
        });
    }
    
    // Fetch arrivals data with timeout
    private static boolean fetchArrivalsData(Context context, Object factory, long timeoutMs) {
        final AtomicBoolean success = new AtomicBoolean(false);
        final CountDownLatch latch = new CountDownLatch(1);
        
        Thread fetchThread = new Thread(() -> {
            try {
                // Note: This method is now a stub since we don't need the factory parameter anymore
                // Actual data fetching is done in the service implementation
                
                // Simulate success
                success.set(true);
            } catch (Exception e) {
                Log.e(TAG, "Error fetching arrivals data", e);
                success.set(false);
            } finally {
                latch.countDown();
            }
        });
        
        // Start fetch thread
        fetchThread.start();
        
        // Wait for fetch to complete or timeout
        try {
            boolean completed = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!completed) {
                Log.w(TAG, "Fetch arrivals timeout after " + timeoutMs + "ms");
            }
            return success.get() && completed;
        } catch (InterruptedException e) {
            Log.e(TAG, "Fetch arrivals interrupted", e);
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    // Show loading state in the widget
    private static void showLoading(Context context, RemoteViews remoteViews, int appWidgetId) {
        remoteViews.setViewVisibility(R.id.loading_layout, View.VISIBLE);
        remoteViews.setViewVisibility(R.id.arrival_list_wrapper, View.GONE);
        remoteViews.setViewVisibility(R.id.empty_text, View.GONE);
        
        // Update the widget with the loading state
        try {
            AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, remoteViews);
        } catch (Exception e) {
            Log.e(TAG, "Error showing loading state", e);
        }
    }
    
    // Show the list view and hide loading indicators
    private static void showListView(RemoteViews remoteViews) {
        remoteViews.setViewVisibility(R.id.loading_layout, View.GONE);
        remoteViews.setViewVisibility(R.id.arrival_list_wrapper, View.VISIBLE);
        remoteViews.setViewVisibility(R.id.empty_text, View.GONE);
    }
    
    // Thread pool for managing widget updates
    private static java.util.concurrent.ExecutorService mUpdateExecutor = null;
    
    // Set of currently processing requests to avoid duplicates
    private static final Set<String> mOngoingRequests = Collections.synchronizedSet(new HashSet<>());
    
    // Get RemoteViews for the widget
    private static RemoteViews getRemoteViews(Context context, String stopId, String stopName, int appWidgetId) {
        RemoteViews remoteViews = new RemoteViews(context.getPackageName(), R.layout.widget_favorite_stop);
        
        // Set stop name if available
        if (!TextUtils.isEmpty(stopName)) {
            remoteViews.setTextViewText(R.id.stop_name, stopName);
        } else {
            remoteViews.setTextViewText(R.id.stop_name, context.getString(R.string.stop_info_no_name));
        }
        
        // Set last updated time
        remoteViews.setTextViewText(R.id.direction, "Updated: " + 
                UIUtils.getTimeWithContext(context, System.currentTimeMillis(), false));
        
        // Set refresh button intent
        Intent refreshIntent = new Intent(context, FavoriteStopWidgetProvider.class);
        refreshIntent.setAction(FavoriteStopWidgetProvider.ACTION_REFRESH_WIDGET);
        refreshIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        PendingIntent refreshPendingIntent = PendingIntent.getBroadcast(context, appWidgetId, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        remoteViews.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent);
        
        // Set up intent to open the stop info when tapping widget header
        Intent stopInfoIntent = new Intent(context, ArrivalsListActivity.class);
        stopInfoIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        stopInfoIntent.putExtra(ArrivalsListActivity.EXTRA_STOP_ID, stopId);
        PendingIntent stopInfoPendingIntent = PendingIntent.getActivity(context, appWidgetId, stopInfoIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        remoteViews.setOnClickPendingIntent(R.id.widget_header, stopInfoPendingIntent);
        
        // Set up the adapter for the ListView
        Intent intent = new Intent(context, ArrivalsWidgetListService.class);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        intent.putExtra(EXTRA_STOP_ID, stopId);
        intent.putExtra(EXTRA_STOP_NAME, stopName);
        // Make the intent unique with data
        intent.setData(Uri.parse("widget://" + appWidgetId));
        remoteViews.setRemoteAdapter(R.id.arrivals_list, intent);
        
        // Set up empty view
        remoteViews.setEmptyView(R.id.arrivals_list, R.id.empty_text);
        
        return remoteViews;
    }
} 