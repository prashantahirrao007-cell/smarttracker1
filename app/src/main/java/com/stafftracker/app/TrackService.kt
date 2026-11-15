package com.stafftracker.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/** Runs only between Check In and Check Out. Sends GPS position to Firestore. */
class TrackService : Service(), LocationListener {
    private var lm: LocationManager? = null
    private var first = true

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val ch = "track"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(ch, "Location sharing", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(this, ch)
            .setContentTitle("Checked in")
            .setContentText("Sharing your location with admin")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(1, n)
        lm = getSystemService(LocationManager::class.java)
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER))
                if (lm!!.isProviderEnabled(p)) lm!!.requestLocationUpdates(p, 15000L, 10f, this)
        } catch (e: SecurityException) { stopSelf() }
        return START_STICKY
    }

    override fun onLocationChanged(l: Location) {
        val u = FirebaseAuth.getInstance().currentUser ?: return
        val m = hashMapOf<String, Any>("lat" to l.latitude, "lng" to l.longitude, "acc" to l.accuracy, "t" to System.currentTimeMillis())
        if (first) { m["inLat"] = l.latitude; m["inLng"] = l.longitude; first = false }
        // update() fails if the live doc is gone (after check-out), so tracking never resurrects
        FirebaseFirestore.getInstance().collection("live").document(u.uid).update(m)
    }

    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
    override fun onDestroy() { lm?.removeUpdates(this); super.onDestroy() }
}
