package com.byd.tripstats.server

import android.content.Context
import android.util.Log
import com.byd.tripstats.data.local.BydStatsDatabase
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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
            uri == "/api/charges"                             -> serveCharges()
            uri.matches(Regex("/api/charges/\\d+/points"))    -> serveChargePoints(uri.chargeId())
            uri.matches(Regex("/api/charges/\\d+"))           -> serveChargeDetail(uri.lastSegmentLong())
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
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
            }.toString()
        }
        return serveJson(json)
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

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun <T> decimate(list: List<T>, n: Int): List<T> {
        if (list.size <= n) return list
        val step = list.size.toDouble() / n
        return List(n) { i -> list[(i * step).toInt().coerceAtMost(list.size - 1)] }
    }
}
