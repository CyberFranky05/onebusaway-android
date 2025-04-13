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
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import org.onebusaway.android.R;
import org.onebusaway.android.app.Application;
import org.onebusaway.android.io.ObaApi;
import org.onebusaway.android.io.elements.ObaArrivalInfo;
import org.onebusaway.android.io.elements.ObaStop;
import org.onebusaway.android.io.request.ObaArrivalInfoRequest;
import org.onebusaway.android.io.request.ObaArrivalInfoResponse;
import org.onebusaway.android.util.UIUtils;
import org.onebusaway.android.widget.WidgetArrivalViewBuilder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

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

        Log.d(TAG, "Updating arrivals for stop: " + stopId + " (widget " + widgetId + ")");

        // Get widget manager and views
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(this);
        RemoteViews views = new RemoteViews(getPackageName(), R.layout.widget_favorite_stop);

        // Show loading indicator
        views.setTextViewText(R.id.stop_name, stopName != null ? stopName : "Loading...");
        views.setTextViewText(R.id.direction, "Loading arrivals data...");
        views.setTextViewText(R.id.no_arrivals, "Checking for arrivals at stop " + stopId);
        appWidgetManager.updateAppWidget(widgetId, views);
        
        Log.d(TAG, "Loading screen set for widget");

        try {
            // Ensure Application is initialized
            initializeAppIfNeeded();
            
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
                    views.setTextViewText(R.id.no_arrivals, "No upcoming arrivals at this time.");
                } else {
                    // Use the WidgetArrivalViewBuilder to format the arrivals as text
                    String arrivalsText = WidgetArrivalViewBuilder.formatArrivalsAsText(this, arrivals, 4);
                    views.setTextViewText(R.id.no_arrivals, arrivalsText);
                    Log.d(TAG, "Set arrival text for " + arrivalsCount + " arrivals");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error setting up arrival text", e);
                views.setTextViewText(R.id.no_arrivals, "Error displaying arrivals. Please try again.");
            }
            
            // Restore refresh button functionality
            setRetryRefreshButton(views, stopId, stopName, widgetId);
            
            // Update widget with the new views
            try {
                appWidgetManager.updateAppWidget(widgetId, views);
                Log.d(TAG, "Widget updated successfully with arrival text");
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
     */
    private void initializeAppIfNeeded() {
        try {
            // Ensure OBA API is initialized
            Application app = Application.get();
            if (app != null) {
                Log.d(TAG, "Application already initialized");
            }
        } catch (Exception e) {
            Log.d(TAG, "Initializing application from widget service");
            try {
                // Initialize application manually
                Application app = Application.get();
                if (app != null) {
                    // App already initialized
                    Log.d(TAG, "Application already initialized");
                } else {
                    Log.e(TAG, "Could not initialize application");
                }
            } catch (Exception ex) {
                Log.e(TAG, "Error initializing application from widget service", ex);
            }
        }
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
     * Utility method to request an update for a specific stop
     */
    public static void requestUpdate(Context context, String stopId, String stopName, int widgetId) {
        Log.d(TAG, "Requesting update for widget " + widgetId + ", stop " + stopId + " (" + stopName + ")");
        Intent intent = new Intent(context, ArrivalsWidgetService.class);
        intent.setAction(ACTION_UPDATE_ARRIVALS);
        intent.putExtra(EXTRA_STOP_ID, stopId);
        intent.putExtra(EXTRA_STOP_NAME, stopName);
        intent.putExtra(EXTRA_WIDGET_ID, widgetId);
        context.startService(intent);
    }
} 