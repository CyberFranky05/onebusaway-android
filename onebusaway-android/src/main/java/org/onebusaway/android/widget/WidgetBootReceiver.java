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

import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Broadcast receiver to handle device boot events and reinitialize widgets
 */
public class WidgetBootReceiver extends BroadcastReceiver {
    private static final String TAG = "WidgetBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() != null && 
                intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) {
            Log.d(TAG, "Boot completed, reinitializing widgets");
            
            // Delay the widget initialization to ensure system is fully booted
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                initializeWidgets(context);
            }, 30000); // 30 second delay
        }
    }
    
    /**
     * Initialize all existing widgets after boot
     */
    private void initializeWidgets(Context context) {
        try {
            Log.d(TAG, "Initializing widgets after boot");
            
            // Get all widget IDs for this provider
            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
            ComponentName thisWidget = new ComponentName(context, FavoriteStopWidgetProvider.class);
            int[] appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget);
            
            Log.d(TAG, "Found " + appWidgetIds.length + " widgets to initialize");
            
            // For each widget, reload the data
            for (int appWidgetId : appWidgetIds) {
                // Get saved stop information
                String stopId = FavoriteStopWidgetProvider.getStopIdForWidget(context, appWidgetId);
                String stopName = FavoriteStopWidgetProvider.getStopNameForWidget(context, appWidgetId);
                
                if (stopId != null) {
                    Log.d(TAG, "Initializing widget " + appWidgetId + " for stop " + stopId);
                    
                    // Request data update for the widget
                    ArrivalsWidgetService.requestUpdate(context, stopId, stopName, appWidgetId);
                    
                    // Schedule automatic updates
                    SharedPreferences prefs = context.getSharedPreferences(
                            FavoriteStopWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE);
                    boolean autoRefresh = prefs.getBoolean(
                            FavoriteStopWidgetProvider.PREF_AUTO_REFRESH_PREFIX + appWidgetId, true);
                    
                    if (autoRefresh) {
                        Log.d(TAG, "Setting up auto-refresh for widget " + appWidgetId);
                        // Create instance of the widget provider to access private methods
                        FavoriteStopWidgetProvider provider = new FavoriteStopWidgetProvider();
                        // Use reflection to access the scheduleWidgetUpdate method which is private
                        try {
                            java.lang.reflect.Method method = FavoriteStopWidgetProvider.class
                                    .getDeclaredMethod("scheduleWidgetUpdate", Context.class, int.class);
                            method.setAccessible(true);
                            method.invoke(provider, context, appWidgetId);
                        } catch (Exception e) {
                            Log.e(TAG, "Error scheduling widget updates", e);
                        }
                    }
                } else {
                    Log.d(TAG, "Widget " + appWidgetId + " has no saved stop, skipping initialization");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing widgets after boot", e);
        }
    }
} 