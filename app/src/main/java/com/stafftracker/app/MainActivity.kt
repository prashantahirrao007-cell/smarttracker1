package com.stafftracker.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val fs by lazy { FirebaseFirestore.getInstance() }
    private var name = ""
    private val regs = mutableListOf<ListenerRegistration>()
    private var map: MapView? = null
    private var screen = ""
    private var csv = ""
    private var fitted = false
    private var autoTried = false
    private var started = false
    private var onDuty = false
    private var monthOff = 0
    private val PRI = Color.parseColor("#0F766E"); private val BG = Color.parseColor("#F1F5F9")
    private val TX = Color.parseColor("#0F172A"); private val MUT = Color.parseColor("#64748B"); private val RED = Color.parseColor("#DC2626")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Configuration.getInstance().load(this, getSharedPreferences("osm", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        if (auth.currentUser != null) profile() else login()
    }

    // ---------- UI helpers ----------
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun toast(s: String?) = Toast.makeText(this, s ?: "Error", Toast.LENGTH_LONG).show()
    private fun shape(c: Int, r: Int = 14, stroke: Int = 0) = GradientDrawable().apply {
        setColor(c); cornerRadius = dp(r).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }
    private fun lp(h: Int = -2, w: Float = 0f, top: Int = 0, bot: Int = 10) =
        LinearLayout.LayoutParams(-1, h, w).apply { topMargin = dp(top); bottomMargin = dp(bot) }
    private fun tv(t: String, sz: Float = 15f, bold: Boolean = false, c: Int = TX) = TextView(this).apply {
        text = t; textSize = sz; setTextColor(c); if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = shape(Color.WHITE, 16)
        setPadding(dp(16), dp(14), dp(16), dp(14)); elevation = dp(2).toFloat()
    }
    private fun btn(t: String, c: Int = PRI, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
        background = shape(c, 12); stateListAnimator = null; minimumHeight = dp(52); setOnClickListener { f() }
    }
    private fun input(h: String, type: Int) = EditText(this).apply {
        hint = h; inputType = type; setSingleLine(); setTextColor(TX)
        background = shape(Color.WHITE, 12, Color.parseColor("#CBD5E1")); setPadding(dp(14), dp(12), dp(14), dp(12))
    }
    private fun fmt(t: Long?, p: String = "hh:mm a") = if (t == null) "-" else SimpleDateFormat(p, Locale.getDefault()).format(Date(t))
    private fun newMap(z: Double) = MapView(this).also {
        map = it; it.setTileSource(TileSourceFactory.MAPNIK); it.setMultiTouchControls(true); it.controller.setZoom(z)
    }
    private fun root(): LinearLayout {
        regs.forEach { it.remove() }; regs.clear(); map?.onDetach(); map = null; fitted = false
        val l = LinearLayout(this); l.orientation = LinearLayout.VERTICAL; l.setBackgroundColor(BG)
        l.setPadding(dp(16), dp(36), dp(16), dp(12)); setContentView(l); return l
    }
    private fun header(l: LinearLayout, title: String, sub: String, right: String, f: () -> Unit) {
        val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL
        val c = LinearLayout(this); c.orientation = LinearLayout.VERTICAL
        c.addView(tv(title, 22f, true)); c.addView(tv(sub, 13f, false, MUT))
        r.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(btn(right, MUT, f).apply { minimumHeight = dp(40); textSize = 13f })
        l.addView(r, lp())
    }
    private fun pin(m: MapView, lat: Double, lng: Double, n: String, old: Boolean = false) {
        m.overlays.add(Marker(m).apply {
            position = GeoPoint(lat, lng); title = n
            setTextLabelBackgroundColor(Color.WHITE); setTextLabelForegroundColor(if (old) MUT else TX)
            setTextLabelFontSize(34); setTextIcon("📍 $n")
        })
    }
    private fun dot(m: MapView, lat: Double, lng: Double) {
        m.overlays.add(Marker(m).apply {
            position = GeoPoint(lat, lng); setInfoWindow(null)
            icon = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(RED); setSize(dp(10), dp(10)); setStroke(dp(1), Color.WHITE) }
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        })
    }
    private fun trail(m: MapView, d: DocumentSnapshot) {
        val pts = (d.get("path") as? List<*>)?.mapNotNull { (it as? String)?.split(",")?.let { s -> GeoPoint(s[0].toDouble(), s[1].toDouble()) } } ?: return
        if (pts.size > 1) m.overlays.add(Polyline().apply { setPoints(pts); outlinePaint.color = RED; outlinePaint.strokeWidth = 4f })
        pts.forEach { dot(m, it.latitude, it.longitude) }
    }

    // ---------- register / sign in ----------
    private fun login(registerMode: Boolean = false) {
        screen = "login"; val l = root(); l.gravity = Gravity.CENTER_VERTICAL
        val ban = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = shape(PRI, 20); setPadding(dp(20), dp(24), dp(20), dp(24)) }
        ban.addView(tv("📍 Staff Tracker", 26f, true, Color.WHITE))
        ban.addView(tv("Attendance & live location  •  10:00 AM – 6:00 PM", 13f, false, Color.parseColor("#CCFBF1")))
        l.addView(ban, lp(bot = 16))
        val c = card(); c.addView(tv(if (registerMode) "Create your account" else "Sign in", 20f, true))
        val n = input("Full name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val e = input("Email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val p = input("Password (min 6 characters)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        if (registerMode) c.addView(n, lp(top = 12)); c.addView(e, lp(top = 12)); c.addView(p, lp(top = 12))
        c.addView(btn(if (registerMode) "Register" else "Sign in") {
            val em = e.text.toString().trim(); val pw = p.text.toString()
            if (em.isEmpty() || pw.length < 6) { toast("Enter email and a password of 6+ characters"); return@btn }
            if (registerMode) {
                val nm = n.text.toString().trim()
                if (nm.isEmpty()) { toast("Enter your full name"); return@btn }
                auth.createUserWithEmailAndPassword(em, pw).addOnSuccessListener { r ->
                    fs.collection("users").document(r.user!!.uid).set(mapOf("name" to nm, "role" to "employee", "createdAt" to System.currentTimeMillis()))
                        .addOnSuccessListener { auth.signOut(); toast("Registered! Please sign in."); login(false) }
                        .addOnFailureListener { toast(it.message) }
                }.addOnFailureListener { toast(it.message) }
            } else auth.signInWithEmailAndPassword(em, pw).addOnSuccessListener { profile() }.addOnFailureListener { toast(it.message) }
        }, lp(top = 16))
        c.addView(tv(if (registerMode) "Already registered? Sign in" else "New employee? Register", 14f, true, PRI).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0); setOnClickListener { login(!registerMode) }
        })
        l.addView(c, lp())
    }

    private fun profile() {
        val u = auth.currentUser ?: return login()
        fs.collection("users").document(u.uid).get().addOnSuccessListener {
            name = it.getString("name") ?: u.email ?: "Staff"
            if (it.getString("role") == "admin") admin() else employee()
        }.addOnFailureListener { toast(it.message); login() }
    }
    private fun logout() { if (onDuty) toast("Please check out first") else { auth.signOut(); login() } }

    // ---------- employee ----------
    private fun employee() {
        screen = "emp"; started = false; autoTried = false; onDuty = false
        val l = root(); val uid = auth.currentUser!!.uid
        header(l, "Hello, $name", "Duty time 10:00 AM – 6:00 PM", "Logout") { logout() }
        val c = card(); val st = tv("", 18f, true); val sub = tv("", 13f, false, MUT)
        c.addView(st); c.addView(sub)
        val dg = tv("", 11f, false, MUT); c.addView(dg)
        dg.post(object : Runnable { override fun run() {
            if (screen == "emp") { dg.text = "Location permission: " + (if (hasLoc()) "OK" else "MISSING") + "\n" + Diag.text(); dg.postDelayed(this, 2000) }
        } })
        val b = btn("Check In") { if (onDuty) Duty.finish(this) else checkIn(uid) }
        c.addView(b, lp(top = 12, bot = 0)); l.addView(c, lp())
        val m = newMap(17.0); l.addView(m, lp(0, 1f))
        regs.add(fs.collection("live").document(uid).addSnapshotListener { d, _ ->
            if (d != null && d.exists() && (d.getLong("inAt") ?: 0L) < Duty.dayStart()) { Duty.finish(this); return@addSnapshotListener }
            onDuty = d != null && d.exists(); m.overlays.clear()
            b.text = if (onDuty) "Check Out" else "Check In"; b.background = shape(if (onDuty) RED else PRI, 12)
            if (d != null && d.exists()) {
                val lat = d.getDouble("lat"); val lng = d.getDouble("lng")
                st.text = "● On duty"; st.setTextColor(PRI)
                sub.text = "Since ${fmt(d.getLong("inAt"))}  •  ${"%.2f".format(d.getDouble("km") ?: 0.0)} km today" + (if (d.getBoolean("late") == true) "  •  Late" else "")
                trail(m, d)
                if (lat != null && lng != null) { pin(m, lat, lng, name + " • " + "%.2f".format(d.getDouble("km") ?: 0.0) + " km"); m.controller.setCenter(GeoPoint(lat, lng)) }
                else sub.text = sub.text.toString() + "\nGetting GPS location…"
                if (!started) { started = true; startSvc() }
            } else {
                started = false; st.text = "Off duty"; st.setTextColor(MUT)
                sub.text = if (Duty.inShift()) "Tap Check In to share your location." else "Check-in is open from 10:00 AM to 6:00 PM."
                if (d != null && !d.metadata.isFromCache && !autoTried) {
                    autoTried = true   // first sign-in of the day inside shift hours = automatic check-in
                    if (Duty.inShift()) fs.collection("logs").document(uid + "_" + Duty.key()).get().addOnSuccessListener { if (!it.exists()) checkIn(uid) }
                }
            }
            m.invalidate()
        })
    }

    private fun hasLoc() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startSvc() {
        if (!hasLoc()) return
        val i = Intent(this, TrackService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun checkIn(uid: String) {
        if (!Duty.inShift()) { toast("Duty time is 10:00 AM to 6:00 PM"); return }
        if (!hasLoc()) {
            val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(need.toTypedArray(), 1); return
        }
        val now = System.currentTimeMillis()
        val m = hashMapOf<String, Any>("name" to name, "inAt" to now, "t" to now, "km" to 0.0,
            "late" to (now > Duty.at(Duty.START_H) + Duty.LATE_MIN * 60000L))
        try {   // show the last known position instantly so admin sees the employee right away
            val lm = getSystemService(LocationManager::class.java)
            listOf("gps", "network").mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let {
                m["lat"] = it.latitude; m["lng"] = it.longitude; m["inLat"] = it.latitude; m["inLng"] = it.longitude
            }
        } catch (e: SecurityException) {}
        fs.collection("live").document(uid).set(m).addOnFailureListener { toast(it.message) }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (hasLoc()) auth.currentUser?.let { checkIn(it.uid) } else toast("Location permission is required to check in")
    }

    // ---------- admin dashboard ----------
    private fun admin() {
        screen = "admin"; val l = root()
        header(l, "Admin Dashboard", fmt(System.currentTimeMillis(), "EEE, dd MMM yyyy"), "Logout") { auth.signOut(); login() }
        val row = LinearLayout(this); val stats = Array(4) { tv("-", 22f, true, PRI) }
        val labels = arrayOf("Registered", "Present today", "Active now", "KM today")
        for (i in 0..3) {
            val c = card(); c.setPadding(dp(6), dp(10), dp(6), dp(10)); c.gravity = Gravity.CENTER
            c.addView(stats[i]); c.addView(tv(labels[i], 11f, false, MUT))
            row.addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        l.addView(row, lp())
        val m = newMap(5.0); m.controller.setCenter(GeoPoint(20.5937, 78.9629)); l.addView(m, lp(0, 2f))
        val list = tv("", 14f); val lc = card(); lc.addView(list)
        val sc = ScrollView(this); sc.addView(lc); l.addView(sc, lp(0, 1f, top = 10))
        l.addView(btn("Monthly report") { monthOff = 0; report() }, lp(top = 4, bot = 0))
        var live = listOf<DocumentSnapshot>(); var done = listOf<DocumentSnapshot>(); var reg = 0
        fun render() {
            m.overlays.clear(); val pts = mutableListOf<GeoPoint>(); val sb = StringBuilder()
            var km = 0.0; val ids = mutableSetOf<String>(); val now = System.currentTimeMillis()
            for (d in live) {
                ids.add(d.id); val k = d.getDouble("km") ?: 0.0; km += k
                val n = d.getString("name") ?: "?"; val lat = d.getDouble("lat"); val lng = d.getDouble("lng")
                val stale = now - (d.getLong("t") ?: 0L) > 180000
                sb.append("● $n\n    In ${fmt(d.getLong("inAt"))} • ${"%.1f".format(k)} km • ${if (stale) "no signal" else "live"}${if (d.getBoolean("late") == true) " • late" else ""}\n")
                trail(m, d)
                if (lat != null && lng != null) { pin(m, lat, lng, n + " • " + "%.1f".format(k) + " km", stale); pts.add(GeoPoint(lat, lng)) }
            }
            for (d in done) {
                ids.add(d.getString("uid") ?: d.id); val k = d.getDouble("km") ?: 0.0; km += k
                sb.append("○ ${d.getString("name")}\n    ${fmt(d.getLong("inAt"))} – ${fmt(d.getLong("outAt"))} • ${"%.1f".format(k)} km\n")
            }
            stats[0].text = "$reg"; stats[1].text = "${ids.size}"; stats[2].text = "${live.size}"; stats[3].text = "%.1f".format(km)
            list.text = if (sb.isEmpty()) "No attendance yet today." else sb.toString().trimEnd()
            if (!fitted && pts.isNotEmpty()) {
                fitted = true
                m.post { if (pts.size == 1) { m.controller.setZoom(17.0); m.controller.setCenter(pts[0]) } else m.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts), true, 120) }
            }
            m.invalidate()
        }
        regs.add(fs.collection("live").addSnapshotListener { s, e -> if (e != null) toast("Admin data error: " + e.message); if (s != null) { live = s.documents.filter { (it.getLong("inAt") ?: 0L) >= Duty.dayStart() }; render() } })
        regs.add(fs.collection("logs").whereGreaterThanOrEqualTo("inAt", Duty.dayStart()).addSnapshotListener { s, _ -> if (s != null) { done = s.documents; render() } })
        fs.collection("users").get().addOnSuccessListener { s -> reg = s.documents.count { it.getString("role") != "admin" }; render() }
    }

    // ---------- monthly report ----------
    private class A(var d: Int = 0, var h: Double = 0.0, var km: Double = 0.0, var late: Int = 0)

    private fun report() {
        screen = "report"; val l = root()
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.MONTH, monthOff)
        }
        val s = cal.timeInMillis; cal.add(Calendar.MONTH, 1); val end = cal.timeInMillis
        header(l, "Monthly report", fmt(s, "MMMM yyyy"), "Back") { admin() }
        val nav = LinearLayout(this)
        nav.addView(btn("◀ Previous") { monthOff--; report() }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        nav.addView(btn("Next ▶") { monthOff++; report() }, LinearLayout.LayoutParams(0, -2, 1f))
        l.addView(nav, lp())
        val out = tv("Loading…", 13f); out.typeface = Typeface.MONOSPACE
        val c = card(); c.addView(out); val sc = ScrollView(this); sc.addView(c); l.addView(sc, lp(0, 1f))
        l.addView(btn("Save CSV file") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/csv").putExtra(Intent.EXTRA_TITLE, "attendance-" + fmt(s, "yyyy-MM") + ".csv"), 2)
        }, lp(top = 6, bot = 0))
        fs.collection("logs").whereGreaterThanOrEqualTo("inAt", s).whereLessThan("inAt", end).get().addOnSuccessListener { q ->
            val agg = sortedMapOf<String, A>(); val sb = StringBuilder("Name,Date,In,Out,Hours,KM,Late\n")
            for (d in q.documents.sortedBy { it.getLong("inAt") ?: 0L }) {
                val n = d.getString("name") ?: "?"; val i = d.getLong("inAt") ?: 0L; val o = d.getLong("outAt") ?: i
                val h = (o - i) / 3600000.0; val km = d.getDouble("km") ?: 0.0; val lt = d.getBoolean("late") == true
                val a = agg.getOrPut(n) { A() }; a.d++; a.h += h; a.km += km; if (lt) a.late++
                sb.append("${n.replace(",", " ")},${fmt(i, "dd/MM/yyyy")},${fmt(i)},${fmt(o)},${"%.2f".format(h)},${"%.2f".format(km)},${if (lt) "Yes" else "No"}\n")
            }
            csv = sb.toString()
            val tx = StringBuilder(String.format("%-14s %4s %6s %7s %4s\n", "Name", "Days", "Hours", "KM", "Late"))
            for ((n, a) in agg) tx.append(String.format("%-14s %4d %6.1f %7.1f %4d\n", n.take(14), a.d, a.h, a.km, a.late))
            out.text = if (agg.isEmpty()) "No records for this month." else tx.toString()
        }.addOnFailureListener { out.text = it.message }
    }

    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        if (rc == 2 && res == RESULT_OK) data?.data?.let { u ->
            contentResolver.openOutputStream(u)?.use { it.write(("\uFEFF" + csv).toByteArray()) }; toast("Saved")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (screen == "report") admin() else super.onBackPressed() }
    override fun onResume() { super.onResume(); map?.onResume() }
    override fun onPause() { super.onPause(); map?.onPause() }
    override fun onDestroy() { regs.forEach { it.remove() }; super.onDestroy() }
}
