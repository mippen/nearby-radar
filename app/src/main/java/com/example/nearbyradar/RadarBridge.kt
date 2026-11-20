package com.example.nearbyradar

import android.webkit.JavascriptInterface
import android.webkit.WebView
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.*

class RadarBridge(
    private val webView: WebView,
    private val onGpsRequested: () -> Unit
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val executor = Executors.newCachedThreadPool()

    private data class AprsEntry(val pkt: AprsPacket, val lastSeenMs: Long)
    private val aprsMap = ConcurrentHashMap<String, AprsEntry>()

    @Volatile private var aprsLat = 59.3293
    @Volatile private var aprsLon = 18.0686
    @Volatile private var aprsRadiusKm = 80.0
    @Volatile private var aprsConnected = false
    @Volatile private var aprsWriter: OutputStreamWriter? = null
    @Volatile private var running = true

    private var meshCacheTs = 0L
    private var meshCacheNodes: List<JSONObject> = emptyList()

    private val heliTypes = setOf(
        "R22", "R44", "R66", "B06", "B212", "B407", "B412", "B429", "B505",
        "EC20", "EC30", "EC35", "EC45", "EC55", "EC75", "AS50", "AS55", "AS65",
        "H125", "H130", "H135", "H145", "H160", "H175", "H225", "A109", "A119",
        "A139", "A169", "A189", "AW39", "S76", "S92", "MD52", "MD60", "NH90",
        "UH60", "H60", "CH47", "BK17"
    )

    init {
        startAprsThread()
    }

    private fun fmt4(v: Double): String = String.format(Locale.US, "%.4f", v)

    private fun safeJs(json: String): String =
        json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    private fun optCleanStr(obj: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            if (obj.has(k) && !obj.isNull(k)) {
                val v = obj.opt(k)?.toString()?.trim()
                if (!v.isNullOrEmpty()) return v
            }
        }
        return null
    }

    fun shutdown() {
        running = false
        try { aprsWriter?.close() } catch (_: Exception) {}
        executor.shutdownNow()
    }

    private fun startAprsThread() {
        Thread {
            while (running) {
                var socket: Socket? = null
                try {
                    socket = Socket("rotate.aprs2.net", 14580)
                    socket.soTimeout = 120_000
                    val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.US_ASCII)
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    aprsWriter = writer
                    aprsConnected = true

                    val rad = aprsRadiusKm.roundToInt().coerceIn(10, 500)
                    writer.write("user N0CALL pass -1 vers NearbyRadarAPK 1.0 filter r/${fmt4(aprsLat)}/${fmt4(aprsLon)}/$rad\r\n")
                    writer.flush()

                    while (running) {
                        val line = reader.readLine() ?: break
                        val pkt = AprsParser.parse(line) ?: continue
                        aprsMap[pkt.callsign] = AprsEntry(pkt, System.currentTimeMillis())
                        if (aprsMap.size > 4000) {
                            val cutoff = System.currentTimeMillis() - 3600_000L
                            aprsMap.entries.removeIf { it.value.lastSeenMs < cutoff }
                        }
                    }
                } catch (_: Exception) {
                    aprsConnected = false
                    aprsWriter = null
                    try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
                } finally {
                    aprsConnected = false
                    aprsWriter = null
                    try { socket?.close() } catch (_: Exception) {}
                }
            }
        }.apply { isDaemon = true }.start()
    }

    @JavascriptInterface
    fun requestLocation() {
        onGpsRequested()
    }

    @JavascriptInterface
    fun fetchFlights(lat: Double, lon: Double, radiusKm: Double) {
        executor.execute {
            val distNm = (radiusKm / 1.852).roundToInt().coerceIn(1, 250)
            val url = "https://opendata.adsb.fi/api/v3/lat/${fmt4(lat)}/lon/${fmt4(lon)}/dist/$distNm"
            val out = JSONArray()
            try {
                val req = Request.Builder().url(url).header("User-Agent", "NearbyRadarAPK/1.0").build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: "{}"
                        val acArray = JSONObject(body).optJSONArray("ac") ?: JSONArray()
                        for (i in 0 until acArray.length()) {
                            val ac = acArray.getJSONObject(i)
                            val alat = ac.optDouble("lat", Double.NaN)
                            val alon = ac.optDouble("lon", Double.NaN)
                            if (alat.isNaN() || alon.isNaN()) continue
                            val dist = haversineKm(lat, lon, alat, alon)
                            if (dist > radiusKm) continue

                            val cat = ac.optString("category", "")
                            val t = ac.optString("t", "").uppercase().trim()
                            val desc = ac.optString("desc", "")
                            val isHeli = cat == "A7" || t in heliTypes ||
                                desc.uppercase().let { it.contains("HELI") || it.contains("ROTOR") }

                            val altRaw = ac.opt("alt_baro")
                            val onGround = altRaw?.toString() == "ground"
                            val altFt = if (onGround) 0 else ac.optInt("alt_baro", -1).let { if (it < 0) null else it }

                            val callsign = ac.optString("flight", "").trim()
                                .ifEmpty { ac.optString("r", "").ifEmpty { ac.optString("hex", "UNKNOWN") } }

                            out.put(JSONObject().apply {
                                put("hex", ac.optString("hex", ""))
                                put("callsign", callsign)
                                put("reg", ac.optString("r", ""))
                                put("type", t)
                                put("desc", desc)
                                put("is_helicopter", isHeli)
                                put("lat", alat)
                                put("lon", alon)
                                put("on_ground", onGround)
                                put("alt_ft", altFt ?: JSONObject.NULL)
                                put("speed_kts", if (ac.has("gs")) ac.optDouble("gs") else JSONObject.NULL)
                                put("track", ac.optDouble("track", 0.0))
                                put("squawk", ac.optString("squawk", ""))
                                put("distance_km", (dist * 10).roundToInt() / 10.0)
                            })
                        }
                    }
                }
            } catch (_: Exception) {}

            val payload = safeJs(JSONObject().put("aircraft", out).toString())
            webView.post {
                webView.evaluateJavascript("window.onFlightsData($payload)", null)
            }
        }
    }

    @JavascriptInterface
    fun fetchAprs(lat: Double, lon: Double, radiusKm: Double) {
        executor.execute {
            val moved = haversineKm(aprsLat, aprsLon, lat, lon)
            if (moved > 5.0 || abs(aprsRadiusKm - radiusKm) > 10.0) {
                aprsLat = lat
                aprsLon = lon
                aprsRadiusKm = radiusKm
                val w = aprsWriter
                if (w != null && aprsConnected) {
                    val rad = radiusKm.roundToInt().coerceIn(10, 500)
                    try {
                        w.write("#filter r/${fmt4(lat)}/${fmt4(lon)}/$rad\r\n")
                        w.flush()
                    } catch (_: Exception) {}
                }
            }

            val cutoff = System.currentTimeMillis() - 3600_000L
            val arr = JSONArray()
            for (entry in aprsMap.values) {
                if (entry.lastSeenMs < cutoff) continue
                val dist = haversineKm(lat, lon, entry.pkt.lat, entry.pkt.lon)
                if (dist <= radiusKm) {
                    arr.put(JSONObject().apply {
                        put("callsign", entry.pkt.callsign)
                        put("lat", entry.pkt.lat)
                        put("lon", entry.pkt.lon)
                        put("symbol", entry.pkt.symbol)
                        put("comment", entry.pkt.comment)
                        put("last_seen", entry.lastSeenMs / 1000.0)
                        put("distance_km", (dist * 10).roundToInt() / 10.0)
                    })
                }
            }
            val payload = safeJs(JSONObject().apply {
                put("connected", aprsConnected)
                put("stations", arr)
            }.toString())
            webView.post {
                webView.evaluateJavascript("window.onAprsData($payload)", null)
            }
        }
    }

    @JavascriptInterface
    fun fetchMeshcore(lat: Double, lon: Double, radiusKm: Double) {
        executor.execute {
            val now = System.currentTimeMillis()
            if (meshCacheNodes.isEmpty() || now - meshCacheTs > 300_000L) {
                try {
                    val req = Request.Builder()
                        .url("https://map.meshcore.io/api/v1/nodes")
                        .header("Accept", "application/json")
                        .header("User-Agent", "NearbyRadarAPK/1.0")
                        .build()
                    http.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string()?.trim() ?: "[]"
                            val rawArr = if (body.startsWith("[")) {
                                JSONArray(body)
                            } else {
                                val obj = JSONObject(body)
                                obj.optJSONArray("nodes") ?: obj.optJSONArray("data") ?: JSONArray()
                            }
                            val parsed = ArrayList<JSONObject>(rawArr.length())
                            for (i in 0 until rawArr.length()) {
                                val n = rawArr.optJSONObject(i) ?: continue
                                val nlat = if (n.has("adv_lat")) n.optDouble("adv_lat", Double.NaN) else n.optDouble("lat", Double.NaN)
                                val nlon = if (n.has("adv_lon")) n.optDouble("adv_lon", Double.NaN) else n.optDouble("lon", Double.NaN)
                                if (nlat.isNaN() || nlon.isNaN() || (nlat == 0.0 && nlon == 0.0)) continue

                                val roleRaw = optCleanStr(n, "type", "role", "node_type")
                                val role = when (roleRaw) {
                                    "1" -> "Companion"
                                    "2" -> "Repeater"
                                    "3" -> "Room Server"
                                    "4" -> "Sensor"
                                    null -> "Node"
                                    else -> roleRaw
                                }
                                parsed.add(JSONObject().apply {
                                    put("id", optCleanStr(n, "public_key", "id") ?: "")
                                    put("name", optCleanStr(n, "adv_name", "name") ?: "Unnamed MeshCore")
                                    put("role", role)
                                    put("lat", nlat)
                                    put("lon", nlon)
                                })
                            }
                            meshCacheNodes = parsed
                            meshCacheTs = now
                        }
                    }
                } catch (_: Exception) {}
            }

            val out = JSONArray()
            for (node in meshCacheNodes) {
                val dist = haversineKm(lat, lon, node.getDouble("lat"), node.getDouble("lon"))
                if (dist <= radiusKm) {
                    out.put(JSONObject(node.toString()).put("distance_km", (dist * 10).roundToInt() / 10.0))
                }
            }
            val payload = safeJs(JSONObject().put("devices", out).toString())
            webView.post {
                webView.evaluateJavascript("window.onMeshData($payload)", null)
            }
        }
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}
