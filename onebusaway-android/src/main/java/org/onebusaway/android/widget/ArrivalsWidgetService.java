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
        views.setTextViewText(R.id.no_arrivals, "DEBUG: Loading for stop ID: " + stopId);
        appWidgetManager.updateAppWidget(widgetId, views);
        
        Log.d(TAG, "Loading screen set for widget");

        try {
            // Fetch arrival data using ObaArrivalInfoRequest
            Log.d(TAG, "Creating request for stop ID: " + stopId);
            ObaArrivalInfoRequest request = ObaArrivalInfoRequest.newRequest(this, stopId);
            Log.d(TAG, "Executing API call");
            ObaArrivalInfoResponse response = request.call();
            Log.d(TAG, "API call completed");

            if (response == null) {
                Log.e(TAG, "Response is null");
                views.setTextViewText(R.id.direction, "ERROR: Null response");
                views.setTextViewText(R.id.no_arrivals, "DEBUG: API returned null response for stop ID: " + stopId);
                appWidgetManager.updateAppWidget(widgetId, views);
                return;
            }
            
            Log.d(TAG, "Response code: " + response.getCode());
            
            if (response.getCode() != ObaApi.OBA_OK) {
                // Handle error
                Log.e(TAG, "Error response code: " + response.getCode());
                views.setTextViewText(R.id.direction, "Error " + response.getCode());
                views.setTextViewText(R.id.no_arrivals, "DEBUG: API error for stop ID: " + stopId + "\nCode: " + response.getCode());
                appWidgetManager.updateAppWidget(widgetId, views);
                return;
            }

            // Process arrival data
            ObaStop stop = response.getStop();
            if (stop == null) {
                Log.e(TAG, "Stop information is null");
                views.setTextViewText(R.id.direction, "ERROR: No stop info");
                views.setTextViewText(R.id.no_arrivals, "DEBUG: No stop information returned for ID: " + stopId);
                appWidgetManager.updateAppWidget(widgetId, views);
                return;
            }
            
            Log.d(TAG, "Stop info: " + stop.getName() + " (ID: " + stop.getId() + ")");
            
            ObaArrivalInfo[] arrivals = response.getArrivalInfo();
            if (arrivals == null) {
                Log.e(TAG, "Arrivals array is null");
                views.setTextViewText(R.id.direction, "ERROR: Null arrivals");
                views.setTextViewText(R.id.no_arrivals, "DEBUG: Null arrivals array for stop: " + stop.getName());
                appWidgetManager.updateAppWidget(widgetId, views);
                return;
            }
            
            Log.d(TAG, "Arrivals count: " + arrivals.length);

            // Check if we have arrivals
            if (arrivals.length == 0) {
                Log.d(TAG, "No arrivals found");
                views.setTextViewText(R.id.stop_name, stopName != null ? stopName : stop.getName());
                views.setTextViewText(R.id.direction, "No arrivals found");
                views.setTextViewText(R.id.no_arrivals, "DEBUG: No upcoming arrivals for stop: " + stop.getName());
                appWidgetManager.updateAppWidget(widgetId, views);
                return;
            }

            // Format basic debug text with arrival info
            StringBuilder debugText = new StringBuilder();
            debugText.append("DEBUG: Stop ID: ").append(stopId).append("\n");
            debugText.append("Stop name: ").append(stop.getName()).append("\n");
            debugText.append("Found ").append(arrivals.length).append(" arrivals\n\n");
            
            // Add first 3 arrivals as simple text
            int count = Math.min(arrivals.length, 3);
            for (int i = 0; i < count; i++) {
                ObaArrivalInfo arrival = arrivals[i];
                debugText.append("Route: ").append(arrival.getShortName()).append("\n");
                debugText.append("To: ").append(arrival.getHeadsign()).append("\n");
                
                // Simple time calculation
                long eta = arrival.getPredictedArrivalTime();
                if (eta == 0) {
                    eta = arrival.getScheduledArrivalTime();
                }
                
                long now = System.currentTimeMillis();
                long minutes = (eta - now) / 60000;
                
                debugText.append("ETA: ");
                if (minutes <= 0) {
                    debugText.append("now");
                } else {
                    debugText.append(minutes).append(" min");
                }
                debugText.append("\n\n");
            }

            // Update the widget with simple text data
            Log.d(TAG, "Updating widget with arrival data");
            views.setTextViewText(R.id.stop_name, stopName != null ? stopName : stop.getName());
            views.setTextViewText(R.id.direction, "Arrivals for stop: " + stop.getName());
            views.setTextViewText(R.id.no_arrivals, debugText.toString());
            appWidgetManager.updateAppWidget(widgetId, views);
            Log.d(TAG, "Widget updated successfully");

        } catch (Exception e) {
            Log.e(TAG, "Error fetching arrivals", e);
            views.setTextViewText(R.id.direction, "Error: " + e.getClass().getSimpleName());
            views.setTextViewText(R.id.no_arrivals, "DEBUG EXCEPTION: " + e.getMessage() + "\nFor stop ID: " + stopId);
            appWidgetManager.updateAppWidget(widgetId, views);
        }
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