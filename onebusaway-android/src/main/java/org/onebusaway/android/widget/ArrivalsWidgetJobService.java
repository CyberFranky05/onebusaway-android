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

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.PersistableBundle;
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
import org.onebusaway.android.ui.HomeActivity;
import org.onebusaway.android.util.UIUtils;
import org.onebusaway.android.widget.FavoriteStopWidgetProvider;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * JobService to handle widget update requests in the background
 */
public class ArrivalsWidgetJobService extends JobService {
    private static final String TAG = "ArrivalsWidgetJobSvc";
    private FetchArrivalsTask mCurrentTask = null;
    private final Executor mExecutor = Executors.newSingleThreadExecutor();

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.d(TAG, "Job started");
        
        // Get the extras from the job
        PersistableBundle extras = params.getExtras();
        if (extras == null) {
            Log.e(TAG, "No extras in job parameters");
            jobFinished(params, false);
            return false;
        }
        
        String stopId = extras.getString(ArrivalsWidgetService.EXTRA_STOP_ID);
        String stopName = extras.getString(ArrivalsWidgetService.EXTRA_STOP_NAME);
        int widgetId = extras.getInt(ArrivalsWidgetService.EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        
        if (stopId == null || widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            Log.e(TAG, "Missing required job parameters");
            jobFinished(params, false);
            return false;
        }
        
        Log.d(TAG, "Processing update request for widget " + widgetId + ", stop " + stopId);
        
        // Instead of starting a service (which fails with BackgroundServiceStartNotAllowedException),
        // update the widget directly from the JobService
        mCurrentTask = new FetchArrivalsTask(this, stopId, stopName, widgetId, params);
        mCurrentTask.executeOnExecutor(mExecutor);
        
        // Return true to indicate we're still doing work on another thread
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Log.d(TAG, "Job stopped");
        if (mCurrentTask != null) {
            mCurrentTask.cancel(true);
            mCurrentTask = null;
        }
        return false; // Don't reschedule
    }
    
    /**
     * AsyncTask to fetch arrivals data and update widget
     */
    private static class FetchArrivalsTask {
        private final Context mContext;
        private final String mStopId;
        private final String mStopName;
        private final int mWidgetId;
        private final JobParameters mParams;
        private final JobService mJobService;
        private boolean mCancelled = false;
        
        FetchArrivalsTask(JobService jobService, String stopId, String stopName, int widgetId, JobParameters params) {
            mContext = jobService.getApplicationContext();
            mStopId = stopId;
            mStopName = stopName;
            mWidgetId = widgetId;
            mParams = params;
            mJobService = jobService;
        }
        
        void executeOnExecutor(Executor executor) {
            executor.execute(this::doInBackground);
        }
        
        void cancel(boolean mayInterruptIfRunning) {
            mCancelled = true;
        }
        
