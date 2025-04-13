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

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.onebusaway.android.R;

/**
 * Activity that displays settings for a specific widget
 */
public class WidgetSettingsActivity extends Activity {
    private static final String TAG = "WidgetSettingsActivity";
    
    private int mAppWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_widget_settings);
        
        // Initialize the application if needed
        initializeAppIfNeeded();
        
        // Get the widget ID
        Intent intent = getIntent();
        if (intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)) {
            mAppWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            Log.d(TAG, "Settings opened for widget ID: " + mAppWidgetId);
        } else {
            Log.e(TAG, "No widget ID provided");
            finish();
            return;
        }
        
        // Get stop info for this widget
        String stopId = FavoriteStopWidgetProvider.getStopIdForWidget(this, mAppWidgetId);
        String stopName = FavoriteStopWidgetProvider.getStopNameForWidget(this, mAppWidgetId);
        
        TextView stopInfoText = findViewById(R.id.widget_settings_info);
        if (stopId != null && stopName != null) {
            stopInfoText.setText(getString(R.string.widget_settings_current_stop, stopName));
        } else {
            stopInfoText.setText(R.string.widget_settings_no_stop);
        }
        
        // Set up change stop button
        Button changeStopButton = findViewById(R.id.widget_settings_change_stop);
        changeStopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Open the stop selector
                Intent selectorIntent = new Intent(WidgetSettingsActivity.this, 
                        FavoriteStopWidgetProvider.StopSelectorActivity.class);
                selectorIntent.setAction(FavoriteStopWidgetProvider.ACTION_STOP_SELECTOR);
                selectorIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId);
                startActivity(selectorIntent);
                
                // Close settings after opening selector
                finish();
            }
        });
        
        // Set up close button
        Button closeButton = findViewById(R.id.widget_settings_close);
        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }
    
    /**
     * Make sure the Application class is initialized properly
     */
    private void initializeAppIfNeeded() {
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
} 