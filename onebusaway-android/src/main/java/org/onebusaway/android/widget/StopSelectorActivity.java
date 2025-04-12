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
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.onebusaway.android.R;
import org.onebusaway.android.app.Application;
import org.onebusaway.android.io.elements.ObaRegion;
import org.onebusaway.android.provider.ObaContract;

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
        
        // Enable display over lock screen
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        
        // Get widget ID
        Intent intent = getIntent();
        if (intent != null) {
            mAppWidgetId = intent.getIntExtra(
                    FavoriteStopWidgetProvider.EXTRA_WIDGET_ID, 
                    AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        
        if (mAppWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            Log.e(TAG, "Invalid widget ID, finishing silently");
            finish();
            return;
        }
        
        // Set dialog style
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_stop_selector);
        
        // Configure as bottom sheet
        setupBottomSheetStyle();
        
        // Fetch starred stops from database
        loadStarredStops();
        
        // Add touch outside to dismiss
        findViewById(R.id.stop_selector_root).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // Prevent clicks on the main container from dismissing
        findViewById(R.id.stop_selector_container).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Do nothing, prevent propagation
            }
        });
    }
    
    private void setupBottomSheetStyle() {
        Window window = getWindow();
        
        // Set background to transparent
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        
        // Set layout parameters
        WindowManager.LayoutParams params = window.getAttributes();
        params.gravity = Gravity.BOTTOM;
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        
        // Use TYPE_TOAST to make sure it appears on top of everything
        params.type = WindowManager.LayoutParams.TYPE_TOAST;
        
        // Apply parameters
        window.setAttributes(params);
        
        // Add dim background
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.6f);
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
            
            // Query the content provider
            Cursor c = getContentResolver().query(
                    ObaContract.Stops.CONTENT_URI,
                    new String[]{
                            ObaContract.Stops._ID,
                            ObaContract.Stops.NAME,
                            ObaContract.Stops.UI_NAME
                    },
                    selection,
                    null,
                    ObaContract.Stops.USE_COUNT + " DESC");
            
            if (c != null) {
                while (c.moveToNext()) {
                    String stopId = c.getString(c.getColumnIndex(ObaContract.Stops._ID));
                    String stopName = c.getString(c.getColumnIndex(ObaContract.Stops.NAME));
                    String uiName = c.getString(c.getColumnIndex(ObaContract.Stops.UI_NAME));
                    
                    // Use UI name if available, otherwise use stop name
                    String displayName = !TextUtils.isEmpty(uiName) ? uiName : (stopName != null ? stopName : stopId);
                    
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
                
                Button openAppButton = new Button(this);
                openAppButton.setText("Open OneBusAway");
                openAppButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // Open main app
                        Intent intent = new Intent(StopSelectorActivity.this, 
                                org.onebusaway.android.ui.HomeActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        finish();
                    }
                });
                
                container.addView(openAppButton);
            }
            return;
        }
        
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
} 