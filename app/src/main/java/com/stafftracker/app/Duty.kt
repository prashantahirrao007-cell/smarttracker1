package com.stafftracker.app

import android.content.Context
import android.content.Intent
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*

/** Shift rules + check-out logic (used by the Check Out button and the 6 PM auto check-out). */
object Duty {
    const val START_H = 10   // 10:00 AM
    const val END_H = 18     // 06:00 PM
    const val LATE_MIN = 15  // after 10:15 = late

    fun dayStart(t: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    fun at(h: Int, t: Long = System.currentTimeMillis()) = dayStart(t) + h * 3600000L
    fun inShift(t: Long = System.currentTimeMillis()) = t >= at(START_H, t) && t < at(END_H, t)
    fun key(t: Long = System.currentTimeMillis()): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(t))

    /** Saves the day's record to logs/{uid}_{yyyyMMdd}, deletes the live doc, stops tracking. */
    fun finish(ctx: Context, done: () -> Unit = {}) {
        val u = FirebaseAuth.getInstance().currentUser ?: return
        val fs = FirebaseFirestore.getInstance()
        val live = fs.collection("live").document(u.uid)
        ctx.stopService(Intent(ctx, TrackService::class.java))
        live.get().addOnSuccessListener { d ->
            if (!d.exists()) { done(); return@addOnSuccessListener }
            val inAt = d.getLong("inAt") ?: System.currentTimeMillis()
            val out = if (inAt < dayStart()) minOf(d.getLong("t") ?: inAt, at(END_H, inAt)) else System.currentTimeMillis()
            val ref = fs.collection("logs").document(u.uid + "_" + key(inAt))
            ref.get().addOnSuccessListener { old ->
                ref.set(hashMapOf<String, Any?>(
                    "uid" to u.uid, "name" to d.getString("name"),
                    "inAt" to minOf(inAt, old.getLong("inAt") ?: Long.MAX_VALUE),
                    "outAt" to out,
                    "km" to (d.getDouble("km") ?: 0.0) + (old.getDouble("km") ?: 0.0),
                    "late" to (old.getBoolean("late") ?: d.getBoolean("late") ?: false),
                    "inLat" to (old.get("inLat") ?: d.get("inLat")), "inLng" to (old.get("inLng") ?: d.get("inLng")),
                    "outLat" to d.get("lat"), "outLng" to d.get("lng")))
                live.delete(); done()
            }
        }
    }
}

/** Simple on-screen diagnostics shared between the tracking service and the employee screen. */
object Diag {
    @Volatile var msg = "Tracking not started yet"
    @Volatile var up = "No upload yet"
    @Volatile var fixes = 0
    fun text() = msg + "\n" + up
}
