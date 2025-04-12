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
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.BaseColumns;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.onebusaway.android.R;
import org.onebusaway.android.app.Application;
import org.onebusaway.android.io.elements.ObaRegion;
import org.onebusaway.android.provider.ObaContract;
import org.onebusaway.android.ui.HomeActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Bottom sheet style activity that shows a list of starred stops for the user to select
 * for display in the widget.
 */
public class StopSelectorActivity extends Activity {
    private static final String TAG = "StopSelectorActivity";
    
    private List<FavoriteStopWidgetProvider.StarredStop> mStarredStops;
    private int mAppWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Log.d(TAG, "StopSelectorActivity.onCreate() started");
        
        // Set the activity style as a dialog
        setContentView(R.layout.activity_stop_selector);
        
        // Make it a proper dialog
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        
        // Set proper layout dimensions
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.BOTTOM;
        window.setAttributes(params);
        
        // Add dim to background
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.7f);
        
        // Get widget ID
        Intent intent = getIntent();
        if (intent != null) {
            mAppWidgetId = intent.getIntExtra(
                    FavoriteStopWidgetProvider.EXTRA_WIDGET_ID, 
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            Log.d(TAG, "Got widget ID from intent: " + mAppWidgetId);
        }
        
        if (mAppWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            Log.e(TAG, "Invalid widget ID, finishing silently");
            finish();
            return;
        }
        
        // Fetch starred stops from database
        loadStarredStops();
        
        // Set up touch handlers
        View rootView = findViewById(R.id.stop_selector_root);
        if (rootView != null) {
            rootView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }
        
        View containerView = findViewById(R.id.stop_selector_container);
        if (containerView != null) {
            containerView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Prevent propagation to root view
                }
            });
        }
    }
    
    /**
     * Load starred stops from the database
     */
    private void loadStarredStops() {
        try {
            mStarredStops = new ArrayList<>();
            String selection = ObaContract.Stops.FAVORITE + "=1";
            
            // Consider applying region filter
            ObaRegion region = Application.get().getCurrentRegion();
            if (region != null) {
                selection += " AND " + ObaContract.Stops.REGION_ID + "=" + region.getId();
            }
            
            Log.d(TAG, "Querying for starred stops with selection: " + selection);
            
            // Query the content provider
            Cursor c = getContentResolver().query(
                    ObaContract.Stops.CONTENT_URI,
                    new String[]{
                            ObaContract.Stops._ID,
                            ObaContract.Stops.NAME,
                            ObaContract.Stops.UI_NAME,
                            ObaContract.Stops.DIRECTION
                    },
                    selection,
                    null,
                    ObaContract.Stops.USE_COUNT + " DESC");
            
            if (c != null) {
                Log.d(TAG, "Found " + c.getCount() + " starred stops");
                while (c.moveToNext()) {
                    String stopId = c.getString(c.getColumnIndex(ObaContract.Stops._ID));
                    String stopName = c.getString(c.getColumnIndex(ObaContract.Stops.NAME));
                    String uiName = c.getString(c.getColumnIndex(ObaContract.Stops.UI_NAME));
                    
                    // Use UI name if available, otherwise use stop name
                    String displayName = !TextUtils.isEmpty(uiName) ? uiName : (stopName != null ? stopName : stopId);
                    
                    Log.d(TAG, "Adding starred stop: " + stopId + " - " + displayName);
                    mStarredStops.add(new FavoriteStopWidgetProvider.StarredStop(stopId, displayName));
                }
                c.close();
            }
            
            // Now setup the list
            setupListView();
            
        } catch (Exception e) {
            Log.e(TAG, "Error loading starred stops: " + e.getMessage(), e);
            Toast.makeText(this, 
                    "Error loading stops: " + e.getMessage(), 
                    Toast.LENGTH_SHORT).show();
            finish();
        }
    }
    
    private void setupListView() {
        ListView listView = findViewById(R.id.stop_list);
        
        if (mStarredStops == null || mStarredStops.isEmpty()) {
            TextView errorText = new TextView(this);
            errorText.setText("No starred stops found. Please star some stops in the OneBusAway app first.");
            errorText.setPadding(32, 32, 32, 32);
            errorText.setGravity(android.view.Gravity.CENTER);
            
            ViewGroup container = findViewById(R.id.stop_selector_container);
            if (container != null) {
                container.removeAllViews();
                container.addView(errorText);
                
                // Add a button that the user must tap to open the app
                Button openAppButton = new Button(this);
                openAppButton.setText("Open OneBusAway");
                openAppButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // Open main app on button click
                        try {
                            Intent intent = new Intent(StopSelectorActivity.this, 
                                    org.onebusaway.android.ui.HomeActivity.class);
                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                            finish();
                        } catch (Exception e) {
                            Log.e(TAG, "Error opening main app: " + e.getMessage(), e);
                            Toast.makeText(StopSelectorActivity.this, 
                                    "Error: " + e.getMessage(), 
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
                
                // Add cancel button
                Button cancelButton = new Button(this);
                cancelButton.setText("Cancel");
                cancelButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        finish();
                    }
                });
                
                // Add buttons in a horizontal layout
                LinearLayout buttonLayout = new LinearLayout(this);
                buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
                buttonLayout.setGravity(Gravity.CENTER);
                buttonLayout.setPadding(32, 16, 32, 32);
                
                // Set layout params for buttons
                LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                buttonParams.setMargins(8, 0, 8, 0);
                
                buttonLayout.addView(cancelButton, buttonParams);
                buttonLayout.addView(openAppButton, buttonParams);
                
                container.addView(buttonLayout);
            }
            return;
        }
        
        Log.d(TAG, "Setting up list view with " + mStarredStops.size() + " stops");
        
        // Create a better styled adapter with more visual feedback
        ArrayAdapter<FavoriteStopWidgetProvider.StarredStop> adapter = 
                new ArrayAdapter<FavoriteStopWidgetProvider.StarredStop>(
                this, R.layout.simple_list_item, mStarredStops) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view;
                if (convertView == null) {
                    view = getLayoutInflater().inflate(R.layout.simple_list_item, parent, false);
                } else {
                    view = convertView;
                }
                
                TextView textView = view.findViewById(android.R.id.text1);
                String displayName = mStarredStops.get(position).getDisplayName();
                if (displayName == null || displayName.isEmpty()) {
                    displayName = "Stop " + (position + 1);
                }
                textView.setText(displayName);
                
                // Set background selector for better touch feedback
                view.setBackgroundResource(android.R.drawable.list_selector_background);
                
                return view;
            }
        };
        
        listView.setAdapter(adapter);
        
        // Add divider
        listView.setDivider(new ColorDrawable(Color.parseColor("#DDDDDD")));
        listView.setDividerHeight(1);
        
        // Set click listener
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                FavoriteStopWidgetProvider.StarredStop stop = mStarredStops.get(position);
                
                try {
                    Log.d(TAG, "User selected stop: " + stop.getStopId() + " (" + stop.getDisplayName() + ") for widget " + mAppWidgetId);
                    
                    // Create explicit intent for the widget provider
                    Intent intent = new Intent(StopSelectorActivity.this, FavoriteStopWidgetProvider.class);
                    intent.setAction(FavoriteStopWidgetProvider.ACTION_SELECT_STOP);
                    intent.putExtra(FavoriteStopWidgetProvider.EXTRA_WIDGET_ID, mAppWidgetId);
                    intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId);
                    intent.putExtra(FavoriteStopWidgetProvider.EXTRA_STOP_ID, stop.getStopId());
                    intent.putExtra(FavoriteStopWidgetProvider.EXTRA_STOP_NAME, stop.getDisplayName());
                    
                    // Use component name to make it explicit
                    intent.setComponent(new android.content.ComponentName(
                            StopSelectorActivity.this,
                            FavoriteStopWidgetProvider.class));
                    
                    // Send broadcast
                    sendBroadcast(intent);
                    
                    // Log all the details
                    Log.d(TAG, "Sent ACTION_SELECT_STOP broadcast with widget ID: " + mAppWidgetId +
                            ", stop ID: " + stop.getStopId() + 
                            ", stop name: " + stop.getDisplayName());
                    
                    // Show feedback
                    Toast.makeText(StopSelectorActivity.this, 
                            "Selected stop: " + stop.getDisplayName(), 
                            Toast.LENGTH_SHORT).show();
                    
                    // Force an immediate update to the widget
                    AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(StopSelectorActivity.this);
                    FavoriteStopWidgetProvider widgetProvider = new FavoriteStopWidgetProvider();
                    widgetProvider.onReceive(StopSelectorActivity.this, intent);
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error sending stop selection broadcast: " + e.getMessage(), e);
                    Toast.makeText(StopSelectorActivity.this,
                            "Error selecting stop: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                }
                
                // Close activity
                finish();
            }
        });
    }

    @Override
    public void finish() {
        super.finish();
        
        // Apply slide-down animation when closing
        overridePendingTransition(0, R.anim.slide_down);
    }
} 