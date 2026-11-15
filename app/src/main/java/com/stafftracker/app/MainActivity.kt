package com.stafftracker.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val fs by lazy { FirebaseFirestore.getInstance() }
    private var name = ""
    private var reg: ListenerRegistration? = null
    private var map: MapView? = null
    private var screen = ""
    private var logs: List<Map<String, Any?>> = listOf()
    private var csv = ""
    private var started = false
    private var fitted = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Configuration.getInstance().load(this, getSharedPreferences("osm", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        if (auth.currentUser != null) profile() else login()
    }

    // ---------- helpers ----------
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun toast(s: String?) = Toast.makeText(this, s ?: "Error", Toast.LENGTH_LONG).show()
    private fun tv(t: String, sz: Float = 16f, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sz; setTextColor(Color.BLACK); if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun btn(t: String, c: () -> Unit) = Button(this).apply { text = t; setOnClickListener { c() } }
    private fun field(h: String, type: Int) = EditText(this).apply { hint = h; inputType = type; setSingleLine() }
    private fun fmt(t: Long?) = if (t == null) "-" else SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(t))
    private fun newMap(z: Double) = MapView(this).also {
        map = it; it.setTileSource(TileSourceFactory.MAPNIK); it.setMultiTouchControls(true); it.controller.setZoom(z)
    }
    private fun root(): LinearLayout {
        reg?.remove(); reg = null; map?.onDetach(); map = null; fitted = false
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(16), dp(40), dp(16), dp(16)); l.setBackgroundColor(Color.WHITE)
        setContentView(l); return l
    }
    private fun pin(m: MapView, lat: Double, lng: Double, n: String) {
        m.overlays.add(Marker(m).apply {
            position = GeoPoint(lat, lng); title = n
            setTextLabelBackgroundColor(Color.WHITE); setTextLabelForegroundColor(Color.BLACK)
            setTextLabelFontSize(34); setTextIcon("📍 $n")
        })
    }

    // ---------- login ----------
    private fun login() {
        screen = "login"; val l = root()
        l.addView(tv("Staff Tracker", 26f, true))
        val n = field("Your name (needed only for Register)", InputType.TYPE_CLASS_TEXT)
        val e = field("Email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val p = field("Password (min 6 characters)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        l.addView(n); l.addView(e); l.addView(p)
        l.addView(btn("Login") {
            auth.signInWithEmailAndPassword(e.text.toString().trim(), p.text.toString())
                .addOnSuccessListener { profile() }.addOnFailureListener { toast(it.message) }
        })
        l.addView(btn("Register as employee") {
            val nm = n.text.toString().trim()
            if (nm.isEmpty()) { toast("Enter your name"); return@btn }
            auth.createUserWithEmailAndPassword(e.text.toString().trim(), p.text.toString())
                .addOnSuccessListener { r ->
                    fs.collection("users").document(r.user!!.uid).set(mapOf("name" to nm, "role" to "employee"))
                        .addOnSuccessListener { profile() }.addOnFailureListener { toast(it.message) }
                }.addOnFailureListener { toast(it.message) }
        })
    }

    private fun profile() {
        val u = auth.currentUser ?: return login()
        fs.collection("users").document(u.uid).get().addOnSuccessListener {
            name = it.getString("name") ?: u.email ?: "Staff"
            if (it.getString("role") == "admin") admin() else employee()
        }.addOnFailureListener { toast(it.message); login() }
    }

    // ---------- employee ----------
    private fun employee() {
        screen = "emp"; started = false; val l = root(); val uid = auth.currentUser!!.uid
        l.addView(tv("Hello, $name", 22f, true))
        val st = tv("Checking status…"); l.addView(st)
        val m = newMap(17.0)
        l.addView(m, LinearLayout.LayoutParams(-1, 0, 1f))
        var inn = false
        val b = Button(this); l.addView(b)
        l.addView(btn("Logout") { if (inn) toast("Please check out first") else { auth.signOut(); login() } })
        b.setOnClickListener { if (inn) checkOut(uid) else checkIn(uid) }
        reg = fs.collection("live").document(uid).addSnapshotListener { d, _ ->
            inn = d != null && d.exists()
            b.text = if (inn) "CHECK OUT" else "CHECK IN"
            m.overlays.clear()
            if (inn) {
                val lat = d!!.getDouble("lat"); val lng = d.getDouble("lng")
                st.text = "Checked in at " + fmt(d.getLong("inAt")) + ". Location is being shared."
                if (lat != null && lng != null) { pin(m, lat, lng, name); m.controller.setCenter(GeoPoint(lat, lng)) }
                else st.text = st.text.toString() + " Waiting for GPS…"
                if (!started) { started = true; startSvc() }
            } else st.text = "You are checked out. Tracking is off."
            m.invalidate()
        }
    }

    private fun hasLoc() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startSvc() {
        if (!hasLoc()) return
        val i = Intent(this, TrackService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun checkIn(uid: String) {
        if (!hasLoc()) {
            val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(need.toTypedArray(), 1); return
        }
        val now = System.currentTimeMillis()
        fs.collection("live").document(uid).set(mapOf("name" to name, "inAt" to now, "t" to now))
            .addOnFailureListener { toast(it.message) }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (hasLoc()) auth.currentUser?.let { checkIn(it.uid) } else toast("Location permission is required to check in")
    }

    private fun checkOut(uid: String) {
        val ref = fs.collection("live").document(uid)
        ref.get().addOnSuccessListener { d ->
            if (d.exists()) {
                fs.collection("logs").add(mapOf(
                    "uid" to uid, "name" to name, "inAt" to d.get("inAt"), "outAt" to System.currentTimeMillis(),
                    "inLat" to d.get("inLat"), "inLng" to d.get("inLng"), "outLat" to d.get("lat"), "outLng" to d.get("lng")))
            }
            stopService(Intent(this, TrackService::class.java))
            started = false
            ref.delete()
        }.addOnFailureListener { toast(it.message) }
    }

    // ---------- admin ----------
    private fun admin() {
        screen = "admin"; val l = root()
        l.addView(tv("Admin – $name", 20f, true))
        val row = LinearLayout(this)
        row.addView(btn("Report") { report() }); row.addView(btn("Logout") { auth.signOut(); login() })
        l.addView(row)
        val m = newMap(5.0); m.controller.setCenter(GeoPoint(20.5937, 78.9629))
        l.addView(m, LinearLayout.LayoutParams(-1, 0, 2f))
        val list = tv(""); val sc = ScrollView(this); sc.addView(list); l.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
        reg = fs.collection("live").addSnapshotListener { s, _ ->
            if (s == null) return@addSnapshotListener
            m.overlays.clear(); val pts = mutableListOf<GeoPoint>(); val sb = StringBuilder()
            for (d in s.documents) {
                val lat = d.getDouble("lat"); val lng = d.getDouble("lng"); val n = d.getString("name") ?: "?"
                val age = (System.currentTimeMillis() - (d.getLong("t") ?: 0L)) / 60000
                sb.append("• $n – in ${fmt(d.getLong("inAt"))}" + (if (age > 2) " (no signal ${age} min)" else " (live)") + "\n")
                if (lat != null && lng != null) { pin(m, lat, lng, n); pts.add(GeoPoint(lat, lng)) }
            }
            list.text = if (s.isEmpty) "Nobody is checked in." else "Checked in now (${s.size()}):\n" + sb
            if (!fitted && pts.isNotEmpty()) {
                fitted = true
                m.post {
                    if (pts.size == 1) { m.controller.setZoom(17.0); m.controller.setCenter(pts[0]) }
                    else m.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts), true, 120)
                }
            }
            m.invalidate()
        }
    }

    private fun report() {
        screen = "report"; val l = root()
        l.addView(tv("Attendance report", 20f, true))
        val out = tv("Loading…", 12f); out.typeface = Typeface.MONOSPACE
        val row = LinearLayout(this); l.addView(row)
        fun show(days: Int) {
            val cut = if (days == 0) 0L else System.currentTimeMillis() - days * 86400000L
            val rows = logs.filter { (it["inAt"] as? Long ?: 0L) >= cut }
            val f = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())
            val sb = StringBuilder("Name,In,Out,Minutes,InLat,InLng,OutLat,OutLng\n"); val tx = StringBuilder()
            for (r in rows) {
                val i = r["inAt"] as? Long ?: 0L; val o = r["outAt"] as? Long ?: 0L; val mins = (o - i) / 60000
                val n = (r["name"] as? String ?: "").replace(",", " ")
                sb.append("$n,${f.format(Date(i))},${f.format(Date(o))},$mins,${r["inLat"] ?: ""},${r["inLng"] ?: ""},${r["outLat"] ?: ""},${r["outLng"] ?: ""}\n")
                tx.append("$n\n  ${f.format(Date(i))} -> ${f.format(Date(o))}  (${mins / 60}h ${mins % 60}m)\n")
            }
            csv = sb.toString(); out.text = if (rows.isEmpty()) "No records." else "Records: ${rows.size}\n\n$tx"
        }
        row.addView(btn("Last 24h") { show(1) }); row.addView(btn("7 days") { show(7) }); row.addView(btn("All") { show(0) })
        l.addView(btn("Save CSV file") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/csv").putExtra(Intent.EXTRA_TITLE, "attendance.csv"), 2)
        })
        val sc = ScrollView(this); sc.addView(out); l.addView(sc)
        fs.collection("logs").orderBy("inAt", Query.Direction.DESCENDING).limit(1000).get()
            .addOnSuccessListener { s -> logs = s.documents.map { it.data ?: emptyMap() }; show(7) }
            .addOnFailureListener { out.text = it.message }
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
    override fun onDestroy() { reg?.remove(); super.onDestroy() }
}
