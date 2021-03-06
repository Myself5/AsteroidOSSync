/*
 * Copyright (C) 2016 - Florent Revest <revestflo@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.asteroidos.sync.ble;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import com.idevicesinc.sweetblue.BleDevice;

import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.UUID;

import github.vatsal.easyweather.Helper.ForecastCallback;
import github.vatsal.easyweather.WeatherMap;
import github.vatsal.easyweather.retrofit.models.ForecastResponseModel;
import github.vatsal.easyweather.retrofit.models.List;

@SuppressWarnings( "deprecation" ) // Before upgrading to SweetBlue 3.0, we don't have an alternative to the deprecated ReadWriteListener
public class WeatherService implements BleDevice.ReadWriteListener {
    private static final UUID weatherCityCharac     = UUID.fromString("00008001-0000-0000-0000-00a57e401d05");
    private static final UUID weatherIdsCharac      = UUID.fromString("00008002-0000-0000-0000-00a57e401d05");
    private static final UUID weatherMinTempsCharac = UUID.fromString("00008003-0000-0000-0000-00a57e401d05");
    private static final UUID weatherMaxTempsCharac = UUID.fromString("00008004-0000-0000-0000-00a57e401d05");

    private static final String owmApiKey = "ffcb5a7ed134aac3d095fa628bc46c65";

    public static final String PREFS_NAME = "WeatherPreferences";
    public static final String PREFS_LATITUDE = "latitude";
    public static final float PREFS_LATITUDE_DEFAULT = (float) 40.7128;
    public static final String PREFS_LONGITUDE = "longitude";
    public static final float PREFS_LONGITUDE_DEFAULT = (float) -74.006;
    public static final String PREFS_ZOOM = "zoom";
    public static final float PREFS_ZOOM_DEFAULT = (float) 7.0;
    public static final String WEATHER_SYNC_INTENT = "org.asteroidos.sync.WEATHER_SYNC_REQUEST_LISTENER";

    private BleDevice mDevice;
    private Context mCtx;
    private SharedPreferences mSettings;

    private WeatherSyncReqReceiver mSReceiver;
    private PendingIntent alarmPendingIntent;
    private AlarmManager alarmMgr;

    public WeatherService(Context ctx, BleDevice device) {
        mDevice = device;
        mCtx = ctx;
    }

    public void sync() {
        updateWeather();

        // Register a broadcast handler to use for the alarm Intent
        mSReceiver = new WeatherSyncReqReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(WEATHER_SYNC_INTENT);
        mCtx.registerReceiver(mSReceiver, filter);

        // Fire update intent every 30 Minutes to update Weather
        Intent alarmIntent = new Intent(WEATHER_SYNC_INTENT);
        alarmPendingIntent = PendingIntent.getBroadcast(mCtx, 0, alarmIntent, 0);
        alarmMgr = (AlarmManager) mCtx.getSystemService(Context.ALARM_SERVICE);
        alarmMgr.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_HALF_HOUR,
                AlarmManager.INTERVAL_HALF_HOUR, alarmPendingIntent);
    }

    public void unsync() {
        try {
            mCtx.unregisterReceiver(mSReceiver);
        } catch (IllegalArgumentException ignored) {}

        if (alarmMgr!= null) {
            alarmMgr.cancel(alarmPendingIntent);
        }
    }

    private void updateWeather() {
        float latitude = mSettings.getFloat(PREFS_LATITUDE, PREFS_LATITUDE_DEFAULT);
        float longitude = mSettings.getFloat(PREFS_LONGITUDE, PREFS_LONGITUDE_DEFAULT);
        WeatherMap weatherMap = new WeatherMap(mCtx, owmApiKey);
        weatherMap.getLocationForecast(String.valueOf(latitude), String.valueOf(longitude), new ForecastCallback() {
            @Override
            public void success(ForecastResponseModel response) {
                List[] l = response.getList();
                String cityName = response.getCity().getName();
                byte[] city = {};
                if(cityName != null)
                    city = cityName.getBytes(StandardCharsets.UTF_8);
                final byte[] ids = new byte[10];
                final byte[] maxTemps = new byte[10];
                final byte[] minTemps = new byte[10];

                int currentDay, i=0;

                try {
                    for (int j = 0; j < 5; j++) { // For each day of forecast
                        currentDay = dayOfTimestamp(Long.parseLong(l[i].getDt()));
                        short min = Short.MAX_VALUE;
                        short max = Short.MIN_VALUE;
                        int id = 0;
                        while (i < l.length && dayOfTimestamp(Long.parseLong(l[i].getDt())) == currentDay) { // For each data point of the day
                            // TODO is there a better way to select the most significant ID than the first of the afternoon ?
                            if (hourOfTimestamp(Long.parseLong(l[i].getDt())) >= 12 && id == 0)
                                id = Short.parseShort(l[i].getWeather()[0].getId());

                            short currentTemp = (short) Math.round(Float.parseFloat(l[i].getMain().getTemp()));
                            if (currentTemp > max) max = currentTemp;
                            if (currentTemp < min) min = currentTemp;

                            currentDay = dayOfTimestamp(Long.parseLong(l[i].getDt()));
                            i = i + 1;
                        }

                        ids[2 * j] = (byte) (id >> 8);
                        ids[2 * j + 1] = (byte) id;
                        maxTemps[2 * j] = (byte) (max >> 8);
                        maxTemps[2 * j + 1] = (byte) max;
                        minTemps[2 * j] = (byte) (min >> 8);
                        minTemps[2 * j + 1] = (byte) min;
                    }
                } catch(java.lang.ArrayIndexOutOfBoundsException ignored) {}

                mDevice.write(weatherCityCharac, city, WeatherService.this);
                mDevice.write(weatherIdsCharac, ids, WeatherService.this);
                mDevice.write(weatherMaxTempsCharac, maxTemps, WeatherService.this);
                mDevice.write(weatherMinTempsCharac, minTemps, WeatherService.this);
            }

            @Override public void failure(String message) {
                Log.e("WeatherService", "Could not get weather from owm");
            }
        });
    }

    private int dayOfTimestamp(long timestamp) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestamp*1000);
        return cal.get(Calendar.DAY_OF_WEEK);
    }

    private int hourOfTimestamp(long timestamp) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestamp*1000);
        return cal.get(Calendar.HOUR_OF_DAY);
    }

    @Override
    public void onEvent(ReadWriteEvent e) {
        if(!e.wasSuccess())
            Log.e("WeatherService", e.status().toString());
    }


    class WeatherSyncReqReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateWeather();
        }
    }
}
