package com.example.nearbyradar

import kotlin.math.pow

data class AprsPacket(
    val callsign: String,
    val lat: Double,
    val lon: Double,
    val symbol: String,
    val comment: String
)

object AprsParser {

    fun parse(rawLine: String): AprsPacket? {
        if (rawLine.isBlank() || rawLine.startsWith("#")) return null
        val colonIdx = rawLine.indexOf(':')
        if (colonIdx <= 0 || colonIdx + 1 >= rawLine.length) return null

        val header = rawLine.substring(0, colonIdx)
        val info = rawLine.substring(colonIdx + 1)

        val gtIdx = header.indexOf('>')
        if (gtIdx <= 0) return null
        val from = header.substring(0, gtIdx).trim()
        val dest = header.substring(gtIdx + 1).substringBefore(',').trim()

        return when (info[0]) {
            '!', '=' -> parsePosition(from, info.substring(1))
            '/', '@' -> if (info.length >= 9) parsePosition(from, info.substring(8)) else null
            '\'', '`' -> parseMicE(from, dest, info)
            else -> null
        }
    }

    private fun parsePosition(from: String, body: String): AprsPacket? {
        if (body.isEmpty()) return null
        val first = body[0]

        if (first in '0'..'9' && body.length >= 19) {
            val lat = decodeDms(body.substring(0, 8), isLat = true) ?: return null
            val symTable = body[8]
            val lon = decodeDms(body.substring(9, 18), isLat = false) ?: return null
            val symCode = body[18]
            val comment = body.substring(19).trim()
            return AprsPacket(from, lat, lon, "$symTable$symCode", comment)
        }

        if (body.length >= 13) {
            val symTable = body[0]
            val y = decodeBase91(body.substring(1, 5)) ?: return null
            val x = decodeBase91(body.substring(5, 9)) ?: return null
            val symCode = body[9]
            val lat = 90.0 - y / 380926.0
            val lon = -180.0 + x / 190463.0
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            val comment = if (body.length > 13) body.substring(13).trim() else ""
            return AprsPacket(from, lat, lon, "$symTable$symCode", comment)
        }

        return null
    }

    private fun parseMicE(from: String, dest: String, info: String): AprsPacket? {
        if (dest.length < 6 || info.length < 9) return null
        val latDigits = CharArray(6)
        var isNorth = false
        var lonOffset100 = false
        var isWest = false

        for (i in 0 until 6) {
            val c = dest[i]
            val digit: Char = when (c) {
                in '0'..'9' -> c
                in 'A'..'J' -> ('0'.code + (c.code - 'A'.code)).toChar()
                'K', 'L' -> '0'
                in 'P'..'Y' -> ('0'.code + (c.code - 'P'.code)).toChar()
                'Z' -> '0'
                else -> return null
            }
            latDigits[i] = digit
            val isHigh = c in 'P'..'Z'
            when (i) {
                3 -> isNorth = isHigh
                4 -> lonOffset100 = isHigh
                5 -> isWest = isHigh
            }
        }

        val latDeg = "${latDigits[0]}${latDigits[1]}".toIntOrNull() ?: return null
        val latMin = "${latDigits[2]}${latDigits[3]}.${latDigits[4]}${latDigits[5]}".toDoubleOrNull() ?: return null
        var lat = latDeg + latMin / 60.0
        if (!isNorth) lat = -lat

        var d = info[1].code - 28
        if (lonOffset100) d += 100
        if (d in 180..189) d -= 80 else if (d in 190..199) d -= 190

        var m = info[2].code - 28
        if (m >= 60) m -= 60
        val h = info[3].code - 28
        var lon = d + (m + h / 100.0) / 60.0
        if (isWest) lon = -lon

        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null

        val symCode = info[7]
        val symTable = info[8]
        val comment = if (info.length > 9) info.substring(9).trim() else ""
        return AprsPacket(from, lat, lon, "$symTable$symCode", comment)
    }

    private fun decodeDms(s: String, isLat: Boolean): Double? {
        val degLen = if (isLat) 2 else 3
        val deg = s.substring(0, degLen).toDoubleOrNull() ?: return null
        val min = s.substring(degLen, s.length - 1).replace(' ', '0').toDoubleOrNull() ?: return null
        val hemi = s.last().uppercaseChar()
        var dec = deg + min / 60.0
        if (hemi == 'S' || hemi == 'W') dec = -dec
        return dec
    }

    private fun decodeBase91(s: String): Double? {
        var acc = 0.0
        for (i in s.indices) {
            val v = s[i].code - 33
            if (v < 0 || v > 90) return null
            acc += v * 91.0.pow(s.length - 1 - i)
        }
        return acc
    }
}
