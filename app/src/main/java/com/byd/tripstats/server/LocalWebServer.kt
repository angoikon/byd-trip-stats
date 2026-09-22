package com.byd.tripstats.server

import android.content.Context
import android.util.Log
import com.byd.tripstats.data.analysis.CostAttribution
import com.byd.tripstats.data.analysis.TripReport
import com.byd.tripstats.data.local.BydStatsDatabase
import com.byd.tripstats.data.local.entity.TripEntity
import com.byd.tripstats.data.local.entity.TripStatsEntity
import com.byd.tripstats.data.notify.VehicleEventLog
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.data.preferences.SocSource
import com.byd.tripstats.data.repository.BatteryVoltageHistoryRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LocalWebServer(
    private val context: Context,
    private val db: BydStatsDatabase,
    port: Int = DEFAULT_PORT,
    private val pin: String,
    private val onLockoutChanged: (lockedCount: Int) -> Unit = {}
) : NanoHTTPD(port) {

    companion object {
        const val DEFAULT_PORT = 8888
        private const val TAG = "LocalWebServer"
        private const val MAX_POINTS = 500
        private const val SESSION_COOKIE = "byd_session"
        private const val SESSION_MAX_AGE = 30 * 24 * 3600
        private const val MAX_ATTEMPTS = 5
    }

    // In-memory set of valid session tokens; cleared when the server restarts (pin/port change)
    private val sessions: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap())

    // Failed PIN attempts per remote IP — value is the attempt count
    private val failedAttempts = ConcurrentHashMap<String, Int>()

    init {
        // NanoHTTPD's stock temp manager writes to java.io.tmpdir, which is not dependably
        // writable on Android. Buffer uploads in our own cache instead — external first,
        // because a database backup is far bigger than internal storage wants to hold twice.
        val uploadCache = File(context.externalCacheDir ?: context.cacheDir, "upload")
        setTempFileManagerFactory { UploadTempFileManager(uploadCache) }
    }

    private fun clientIp(session: IHTTPSession): String =
        session.headers["remote-addr"] ?: "unknown"

    private fun isLockedOut(ip: String) =
        (failedAttempts[ip] ?: 0) >= MAX_ATTEMPTS

    private fun recordFailure(ip: String) {
        val count = (failedAttempts[ip] ?: 0) + 1
        failedAttempts[ip] = count
        onLockoutChanged(failedAttempts.values.count { it >= MAX_ATTEMPTS })
        Log.w(TAG, "Failed PIN attempt $count/$MAX_ATTEMPTS from $ip")
    }

    private fun clearFailures(ip: String) {
        failedAttempts.remove(ip)
        onLockoutChanged(failedAttempts.values.count { it >= MAX_ATTEMPTS })
    }

    fun clearLockouts() {
        failedAttempts.clear()
        onLockoutChanged(0)
        Log.i(TAG, "All lockouts cleared")
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "${session.method} $uri")
        return try {
            addCors(route(session))
        } catch (e: Exception) {
            Log.e(TAG, "Error serving $uri", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "error")
        }
    }

    private fun addCors(r: Response): Response {
        r.addHeader("Access-Control-Allow-Origin", "*")
        return r
    }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri
        // Static PWA assets — no auth needed (required for manifest/SW to work before login)
        if (uri == "/manifest.json") return serveAsset("pwa/manifest.json", "application/json")
        if (uri == "/sw.js")         return serveAsset("pwa/sw.js",         "application/javascript")
        if (uri.startsWith("/icons/")) return serveAsset("pwa$uri",         "image/png")

        // Login / logout — no auth needed
        if (uri == "/login" && session.method == Method.POST) return handleLogin(session)
        if (uri == "/login") {
            val ip = clientIp(session)
            if (isLockedOut(ip)) return serveLockedPage(ip)
            return serveLoginPage(error = session.parameters.containsKey("error"))
        }
        if (uri == "/logout") return handleLogout(session)

        // All other routes require a valid session
        if (!isAuthenticated(session)) {
            return if (uri.startsWith("/api/")) {
                // API callers get 401 JSON rather than an HTML redirect
                newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                    """{"error":"unauthenticated"}""")
            } else {
                redirect("/login")
            }
        }

        val isPost = session.method == Method.POST
        return when {
            uri == "/" || uri == "/index.html"                -> serveAsset("pwa/index.html", "text/html; charset=utf-8")
            uri == "/api/status"                              -> serveJson("""{"ready":true}""")
            isPost && uri.matches(Regex("/api/trips/\\d+/favourite"))   -> setTripFavourite(session, uri.favId("trips"))
            isPost && uri.matches(Regex("/api/charges/\\d+/favourite")) -> setChargeFavourite(session, uri.favId("charges"))
            uri == "/api/trips"                               -> serveTrips()
            uri.matches(Regex("/api/trips/\\d+/points"))      -> serveTripPoints(uri.tripId())
            uri.matches(Regex("/api/trips/\\d+"))             -> serveTripDetail(uri.lastSegmentLong())
            uri == "/api/notifications"                       -> serveNotifications()
            uri == "/api/battery"                             -> serveBatteryHistory()
            uri == "/api/charges"                             -> serveCharges()
            uri.matches(Regex("/api/charges/\\d+/points"))    -> serveChargePoints(uri.chargeId())
            uri.matches(Regex("/api/charges/\\d+"))           -> serveChargeDetail(uri.lastSegmentLong())
            isPost && uri == "/api/files/upload"              -> handleFileUpload(session)
            isPost && uri == "/api/files/delete"              -> handleFileDelete(session)
            uri == "/api/files"                               -> serveFileListing(session)
            uri == "/files/download"                          -> serveFileDownload(session)
            uri == "/files/view"                              -> serveFileView(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    /**
     * The vehicle events the app has recorded — trips finished, charges finished, alerts —
     * newest first, for the companion's notification feed.
     *
     * A read of a small local ring, not the database: the feed is a passive list of what the car
     * has done recently, and it is deliberately independent of whether a Telegram bot is linked
     * or its switches are on. Those govern a push to a phone; this is the car's own history.
     */
    private fun serveNotifications(): Response {
        val events = VehicleEventLog.getInstance(context).recent()
        val arr = JSONArray()
        events.forEach { e ->
            arr.put(JSONObject().apply {
                put("key", e.key)
                put("type", e.type.name.lowercase())
                put("title", e.title)
                put("body", e.body)
                put("timestamp", e.timestamp)
            })
        }
        return serveJson(arr.toString())
    }

    // ── Favourite toggle (live mode write-back) ──────────────────────────────────
    // Favourite state is sent as a query param: POST /api/trips/5/favourite?favourite=1

    private fun setTripFavourite(session: IHTTPSession, id: Long): Response {
        val fav = session.parameters["favourite"]?.firstOrNull() == "1"
        runBlocking(Dispatchers.IO) { db.tripDao().setFavourite(id, fav) }
        return serveJson("""{"ok":true,"id":$id,"isFavourite":${if (fav) 1 else 0}}""")
    }

    private fun setChargeFavourite(session: IHTTPSession, id: Long): Response {
        val fav = session.parameters["favourite"]?.firstOrNull() == "1"
        runBlocking(Dispatchers.IO) { db.chargingSessionDao().setFavourite(id, fav) }
        return serveJson("""{"ok":true,"id":$id,"isFavourite":${if (fav) 1 else 0}}""")
    }

    // ── Auth helpers ───────────────────────────────────────────────────────────

    private fun isAuthenticated(session: IHTTPSession): Boolean {
        val token = getSessionToken(session) ?: return false
        return sessions.contains(token)
    }

    private fun getSessionToken(session: IHTTPSession): String? =
        session.headers["cookie"]
            ?.split(";")
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$SESSION_COOKIE=") }
            ?.removePrefix("$SESSION_COOKIE=")
            ?.trim()

    private fun handleLogin(session: IHTTPSession): Response {
        val ip = clientIp(session)
        if (isLockedOut(ip)) return serveLockedPage(ip)
        runCatching { session.parseBody(HashMap()) }
        val entered = session.parameters["pin"]?.firstOrNull() ?: ""
        return if (entered == pin) {
            clearFailures(ip)
            val token = UUID.randomUUID().toString()
            sessions.add(token)
            val r = redirect("/")
            r.addHeader("Set-Cookie",
                "$SESSION_COOKIE=$token; Path=/; HttpOnly; SameSite=Lax; Max-Age=$SESSION_MAX_AGE")
            r
        } else {
            recordFailure(ip)
            if (isLockedOut(ip)) serveLockedPage(ip) else redirect("/login?error=1")
        }
    }

    private fun handleLogout(session: IHTTPSession): Response {
        getSessionToken(session)?.let { sessions.remove(it) }
        val r = redirect("/login")
        r.addHeader("Set-Cookie", "$SESSION_COOKIE=; Path=/; HttpOnly; Max-Age=0")
        return r
    }

    private fun redirect(location: String): Response =
        newFixedLengthResponse(Response.Status.REDIRECT_SEE_OTHER, MIME_PLAINTEXT, "").also {
            it.addHeader("Location", location)
        }

    private fun serveLoginPage(error: Boolean): Response {
        val errorHtml = if (error)
            """<p class="error">Incorrect PIN — try again.</p>""" else ""
        val html = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>BYD Trip Stats</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:#0f1115;color:#e6e8ec;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
     display:flex;align-items:center;justify-content:center;min-height:100vh}
.card{background:#181b22;border:1px solid #262b36;border-radius:16px;padding:32px 28px;width:100%;max-width:340px}
.logo{display:flex;align-items:center;gap:10px;margin-bottom:20px}
.logo img{width:36px;height:36px;border-radius:8px}
.logo span{font-size:18px;font-weight:700}
p{color:#9aa3b2;font-size:14px;line-height:1.5;margin-bottom:20px}
label{display:block;font-size:12px;color:#9aa3b2;margin-bottom:6px;font-weight:500}
input{width:100%;padding:14px;background:#20242d;border:1px solid #262b36;border-radius:10px;
      color:#e6e8ec;font-size:28px;text-align:center;letter-spacing:10px;outline:none;
      -webkit-text-security:disc}
input:focus{border-color:#4cc9f0}
button{width:100%;padding:13px;margin-top:14px;background:#2196F3;color:#fff;border:none;
       border-radius:10px;font-size:16px;font-weight:600;cursor:pointer}
button:active{background:#1976D2}
.error{color:#f72585;font-size:13px;margin-top:10px;text-align:center}
</style>
</head>
<body>
<div class="card">
  <div class="logo">
    <img src="/icons/icon-192.png" alt=""/>
    <span>BYD Trip Stats</span>
  </div>
  <p>Enter the PIN shown in the app under<br><strong>Settings → App → Web Companion</strong>.</p>
  <form method="POST" action="/login">
    <label>Access PIN</label>
    <input type="text" inputmode="numeric" name="pin" maxlength="10" autocomplete="off" autofocus/>
    <button type="submit">Unlock</button>
    $errorHtml
  </form>
</div>
</body>
</html>"""
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun serveLockedPage(ip: String): Response {
        val html = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>BYD Trip Stats — Locked</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:#0f1115;color:#e6e8ec;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
     display:flex;align-items:center;justify-content:center;min-height:100vh}
.card{background:#181b22;border:1px solid #262b36;border-radius:16px;padding:32px 28px;width:100%;max-width:340px;text-align:center}
.icon{font-size:48px;margin-bottom:16px}
h2{font-size:20px;margin-bottom:10px;color:#f72585}
p{color:#9aa3b2;font-size:14px;line-height:1.6;margin-bottom:12px}
code{background:#20242d;padding:2px 8px;border-radius:6px;font-size:13px;color:#4cc9f0}
</style>
</head>
<body>
<div class="card">
  <div class="icon">🔒</div>
  <h2>Access Locked</h2>
  <p>Too many incorrect PIN attempts from <code>$ip</code>.</p>
  <p>Go to the car, open <strong>BYD Trip Stats → Settings → App → Web Companion</strong> and tap <strong>Clear lockouts</strong> to restore access.</p>
</div>
</body>
</html>"""
        return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/html; charset=utf-8", html)
    }

    // ── URI helpers ────────────────────────────────────────────────────────────

    private fun String.lastSegmentLong() = trimEnd('/').substringAfterLast('/').toLong()
    private fun String.tripId()   = removePrefix("/api/trips/").removeSuffix("/points").toLong()
    private fun String.chargeId() = removePrefix("/api/charges/").removeSuffix("/points").toLong()
    private fun String.favId(kind: String) = removePrefix("/api/$kind/").removeSuffix("/favourite").toLong()

    // ── Asset serving ──────────────────────────────────────────────────────────

    private fun serveAsset(path: String, mimeType: String): Response = try {
        newChunkedResponse(Response.Status.OK, mimeType, context.assets.open(path))
    } catch (e: IOException) {
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found: $path")
    }

    private fun serveJson(json: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", json)

    // ── API — trips ────────────────────────────────────────────────────────────

    private fun serveTrips(): Response {
        val json = runBlocking(Dispatchers.IO) {
            val trips = db.tripDao()
                .getCompletedTripsBefore(Long.MAX_VALUE)
                .filter { it.endTime != null }
                .sortedByDescending { it.startTime }
            val arr = JSONArray()
            for (trip in trips) {
                val stats = db.tripStatsDao().getStatsForTrip(trip.id)
                arr.put(JSONObject().apply {
                    put("id",                trip.id)
                    put("startTime",         trip.startTime)
                    put("endTime",           trip.endTime)
                    put("offStateDurationMs",trip.offStateDurationMs)
                    put("startSoc",          trip.startSoc)
                    put("endSoc",            trip.endSoc)
                    put("startSocPanel",     trip.startSocPanel)
                    put("endSocPanel",       trip.endSocPanel)
                    put("isManual",          if (trip.isManual) 1 else 0)
                    put("isFavourite",       if (trip.isFavourite) 1 else 0)
                    put("dist",   stats?.totalDistance
                        ?: trip.endOdometer?.minus(trip.startOdometer)?.coerceAtLeast(0.0))
                    put("startOdometer",     trip.startOdometer)
                    put("endOdometer",       trip.endOdometer)
                    put("avgSpd",  stats?.avgSpeed          ?: 0.0)
                    put("eff",     stats?.avgEfficiency     ?: 0.0)
                    put("energy",  stats?.totalEnergyConsumed ?: 0.0)
                })
            }
            arr.toString()
        }
        return serveJson(json)
    }

    private fun serveTripDetail(id: Long): Response {
        val json = runBlocking(Dispatchers.IO) {
            val trip  = db.tripDao().getTripById(id) ?: return@runBlocking "null"
            val stats = db.tripStatsDao().getStatsForTrip(id)
            JSONObject().apply {
                put("id",                trip.id)
                put("startTime",         trip.startTime)
                put("endTime",           trip.endTime)
                put("offStateDurationMs",trip.offStateDurationMs)
                put("startSoc",          trip.startSoc)
                put("endSoc",            trip.endSoc)
                put("startSocPanel",     trip.startSocPanel)
                put("endSocPanel",       trip.endSocPanel)
                put("isManual",          if (trip.isManual) 1 else 0)
                put("isFavourite",       if (trip.isFavourite) 1 else 0)
                put("dist",       stats?.totalDistance ?: trip.distance?.coerceAtLeast(0.0))
                put("startOdometer",     trip.startOdometer)
                put("endOdometer",       trip.endOdometer)
                put("avgSpeed",   stats?.avgSpeed          ?: 0.0)
                put("avgEfficiency",       stats?.avgEfficiency     ?: 0.0)
                put("totalEnergyConsumed", stats?.totalEnergyConsumed ?: 0.0)
                put("compressedRoute", stats?.compressedRoute?.let { route ->
                    JSONArray().also { arr -> route.forEach { pt ->
                        arr.put(JSONObject().apply { put("lat", pt.lat); put("lon", pt.lon) })
                    }}
                })
                // Overview figures + derived analyses — the same TripReport the HTML
                // export embeds, so the companion's detail matches the app's Overview tab.
                put("report", buildTripReport(trip, stats))
            }.toString()
        }
        return serveJson(json)
    }

    /**
     * Runs the analyses over the trip's full point set (never the decimated one the
     * charts get — the physics model integrates per interval, so dropped points would
     * skew it). Cost needs the whole trip/charge history because the FIFO cost basis
     * is resolved across sessions, so it is only attempted when a price signal exists.
     */
    private suspend fun buildTripReport(trip: TripEntity, stats: TripStatsEntity?): JSONObject {
        val points = db.tripDataPointDao().getDataPointsForTripSync(trip.id)
        val prefs  = PreferencesManager(context)
        val blendedRate = runCatching {
            val trips    = db.tripDao().getCompletedTripsBefore(Long.MAX_VALUE)
            val sessions = db.chargingSessionDao().getAllCompletedSessions()
            CostAttribution.blendedTripRates(trips, sessions, prefs.getCachedElectricityPrice())[trip.id]
        }.getOrNull()
        return TripReport.build(
            trip = trip,
            stats = stats,
            dataPoints = points,
            carConfig = prefs.getCachedSelectedCarConfig(),
            blendedRate = blendedRate,
            currencySymbol = prefs.getCachedCurrencySymbol(),
        )
    }

    private fun serveTripPoints(tripId: Long): Response {
        val json = runBlocking(Dispatchers.IO) {
            val pts = decimate(db.tripDataPointDao().getDataPointsForTripSync(tripId), MAX_POINTS)
            JSONArray().also { arr ->
                for (pt in pts) arr.put(JSONObject().apply {
                    put("timestamp",            pt.timestamp)
                    put("speed",                pt.speed)
                    put("power",                pt.power)
                    put("soc",                  pt.soc)
                    put("socPanel",             pt.socPanel)
                    put("altitude",             pt.altitude)
                    put("batteryTemp",          pt.batteryTemp)
                    put("engineSpeedFront",     pt.engineSpeedFront)
                    put("engineSpeedRear",      pt.engineSpeedRear)
                    put("tyrePressureLF",       pt.tyrePressureLF)
                    put("tyrePressureRF",       pt.tyrePressureRF)
                    put("tyrePressureLR",       pt.tyrePressureLR)
                    put("tyrePressureRR",       pt.tyrePressureRR)
                    put("batteryTotalVoltage",  pt.batteryTotalVoltage)
                    put("batteryCellVoltageMin",pt.batteryCellVoltageMin)
                    put("batteryCellVoltageMax",pt.batteryCellVoltageMax)
                })
            }.toString()
        }
        return serveJson(json)
    }

    // ── API — charging ─────────────────────────────────────────────────────────

    private fun serveCharges(): Response {
        val json = runBlocking(Dispatchers.IO) {
            val sessions = db.chargingSessionDao().getAllCompletedSessions()
            JSONArray().also { arr ->
                for (s in sessions) arr.put(JSONObject().apply {
                    put("id",              s.id)
                    put("startTime",       s.startTime)
                    put("endTime",         s.endTime)
                    put("socStart",        s.socStart)
                    put("socEnd",          s.socEnd)
                    put("socStartPanel",   s.socStartPanel)
                    put("socEndPanel",     s.socEndPanel)
                    put("isFavourite",     if (s.isFavourite) 1 else 0)
                    put("kwhAdded",        s.kwhAdded)
                    put("peakKw",          s.peakKw)
                    put("avgKw",           s.avgKw)
                    put("batteryTempStart",s.batteryTempStart)
                    put("startOdometer",   s.startOdometer)
                })
            }.toString()
        }
        return serveJson(json)
    }

    private fun serveChargeDetail(id: Long): Response {
        val json = runBlocking(Dispatchers.IO) {
            val s = db.chargingSessionDao().getSessionById(id) ?: return@runBlocking "null"
            JSONObject().apply {
                put("id",              s.id)
                put("startTime",       s.startTime)
                put("endTime",         s.endTime)
                put("socStart",        s.socStart)
                put("socEnd",          s.socEnd)
                put("socStartPanel",   s.socStartPanel)
                put("socEndPanel",     s.socEndPanel)
                put("isFavourite",     if (s.isFavourite) 1 else 0)
                put("kwhAdded",        s.kwhAdded)
                put("peakKw",          s.peakKw)
                put("avgKw",           s.avgKw)
                put("batteryTempStart",s.batteryTempStart)
                put("batteryTempEnd",  s.batteryTempEnd)
                put("startOdometer",   s.startOdometer)
            }.toString()
        }
        return serveJson(json)
    }

    private fun serveChargePoints(sessionId: Long): Response {
        val json = runBlocking(Dispatchers.IO) {
            val pts = decimate(db.chargingSessionDao().getDataPointsForSessionSync(sessionId), MAX_POINTS)
            JSONArray().also { arr ->
                for (pt in pts) arr.put(JSONObject().apply {
                    put("timestamp",          pt.timestamp)
                    put("soc",                pt.soc)
                    put("socPanel",           pt.socPanel)
                    put("chargingPower",      pt.chargingPower)
                    put("batteryTotalVoltage",pt.batteryTotalVoltage)
                    put("batteryTempAvg",     pt.batteryTempAvg)
                })
            }.toString()
        }
        return serveJson(json)
    }

    /**
     * The 48-hour 12V / SoC history behind the dashboard's HV / 12V card.
     *
     * Sent whole rather than decimated like the trip endpoints: the window holds at most ~2,880
     * samples (one a minute), which is a small response, and averaging points away would smooth
     * out a brief 12V sag — the one thing anyone opens this chart to find.
     *
     * SoC is resolved here against the user's Panel/BMS preference so the companion plots exactly
     * what the app plots, rather than picking a field of its own.
     */
    private fun serveBatteryHistory(): Response {
        val socSource = PreferencesManager(context).getCachedSocSource()
        val points = BatteryVoltageHistoryRepository.getInstance(context).history.value

        val arr = JSONArray()
        points.forEach { p ->
            arr.put(JSONObject().apply {
                put("t", p.timestamp)
                put("v12", p.battery12vVoltage)
                put("hv", p.batteryTotalVoltage)
                put("charging", p.isChargingSample)
                put("soc", if (socSource == SocSource.PANEL && p.socPanel > 0) {
                    p.socPanel.toDouble()
                } else {
                    p.soc
                })
            })
        }
        return serveJson(
            JSONObject()
                .put("windowMs", BatteryVoltageHistoryRepository.HISTORY_WINDOW_MS)
                .put("socSource", socSource.name)
                .put("points", arr)
                .toString()
        )
    }

    // ── File browser ───────────────────────────────────────────────────────────
    // Browse, download and upload inside the fixed roots FileBrowser allows. These routes are
    // already behind the PIN gate in route(); FileBrowser.resolveWithin() is what stops a
    // crafted ?p= from walking out of a root.

    private fun param(session: IHTTPSession, name: String): String =
        session.parameters[name]?.firstOrNull() ?: ""

    private fun serveFileListing(session: IHTTPSession): Response =
        serveJson(FileBrowser.listingJson(context, param(session, "p")).toString())

    private fun resolveReadableFile(session: IHTTPSession): File? =
        FileBrowser.resolve(context, param(session, "p"))
            ?.second
            ?.takeIf { it.isFile && it.canRead() }

    private fun serveFileDownload(session: IHTTPSession): Response {
        val file = resolveReadableFile(session)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        return newFixedLengthResponse(
            Response.Status.OK, "application/octet-stream", FileInputStream(file), file.length()
        ).apply {
            addHeader("Content-Disposition", """attachment; filename="${asciiFilename(file.name)}"""")
        }
    }

    /**
     * Renders a text file in the browser — diag.log above all, which is the one file people
     * actually want to read rather than keep. Always text/plain, so a stored .html cannot
     * execute in the companion's origin.
     */
    private fun serveFileView(session: IHTTPSession): Response {
        val file = resolveReadableFile(session)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        // A screenshot is served as itself, for an <img> to point at. No Content-Disposition, so
        // the browser renders it rather than offering to save it — Download is the other button.
        FileBrowser.imageMimeType(file)?.let { mime ->
            return newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), file.length())
        }
        if (!FileBrowser.isViewable(file)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Not viewable")
        }
        // Tailed rather than streamed whole: diag.log runs to several MB and the end is the part
        // anyone is looking for.
        return newFixedLengthResponse(
            Response.Status.OK, "text/plain; charset=utf-8", FileBrowser.readTail(file)
        )
    }

    private fun handleFileUpload(session: IHTTPSession): Response {
        val target = FileBrowser.resolve(context, param(session, "p"))
        if (target == null || !target.second.isDirectory) return fileError("That folder is not available.")
        val (root, dir) = target
        if (!root.writable) return fileError(readOnlyReason(root))

        val declared = session.headers["content-length"]?.toLongOrNull() ?: -1L
        if (declared > FileBrowser.MAX_UPLOAD_BYTES) {
            return fileError("That file is over the ${FileBrowser.MAX_UPLOAD_BYTES / (1024 * 1024)} MB upload limit.")
        }

        val parts = HashMap<String, String>()
        try {
            session.parseBody(parts)
        } catch (e: Exception) {
            Log.e(TAG, "Upload body parse failed", e)
            return fileError(e.message ?: "Could not read the upload.")
        }

        val field = parts.keys.firstOrNull() ?: return fileError("No file in the upload.")
        val temp = File(parts.getValue(field))
        val name = sanitiseUploadName(session.parameters[field]?.firstOrNull())
            ?: return fileError("That file name is not usable.")
        val destination = FileBrowser.resolveWithin(dir, name)
            ?: return fileError("That file name is not usable.")

        return try {
            temp.inputStream().use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "Uploaded ${destination.name} (${destination.length()} bytes)")
            serveJson(JSONObject().put("ok", true).put("name", destination.name).toString())
        } catch (e: IOException) {
            Log.e(TAG, "Upload write failed", e)
            fileError(e.message ?: "Could not write the file.")
        } finally {
            temp.delete()
        }
    }

    /**
     * Deletes one file. Writable roots only, so the app's own rolling backups — which it prunes
     * itself — can't be removed from here, and files only: a recursive directory delete behind a
     * single tap is a much bigger mistake than deleting the wrong backup.
     */
    private fun handleFileDelete(session: IHTTPSession): Response {
        val resolved = FileBrowser.resolve(context, param(session, "p"))
            ?: return fileError("That file is not available.")
        val (root, file) = resolved
        if (!root.writable) return fileError(readOnlyReason(root))
        if (!file.isFile) return fileError("Only files can be deleted.")

        val outcome = runCatching { file.delete() }
        if (outcome.getOrDefault(false)) {
            Log.i(TAG, "Deleted ${file.name}")
            return serveJson(JSONObject().put("ok", true).put("name", file.name).toString())
        }

        // Says what went wrong rather than just that something did — the first version of this
        // reported a bare failure for a missing storage permission, which took an adb session to
        // work out.
        val why = outcome.exceptionOrNull()?.message
            ?: when {
                !file.exists() -> "it is already gone — refresh the folder"
                !file.canWrite() -> "the app has no write access to it on the car"
                else -> "the car refused it"
            }
        Log.w(TAG, "Delete failed for ${file.absolutePath}: $why")
        return fileError("Could not delete ${file.name} — $why.")
    }

    /** Uploads keep their own name, minus any path the browser may have sent with it. */
    private fun sanitiseUploadName(raw: String?): String? {
        val name = raw?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
        return if (name.isEmpty() || name == "." || name == "..") null else name
    }

    /** Content-Disposition is a header, so the filename loses anything not plainly ASCII. */
    private fun asciiFilename(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /**
     * Why a folder can't be written to. A blocked root is the storage permission almost every time,
     * and that is fixable by the user — so the message says where to fix it rather than stopping at
     * "read-only".
     */
    private fun readOnlyReason(root: FileBrowser.Root): String =
        if (root.writeBlocked) {
            "${root.label} is read-only — the app needs the Storage permission on the car. " +
                "Grant it in Android Settings → Apps → BYD Trip Stats → Permissions, or re-run the " +
                "ADB setup in Settings → App Management."
        } else {
            "${root.label} is read-only."
        }

    private fun fileError(message: String): Response =
        newFixedLengthResponse(
            Response.Status.BAD_REQUEST, "application/json",
            JSONObject().put("ok", false).put("error", message).toString()
        )

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun <T> decimate(list: List<T>, n: Int): List<T> {
        if (list.size <= n) return list
        val step = list.size.toDouble() / n
        return List(n) { i -> list[(i * step).toInt().coerceAtMost(list.size - 1)] }
    }
}

/** Keeps NanoHTTPD's upload buffering inside a directory this app can actually write to. */
private class UploadTempFileManager(private val dir: File) : NanoHTTPD.TempFileManager {

    private val created = mutableListOf<NanoHTTPD.TempFile>()

    init {
        runCatching { dir.mkdirs() }
    }

    override fun createTempFile(filenameHint: String?): NanoHTTPD.TempFile =
        UploadTempFile(dir).also { created += it }

    override fun clear() {
        created.forEach { runCatching { it.delete() } }
        created.clear()
    }
}

private class UploadTempFile(dir: File) : NanoHTTPD.TempFile {

    private val file = File.createTempFile("upload-", ".tmp", dir)
    private val stream = FileOutputStream(file)

    override fun open(): OutputStream = stream

    override fun delete() {
        runCatching { stream.close() }
        file.delete()
    }

    override fun getName(): String = file.absolutePath
}
