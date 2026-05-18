package com.drexalane.railpk

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.JsonReader
import org.json.JSONObject

/**
 * Réseau ferré chargé en mémoire avec index spatial.
 * ⚠️ RÉFÉRENCE CANONIQUE — version de production.
 * La version Dart dans lib/engine/rail_network.dart est une copie de test.
 * Toute modification ici doit être répercutée dans la version Dart.
 */
class RailNetwork {
    val points = mutableListOf<PkPoint>()
    val byLigne = mutableMapOf<String, MutableList<PkPoint>>()
    private val grid = mutableMapOf<String, MutableList<Int>>()

    @Volatile var loaded = false
        private set

    var lastError: String? = null
        private set

    companion object {
        private const val CELL_SIZE = 0.05
    }

    /** Charge depuis une chaîne JSON (utilisé pour les tests). */
    fun loadFromString(raw: String) {
        if (loaded) return
        try {
            // Réinitialise
            points.clear()
            byLigne.clear()
            grid.clear()

            val root = JSONObject(raw)
            val features = root.getJSONArray("features")
            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val props = feature.getJSONObject("properties")
                val codeLigne = props.getString("code_ligne")
                val pk = props.getDouble("pk")
                val geom = feature.getJSONObject("geometry")
                val coords = geom.getJSONArray("coordinates")
                val lon = coords.getDouble(0)
                val lat = coords.getDouble(1)

                // Validation bornes coordonnées (A7)
                if (lon < -180.0 || lon > 180.0 || lat < -90.0 || lat > 90.0) continue

                val pt = PkPoint(lon, lat, pk, codeLigne)
                points.add(pt)
                byLigne.getOrPut(codeLigne) { mutableListOf() }.add(pt)
            }

            for (list in byLigne.values) {
                list.sortBy { it.pk }
            }
            buildGrid()
            loaded = true
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message ?: "?"}"
            try {
                android.util.Log.e("RailPK",
                    "Échec chargement JSON — $lastError", e)
            } catch (_: Exception) {
                // Log indisponible en test unitaire (JVM)
            }
            points.clear()
            byLigne.clear()
        }
    }

    /** Charge le GeoJSON depuis les assets — streaming (pas de pic mémoire). */
    fun load(context: Context) {
        if (loaded) return

        try {
            val fd: AssetFileDescriptor = context.assets
                .openFd("flutter_assets/data/rail_record_referentiel_pk_france.geojson")
            fd.use {
                val input = it.createInputStream()
                val reader = JsonReader(input.bufferedReader())

                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "type" -> reader.nextString()
                        "features" -> {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                readFeature(reader)
                            }
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                reader.close()
            }

            // Tri par PK croissant
            for (list in byLigne.values) {
                list.sortBy { it.pk }
            }

            buildGrid()
            loaded = true

        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message ?: "?"}"
            try {
                android.util.Log.e("RailPK",
                    "Échec chargement GeoJSON — $lastError", e)
            } catch (_: Exception) {
                // Log indisponible en test unitaire (JVM)
            }
            points.clear()
            byLigne.clear()
        }
    }

    private fun readFeature(reader: JsonReader) {
        var lon = 0.0
        var lat = 0.0
        var pk = 0.0
        var codeLigne = ""
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "type" -> reader.nextString()
                "properties" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "code_ligne" -> codeLigne = reader.nextString()
                            "pk" -> pk = reader.nextDouble()
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                "geometry" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "type" -> reader.nextString()
                            "coordinates" -> {
                                reader.beginArray()
                                lon = reader.nextDouble()
                                lat = reader.nextDouble()
                                reader.endArray()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        // Validation bornes coordonnées (A7)
        if (lon < -180.0 || lon > 180.0 || lat < -90.0 || lat > 90.0) return

        val pt = PkPoint(lon, lat, pk, codeLigne)
        points.add(pt)
        byLigne.getOrPut(codeLigne) { mutableListOf() }.add(pt)
    }

    private fun buildGrid() {
        for ((i, pt) in points.withIndex()) {
            val key = cellKey(pt.lon, pt.lat)
            grid.getOrPut(key) { mutableListOf() }.add(i)
        }
    }

    private fun cellKey(lon: Double, lat: Double): String {
        val ci = (lon / CELL_SIZE).toInt()
        val cj = (lat / CELL_SIZE).toInt()
        return "${ci}_$cj"
    }

    /** Plus proche voisin par Haversine. */
    fun nearest(lon: Double, lat: Double): NearestResult? {
        if (!loaded || points.isEmpty()) return null

        val ci = (lon / CELL_SIZE).toInt()
        val cj = (lat / CELL_SIZE).toInt()

        var best: PkPoint? = null
        var bestDist = Double.MAX_VALUE
        var bestIdx = -1

        for (di in -1..1) {
            for (dj in -1..1) {
                val cell = grid["${ci + di}_${cj + dj}"] ?: continue
                for (idx in cell) {
                    val pt = points[idx]
                    val d = GeoUtils.haversineM(lon, lat, pt.lon, pt.lat)
                    if (d < bestDist) {
                        bestDist = d
                        best = pt
                        bestIdx = idx
                    }
                }
            }
        }

        return best?.let { NearestResult(it, bestIdx, bestDist) }
    }

    /** Voisins du point dans sa ligne (précédent, suivant). */
    fun neighbors(point: PkPoint): Pair<PkPoint, PkPoint>? {
        val line = byLigne[point.codeLigne] ?: return null
        if (line.size < 2) return null

        val pos = binarySearchPk(line, point.pk)
        if (pos < 0) return null

        val prev = if (pos > 0) line[pos - 1] else line[pos]
        val next = if (pos < line.size - 1) line[pos + 1] else line[pos]
        return prev to next
    }

    private fun binarySearchPk(sorted: List<PkPoint>, pk: Double): Int {
        var lo = 0
        var hi = sorted.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val cmp = sorted[mid].pk.compareTo(pk)
            when {
                cmp < 0 -> lo = mid + 1
                cmp > 0 -> hi = mid - 1
                else -> return mid
            }
        }
        return lo.coerceIn(0, sorted.size - 1)
    }
}
