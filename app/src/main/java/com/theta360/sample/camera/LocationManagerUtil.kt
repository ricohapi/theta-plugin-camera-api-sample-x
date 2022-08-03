package com.theta360.sample.camera

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Bundle
import android.support.annotation.RequiresApi
import android.util.Log
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

private const val MIN_INTERVAL = 1 * 1000L  // [ms]
private const val MIN_DISTANCE = 1.0F       // [meter]
private const val TAG: String = "Camera_API_Sample_GNSS"

class LocationManagerUtil(context:Context) {

    private var mStatus: String ?= null
    private var mLat: Double = 0.0
    private var mLng: Double = 0.0
    private var mAlt: Double = 0.0
    private var mGpsTime: Long = Long.MIN_VALUE
    private var mSysTime: Long = Long.MAX_VALUE
    private var isStarted = false

    private val locationManager: LocationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val locationListener: LocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location?) {
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }
        override fun onProviderEnabled(provider: String?) {
        }
        override fun onProviderDisabled(provider: String?) {
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!isStarted) {
            isStarted = true
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_INTERVAL, MIN_DISTANCE, locationListener)
            //locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, MIN_INTERVAL, MIN_DISTANCE, locationListener)
            locationManager.addNmeaListener(mOnNmeaMessageListener)
        }
    }

    fun stop() {
        if (isStarted) {
            isStarted = false
            locationManager.removeUpdates(locationListener)
            locationManager.removeNmeaListener(mOnNmeaMessageListener)
        }
    }

    fun check(): Boolean {
        //In this sample, location data is valid within the last 15sec.
        return if ((System.currentTimeMillis() - mSysTime) < 15_000 &&
            mLat != 0.0 && mLng != 0.0 && mStatus.equals("A")) true else false
    }

    fun getLat(): Double {
        return mLat
    }

    fun getLng(): Double {
        return mLng
    }

    fun getAlt(): Double {
        return mAlt
    }

    fun getGpsTime(): Long {
        return mGpsTime / 1_000
    }

    var mOnNmeaMessageListener = OnNmeaMessageListener { message, timestamp ->
        //Log.d(TAG, message) //output log for all NMEA message
        processNmeaData(message)
    }

    private fun processNmeaData(nmea: String) {
        val rawNmeaSplit: List<String> = nmea.split(",")
        if (rawNmeaSplit.isEmpty()) {
            return
        }
        //GNGGA
        if (rawNmeaSplit[0].equals("${'$'}GNGGA", true)) {
            //Lat
            if (!rawNmeaSplit[2].isEmpty()) {
                val Lat = rawNmeaSplit[2].toDouble() / 100.0
                val Deg = Math.floor(Lat)
                mLat = (Deg + (Lat - Deg) * 100.0 / 60.0) * if (rawNmeaSplit[3].equals("N")) 1 else -1
            }
            //Lng
            if (!rawNmeaSplit[4].isEmpty()) {
                val Lng = rawNmeaSplit[4].toDouble() / 100.0
                val Deg = Math.floor(Lng)
                mLng = (Deg + (Lng - Deg) * 100.0 / 60.0) * if (rawNmeaSplit[5].equals("E")) 1 else -1
            }
            //Alt
            if (!rawNmeaSplit[9].isEmpty()) {
                mAlt = rawNmeaSplit[9].toDouble()
            }
            //Log.d(TAG, "mLat = ${mLat} mLng = ${mLng} mAlt = ${mAlt}")
        }
        //GNRMC
        if (rawNmeaSplit[0].equals("${'$'}GNRMC", true)) {
            //status
            if (!rawNmeaSplit[2].isEmpty()) {
                mStatus = rawNmeaSplit[2]
            }
            //Time
            if (rawNmeaSplit[1].length >= 6 && rawNmeaSplit[9].length == 6) {
                val yyyy = rawNmeaSplit[9].substring(4, 6).toInt() + 2000
                val MM = rawNmeaSplit[9].substring(2, 4).toInt()
                val dd = rawNmeaSplit[9].substring(0, 2).toInt()
                val HH = rawNmeaSplit[1].substring(0, 2).toInt()
                val mm = rawNmeaSplit[1].substring(2, 4).toInt()
                val ss = rawNmeaSplit[1].substring(4, 6).toInt()
                val dateTime = LocalDateTime.of(yyyy,MM,dd,HH,mm,ss)
                mGpsTime = ZonedDateTime.of(dateTime, ZoneId.of("UTC")).toEpochSecond() * 1_000
                mSysTime = System.currentTimeMillis()
            }
            //Log.d(TAG, "mStatus = ${mStatus} mGpsTime = ${mGpsTime} mSysTime = ${mSysTime}")
        }
    }
}
