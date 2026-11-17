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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

/** Runs between Check In and Check Out (auto-stops at 6 PM). Uses Google Fused Location (GPS + Wi-Fi + mobile network). */
class TrackService : Service(), LocationListener {
    private var lm: LocationManager? = null
    private var fused: FusedLocationProviderClient? = null
    private var first = true
    private var last: Location? = null
    private var lastT = 0L
    private val h = Handler(Looper.getMainLooper())
    private val cb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) { r.lastLocation?.let { handle(it) } }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val ch = "track"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(ch, "Location sharing", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(this, ch).setContentTitle("On duty")
            .setContentText("Sharing your location until 6:00 PM")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(1, n)
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ Duty.finish(applicationContext); stopSelf() }, maxOf(Duty.at(Duty.END_H) - System.currentTimeMillis(), 1000L))
        lm = getSystemService(LocationManager::class.java)
        try {
            fused?.removeLocationUpdates(cb)
            fused = LocationServices.getFusedLocationProviderClient(this)
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 20000L)
                .setMinUpdateIntervalMillis(10000L).setMinUpdateDistanceMeters(0f).build()
            fused!!.requestLocationUpdates(req, cb, Looper.getMainLooper()).addOnFailureListener { startLm() }
        } catch (e: Exception) { startLm() }
        Diag.msg = "Service running (fused). Waiting for first fix..."
        return START_STICKY
    }

    /** Fallback if Google Play services are not available on the phone. */
    private fun startLm() {
        try {
            lm?.removeUpdates(this)
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER))
                if (lm!!.isProviderEnabled(p)) lm!!.requestLocationUpdates(p, 20000L, 0f, this)
            Diag.msg = "Service running (basic GPS). Waiting for first fix..."
        } catch (e: SecurityException) { stopSelf() }
    }

    override fun onLocationChanged(l: Location) = handle(l)

    private fun handle(l: Location) {
        val u = FirebaseAuth.getInstance().currentUser ?: return
        val now = System.currentTimeMillis()
        if (now - lastT < 5000) return
        lastT = now
        Diag.fixes++
        Diag.msg = "Fix #" + Diag.fixes + " (" + l.provider + ") +-" + l.accuracy.toInt() + " m at " +
            java.text.SimpleDateFormat("hh:mm:ss a", Locale.US).format(java.util.Date())
        val m = hashMapOf<String, Any>("lat" to l.latitude, "lng" to l.longitude, "acc" to l.accuracy, "t" to now)
        fun pt() = String.format(Locale.US, "%.5f,%.5f", l.latitude, l.longitude)
        if (first) { m["inLat"] = l.latitude; m["inLng"] = l.longitude; first = false }
        val p = last
        if (p == null) {
            if (l.accuracy < 50) { last = l; m["path"] = FieldValue.arrayUnion(pt()) }   // wait for a good fix before measuring distance
        } else if (l.accuracy < 100) {
            val d = l.distanceTo(p)
            if (d >= maxOf(20f, l.accuracy)) {   // moved more than the GPS error radius = real movement
                if (d < 2000) m["km"] = FieldValue.increment(d / 1000.0)
                m["path"] = FieldValue.arrayUnion(pt()); last = l
            }
        }
        // update() fails if the live doc is gone (after check-out), so tracking never resurrects
        FirebaseFirestore.getInstance().collection("live").document(u.uid).update(m)
            .addOnSuccessListener { Diag.up = "Upload OK" }
            .addOnFailureListener { Diag.up = "Upload FAILED: " + it.message }
    }

    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
    override fun onDestroy() {
        h.removeCallbacksAndMessages(null); fused?.removeLocationUpdates(cb); lm?.removeUpdates(this); super.onDestroy()
    }
}
