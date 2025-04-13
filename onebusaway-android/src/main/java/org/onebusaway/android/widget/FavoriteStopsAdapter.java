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

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.onebusaway.android.R;
import org.onebusaway.android.widget.FavoriteStopManager.FavoriteStop;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for displaying favorite stops in a RecyclerView
 */
public class FavoriteStopsAdapter extends RecyclerView.Adapter<FavoriteStopsAdapter.ViewHolder> {

    private List<FavoriteStop> mFavoriteStops = new ArrayList<>();
    private OnStopSelectedListener mListener;

    /**
     * Interface for handling stop selection
     */
    public interface OnStopSelectedListener {
        void onStopSelected(FavoriteStop stop);
    }

    /**
     * ViewHolder for stop items
     */
    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView mStopNameTextView;
        private final TextView mStopCodeTextView;

        public ViewHolder(View itemView) {
            super(itemView);
            mStopNameTextView = itemView.findViewById(R.id.stop_name);
            mStopCodeTextView = itemView.findViewById(R.id.stop_code);
        }

        public void bind(final FavoriteStop stop, final OnStopSelectedListener listener) {
            mStopNameTextView.setText(stop.getStopName());
            mStopCodeTextView.setText("Stop ID: " + stop.getStopId());

            // Set click listener
            itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        listener.onStopSelected(stop);
                    }
                }
            });
        }
    }

    public FavoriteStopsAdapter(OnStopSelectedListener listener) {
        mListener = listener;
    }

    /**
     * Update the adapter with a new list of stops
     */
    public void setFavoriteStops(List<FavoriteStop> stops) {
        mFavoriteStops = stops;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_favorite_stop, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(mFavoriteStops.get(position), mListener);
    }

    @Override
    public int getItemCount() {
        return mFavoriteStops.size();
    }
} 