        private void doInBackground() {
            try {
                if (mCancelled) return;
                
                // Make sure the app is initialized
                if (!initializeApp()) {
                    Log.e(TAG, "Failed to initialize app");
                    updateWidgetWithError();
                    mJobService.jobFinished(mParams, false);
                    return;
                }
                
                // Fetch arrivals data
                ObaArrivalInfoResponse response = fetchArrivalsData();
                if (mCancelled) return;
                
                if (response == null) {
                    Log.e(TAG, "Arrivals response is null");
                    updateWidgetWithError();
                    mJobService.jobFinished(mParams, false);
                    return;
                }
                
                // Process the response and update widget
                if (response.getCode() == 200) {
                    ObaStop stop = response.getStop();
                    ObaArrivalInfo[] arrivals = response.getArrivalInfo();
                    
                    if (stop != null) {
                        updateWidgetWithArrivals(stop, arrivals);
                    } else {
                        Log.e(TAG, "Stop object is null in the response");
                        updateWidgetWithError();
                    }
                } else {
                    Log.e(TAG, "Error in arrivals response: " + response.getCode());
                    updateWidgetWithError();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching arrivals", e);
                updateWidgetWithError();
            } finally {
                mJobService.jobFinished(mParams, false);
            }
        }
        
        private boolean initializeApp() {
            try {
                Application app = Application.get();
                if (app == null) {
                    Log.e(TAG, "Application instance is null");
                    return false;
                }
                
                ObaRegion region = app.getCurrentRegion();
                if (region == null) {
                    Log.e(TAG, "Current region is null");
                    return false;
                }
                
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Error initializing application", e);
                return false;
            }
        }
        
        private ObaArrivalInfoResponse fetchArrivalsData() {
            try {
                ObaArrivalInfoRequest request = ObaArrivalInfoRequest.newRequest(mContext, mStopId);
                return request.call();
            } catch (Exception e) {
                Log.e(TAG, "Error fetching arrivals data", e);
                return null;
            }
        }
        
        private void updateWidgetWithArrivals(ObaStop stop, ObaArrivalInfo[] arrivals) {
            try {
                AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(mContext);
                RemoteViews views = new RemoteViews(mContext.getPackageName(), R.layout.widget_favorite_stop);
                
                // Set stop name
                views.setTextViewText(R.id.stop_name, mStopName != null ? mStopName : stop.getName());
                
                // Set direction/stop info
                String direction = stop.getDirection();
                if (direction != null && !direction.isEmpty()) {
                    views.setTextViewText(R.id.direction, direction);
                } else {
                    views.setTextViewText(R.id.direction, stop.getName());
                }
                
                // Reset visibility
                views.setViewVisibility(R.id.no_arrivals, View.GONE);
                
                // Format and add arrivals
                if (arrivals != null && arrivals.length > 0) {
                    // Instead of using WidgetArrivalViewBuilder which doesn't exist with the expected signature,
                    // directly update the views with arrival information
                    updateViewsWithArrivals(views, arrivals);
                } else {
                    views.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
                    views.setTextViewText(R.id.no_arrivals, "No upcoming arrivals");
                }
                
                // Set up all the pending intents for widget interactions
                setupWidgetActions(views);
                
                // Update the widget
                appWidgetManager.updateAppWidget(mWidgetId, views);
                
            } catch (Exception e) {
                Log.e(TAG, "Error updating widget with arrivals", e);
                updateWidgetWithError();
            }
        }
        
        /**
         * Update views with arrival information
         */
        private void updateViewsWithArrivals(RemoteViews views, ObaArrivalInfo[] arrivals) {
            if (arrivals.length == 0) {
                return;
            }
            
            try {
                // Since we don't have dedicated views for arrivals in the layout,
                // we'll format arrivals information as text in the no_arrivals TextView
                StringBuilder arrivalsText = new StringBuilder();
                
                // Format the first few arrivals (up to 3)
                int count = Math.min(arrivals.length, 3);
                for (int i = 0; i < count; i++) {
                    ObaArrivalInfo arrival = arrivals[i];
                    
                    // Get route name and destination
                    String routeName = arrival.getShortName();
                    String destination = arrival.getHeadsign();
                    
                    // Format arrival time
                    long timeInMillis = arrival.getPredictedArrivalTime();
                    if (timeInMillis == 0) {
                        timeInMillis = arrival.getScheduledArrivalTime();
                    }
                    
                    String formattedTime = formatArrivalTime(timeInMillis);
                    
                    // Format as a single line: "RouteNumber - Destination: Time"
                    arrivalsText.append(routeName)
                            .append(" - ")
                            .append(destination)
                            .append(": ")
                            .append(formattedTime);
                    
                    // Add a newline if not the last item
                    if (i < count - 1) {
                        arrivalsText.append("\n");
                    }
                }
                
                // Update the no_arrivals TextView with our formatted text
                views.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
                views.setTextViewText(R.id.no_arrivals, arrivalsText.toString());
                
            } catch (Exception e) {
                Log.e(TAG, "Error formatting arrivals", e);
                // Fallback to simple message
                views.setTextViewText(R.id.no_arrivals, "Unable to format arrivals");
            }
        }
        
        /**
         * Format arrival time as a human-readable string
         */
        private String formatArrivalTime(long timeInMillis) {
            long currentTimeMillis = System.currentTimeMillis();
            long differenceMillis = timeInMillis - currentTimeMillis;
            
            if (differenceMillis < 0) {
                return "Departed";
            }
            
            // Convert to minutes
            long minutesDifference = differenceMillis / (60 * 1000);
            
            if (minutesDifference < 1) {
                return "Now";
            } else if (minutesDifference == 1) {
                return "1 min";
            } else if (minutesDifference < 60) {
                return minutesDifference + " mins";
            } else {
                // Format as time
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.US);
                return sdf.format(new java.util.Date(timeInMillis));
            }
        }
        
        private void updateWidgetWithError() {
            try {
                AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(mContext);
                RemoteViews views = new RemoteViews(mContext.getPackageName(), R.layout.widget_favorite_stop);
                
                // Set basic info
                views.setTextViewText(R.id.stop_name, mStopName != null ? mStopName : "Bus Stop");
                views.setTextViewText(R.id.direction, "Could not load arrivals");
                
                // Show error message
                views.setViewVisibility(R.id.no_arrivals, View.VISIBLE);
                views.setTextViewText(R.id.no_arrivals, "Tap refresh to try again");
                
                // Set up all the pending intents for widget interactions
                setupWidgetActions(views);
                
                // Update the widget
                appWidgetManager.updateAppWidget(mWidgetId, views);
                
            } catch (Exception e) {
                Log.e(TAG, "Error updating widget with error state", e);
            }
        }
        
        private void setupWidgetActions(RemoteViews views) {
            // Set up click intent for the widget to open the app
            Intent appIntent = new Intent(mContext, org.onebusaway.android.ui.HomeActivity.class);
            appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            views.setOnClickPendingIntent(R.id.widget_layout, android.app.PendingIntent.getActivity(
                    mContext, mWidgetId * 100, appIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE));
            
            // Set up settings intent (using header area)
            Intent settingsIntent = new Intent(mContext, FavoriteStopWidgetProvider.class);
            settingsIntent.setAction("org.onebusaway.android.widget.ACTION_OPEN_SETTINGS");
            settingsIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mWidgetId);
            views.setOnClickPendingIntent(R.id.widget_header, android.app.PendingIntent.getBroadcast(
                    mContext, mWidgetId + 1000, settingsIntent, 
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE));
            
            // Set up click intent for the stop selector
            Intent selectorIntent = new Intent(mContext, FavoriteStopWidgetProvider.StopSelectorActivity.class);
            selectorIntent.setAction(FavoriteStopWidgetProvider.ACTION_STOP_SELECTOR);
            selectorIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mWidgetId);
            views.setOnClickPendingIntent(R.id.stop_selector, android.app.PendingIntent.getActivity(
                    mContext, mWidgetId, selectorIntent, 
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE));
            
            // Set up refresh click intent
            Intent refreshIntent = new Intent(mContext, FavoriteStopWidgetProvider.class);
            refreshIntent.setAction(FavoriteStopWidgetProvider.ACTION_REFRESH_WIDGET);
            refreshIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mWidgetId);
            views.setOnClickPendingIntent(R.id.widget_refresh, android.app.PendingIntent.getBroadcast(
                    mContext, mWidgetId + 2000, refreshIntent, 
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE));
        }
    }
} 