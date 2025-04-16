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

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Utility methods for widget-related operations
 */
public class WidgetUtil {
    private static final String TAG = "WidgetUtil";
    
    /**
     * Static flag to track if we've already disabled OneSignal
     */
    private static boolean sOneSignalDisabled = false;
    
    /**
     * Disable OneSignal in widget processes to prevent login errors
     * This uses reflection to avoid direct dependencies on the OneSignal SDK
     */
    public static void disableOneSignalInWidgetProcess(Context context) {
        if (sOneSignalDisabled) {
            return; // Already disabled
        }
        
        try {
            // Check if we're in a widget process
            if (!isWidgetProcess()) {
                return; // Not a widget process, don't disable
            }
            
            Log.d(TAG, "Disabling OneSignal in widget process");
            
            // Try to find the OneSignal class
            Class<?> oneSignalClass = null;
            try {
                oneSignalClass = Class.forName("com.onesignal.OneSignal");
            } catch (ClassNotFoundException e) {
                // OneSignal not available, nothing to disable
                return;
            }
            
            // Try to call the disable method if it exists
            try {
                Method disableMethod = oneSignalClass.getMethod("disablePush", boolean.class);
                disableMethod.invoke(null, true);
                Log.d(TAG, "Successfully disabled OneSignal push in widget process");
            } catch (Exception e) {
                Log.e(TAG, "Error disabling OneSignal push", e);
            }
            
            sOneSignalDisabled = true;
        } catch (Exception e) {
            Log.e(TAG, "Error during OneSignal disable process", e);
        }
    }
    
    /**
     * Check if we're running in a widget-related process
     */
    private static boolean isWidgetProcess() {
        try {
            // Get the current process name
            String processName = getCurrentProcessName();
            
            // Check if it contains widget-related keywords
            if (processName != null && 
                    (processName.contains("widget") || 
                     processName.contains(":remote") || 
                     processName.contains(":appwidget"))) {
                Log.d(TAG, "Detected widget process: " + processName);
                return true;
            }
            
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error checking if widget process", e);
            return false;
        }
    }
    
    /**
     * Get the current process name by reading /proc/self/cmdline
     */
    private static String getCurrentProcessName() {
        try {
            File cmdline = new File("/proc/self/cmdline");
            byte[] data = new byte[128];
            
            java.io.FileInputStream fis = new java.io.FileInputStream(cmdline);
            int read = fis.read(data);
            fis.close();
            
            if (read > 0) {
                int i;
                for (i = 0; i < read; i++) {
                    if (data[i] == 0) {
                        break;
                    }
                }
                
                return new String(data, 0, i);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting process name", e);
        }
        
        return null;
    }
} 