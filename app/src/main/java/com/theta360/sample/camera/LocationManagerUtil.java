package com.theta360.sample.camera;

import static android.content.Context.LOCATION_SERVICE;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Bundle;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class LocationManagerUtil {

    private final long MIN_INTERVAL = 1 * 1000L;    // [ms]
    private final Float MIN_DISTANCE = 1.0F;        // [meter]
    private final String TAG = "Camera_API_Sample_GNSS";

    private String mStatus = null;
    private Double mLat = 0.0;
    private Double mLng = 0.0;
    private Double mAlt = 0.0;
    private long mGpsTime = Long.MIN_VALUE;
    private long mSysTime = Long.MAX_VALUE;
    private boolean isStarted = false;

    private LocationManager locationManager;
    private LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
        }
        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }
        @Override
        public void onProviderEnabled(String provider) {
        }
        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    LocationManagerUtil(Context context){
        locationManager = (LocationManager)context.getSystemService(LOCATION_SERVICE);
    }

    @SuppressLint("MissingPermission")
    void start() {
        if (!isStarted) {
            isStarted = true;
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_INTERVAL, MIN_DISTANCE, locationListener);
            //locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, MIN_INTERVAL, MIN_DISTANCE, locationListener);
            locationManager.addNmeaListener(mOnNmeaMessageListener);
        }
    }

    void stop() {
        if (isStarted) {
            isStarted = false;
            locationManager.removeUpdates(locationListener);
            locationManager.removeNmeaListener(mOnNmeaMessageListener);
        }
    }

    Boolean check() {
        //In this sample, location data is valid within the last 15sec.
        return (System.currentTimeMillis() - mSysTime) < 15000 &&
                mLat != 0.0 && mLng != 0.0 && mStatus.equals("A");
    }

    Double getLat() {
        return mLat;
    }

    Double getLng() {
        return mLng;
    }

    Double getAlt() {
        return mAlt;
    }

    Long getGpsTime() {
        return mGpsTime / 1000;
    }

    OnNmeaMessageListener mOnNmeaMessageListener = (message, timestamp) -> {
        //Log.d(TAG, message); //output log for all NMEA message
        processNmeaData(message);
    };

    private void processNmeaData(String nmea) {
        String[] rawNmeaSplit = nmea.split(",");
        if (rawNmeaSplit == null) {
            return;
        }
        //GNGGA
        if (rawNmeaSplit[0].equalsIgnoreCase("$GNGGA")) {
            //Lat
            if (!rawNmeaSplit[2].isEmpty()) {
                double Lat = Double.parseDouble(rawNmeaSplit[2]) / 100.0;
                double Deg = Math.floor(Lat);
                mLat = (Deg + (Lat - Deg) * 100.0 / 60.0) * (rawNmeaSplit[3].equals("N")? 1 : -1);
            }
            //Lng
            if (!rawNmeaSplit[4].isEmpty()) {
                double Lng = Double.parseDouble(rawNmeaSplit[4]) / 100.0;
                double Deg = Math.floor(Lng);
                mLng = (Deg + (Lng - Deg) * 100.0 / 60.0) * (rawNmeaSplit[5].equals("E")? 1 : -1);
            }
            //Alt
            if (!rawNmeaSplit[9].isEmpty()) {
                mAlt = Double.parseDouble(rawNmeaSplit[9]);
            }
            //Log.d(TAG, "mLat = " + mLat + " mLng = " + mLng + " mAlt = " + mAlt);
        }
        //GNRMC
        if (rawNmeaSplit[0].equalsIgnoreCase("$GNRMC")) {
            //status
            if (!rawNmeaSplit[2].isEmpty()) {
                mStatus = rawNmeaSplit[2];
            }
            //Time
            if (rawNmeaSplit[1].length() >= 6 && rawNmeaSplit[9].length() == 6) {
                int yyyy = Integer.parseInt(rawNmeaSplit[9].substring(4, 6)) + 2000;
                int MM = Integer.parseInt(rawNmeaSplit[9].substring(2, 4));
                int dd = Integer.parseInt(rawNmeaSplit[9].substring(0, 2));
                int HH = Integer.parseInt(rawNmeaSplit[1].substring(0, 2));
                int mm = Integer.parseInt(rawNmeaSplit[1].substring(2, 4));
                int ss = Integer.parseInt(rawNmeaSplit[1].substring(4, 6));
                LocalDateTime dateTime = LocalDateTime.of(yyyy,MM,dd,HH,mm,ss);
                mGpsTime = ZonedDateTime.of(dateTime, ZoneId.of("UTC")).toEpochSecond() * 1000;
                mSysTime = System.currentTimeMillis();
            }
            //Log.d(TAG, "mStatus = " +  mStatus + " mGpsTime = " + mGpsTime + " mSysTime = " + mSysTime);
        }
    }
}