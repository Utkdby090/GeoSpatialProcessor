package com.geospatial.processing.data.geopackage

import com.geospatial.processing.domain.model.Asset
import com.geospatial.processing.domain.model.RecordStatus
import com.geospatial.processing.domain.model.Severity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties

/** What a GeoPackage held: the assets that could be read, and a reason for every row that could not. */
data class GeoPackageContent(val assets: List<Asset>, val skipped: List<String>)

/** The file is not a GeoPackage this app can read (not SQLite, no asset layer, damaged…). The message is for the user. */
class GeoPackageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * OGC GeoPackage 1.3 (a SQLite file) export and import of a project's assets, so QGIS, ArcGIS and other GIS tools can open them.
 *
 * The file has one feature layer, [TABLE], with a WGS 84 (EPSG:4326) point per asset and, for GIS users, one text column per
 * property. `properties_json` is the complete, authoritative copy: import reads only that, so a column renamed or edited in a
 * GIS tool never corrupts an import. Images are not embedded; an asset's images come from the project's image folder.
 * Assets without a position (0,0) get an empty geometry.
 */
object GeoPackage {
    const val TABLE = "assets"

    private const val APPLICATION_ID = 0x47504B47 // "GPKG"
    private const val USER_VERSION = 10300         // GeoPackage 1.3.0
    private const val WGS84 = 4326
    private const val MAX_PROPERTY_COLUMNS = 300
    private const val MAX_COLUMN_NAME = 60

    private val FIXED_COLUMNS = listOf("fid", "geom", "asset_id", "plugin_id", "position", "status", "severity", "captured_at", "properties_json")
    private val json = Json
    private val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    private const val WGS84_WKT = "GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,298.257223563," +
        "AUTHORITY[\"EPSG\",\"7030\"]],AUTHORITY[\"EPSG\",\"6326\"]],PRIMEM[\"Greenwich\",0,AUTHORITY[\"EPSG\",\"8901\"]]," +
        "UNIT[\"degree\",0.0174532925199433,AUTHORITY[\"EPSG\",\"9122\"]],AUTHORITY[\"EPSG\",\"4326\"]]"

    // --- export -------------------------------------------------------------------------------------

    /**
     * Writes [assets] to [dest], replacing it. The file is built under a temporary name and moved into place only when
     * complete, so a failure never leaves a half-written or damaged [dest]. Returns the number of assets written.
     */
    fun write(assets: List<Asset>, dest: File, layerTitle: String = "Assets", onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }): Int {
        val dir = dest.absoluteFile.parentFile ?: throw GeoPackageException("The destination folder doesn't exist.")
        if (!dir.isDirectory) throw GeoPackageException("The destination folder doesn't exist.")
        val tmp = File(dir, ".${dest.name}.${System.nanoTime()}.tmp")
        try {
            connect(tmp, readOnly = false).use { conn ->
                conn.autoCommit = false
                createSchema(conn)
                val columns = propertyColumns(assets)
                createFeatureTable(conn, columns.values)
                insertAssets(conn, assets, columns, onProgress)
                registerLayer(conn, assets, layerTitle)
                conn.commit()
            }
            moveInto(tmp, dest)
        } catch (e: GeoPackageException) {
            throw e
        } catch (e: Exception) {
            throw GeoPackageException("The GeoPackage could not be written: ${e.message}", e)
        } finally {
            tmp.delete()
            File(dir, tmp.name + "-journal").delete()
        }
        return assets.size
    }

    private fun createSchema(conn: Connection) = conn.createStatement().use { st ->
        st.execute("PRAGMA application_id = $APPLICATION_ID")
        st.execute("PRAGMA user_version = $USER_VERSION")
        st.execute(
            """CREATE TABLE gpkg_spatial_ref_sys (srs_name TEXT NOT NULL, srs_id INTEGER PRIMARY KEY, organization TEXT NOT NULL,
               organization_coordsys_id INTEGER NOT NULL, definition TEXT NOT NULL, description TEXT)"""
        )
        st.execute(
            """CREATE TABLE gpkg_contents (table_name TEXT NOT NULL PRIMARY KEY, data_type TEXT NOT NULL, identifier TEXT UNIQUE,
               description TEXT DEFAULT '', last_change DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
               min_x DOUBLE, min_y DOUBLE, max_x DOUBLE, max_y DOUBLE, srs_id INTEGER,
               CONSTRAINT fk_gc_r_srs_id FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys(srs_id))"""
        )
        st.execute(
            """CREATE TABLE gpkg_geometry_columns (table_name TEXT NOT NULL, column_name TEXT NOT NULL, geometry_type_name TEXT NOT NULL,
               srs_id INTEGER NOT NULL, z TINYINT NOT NULL, m TINYINT NOT NULL,
               CONSTRAINT pk_geom_cols PRIMARY KEY (table_name, column_name), CONSTRAINT uk_gc_table_name UNIQUE (table_name),
               CONSTRAINT fk_gc_tn FOREIGN KEY (table_name) REFERENCES gpkg_contents(table_name),
               CONSTRAINT fk_gc_srs FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys (srs_id))"""
        )
        conn.prepareStatement("INSERT INTO gpkg_spatial_ref_sys VALUES (?,?,?,?,?,?)").use { ps ->
            listOf<Array<Any>>(
                arrayOf("Undefined Cartesian SRS", -1, "NONE", -1, "undefined", "undefined Cartesian coordinate reference system"),
                arrayOf("Undefined geographic SRS", 0, "NONE", 0, "undefined", "undefined geographic coordinate reference system"),
                arrayOf("WGS 84 geodetic", WGS84, "EPSG", WGS84, WGS84_WKT, "longitude/latitude coordinates in decimal degrees on the WGS 84 spheroid"),
            ).forEach { row ->
                row.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
                ps.executeUpdate()
            }
        }
    }

    /** Property key -> GIS column name. Names are made safe and unique; keys beyond the cap stay available in `properties_json`. */
    private fun propertyColumns(assets: List<Asset>): Map<String, String> {
        val used = FIXED_COLUMNS.toMutableSet()
        val result = LinkedHashMap<String, String>()
        for (key in assets.flatMapTo(LinkedHashSet()) { it.properties.keys }) {
            if (result.size >= MAX_PROPERTY_COLUMNS) break
            var base = key.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(MAX_COLUMN_NAME)
            if (base.isEmpty() || base[0].isDigit()) base = "p_$base"
            var name = base
            var n = 2
            while (!used.add(name)) name = "${base}_${n++}"
            result[key] = name
        }
        return result
    }

    private fun createFeatureTable(conn: Connection, propertyColumns: Collection<String>) = conn.createStatement().use { st ->
        val extra = propertyColumns.joinToString("") { ", \"$it\" TEXT" }
        st.execute(
            """CREATE TABLE "$TABLE" (fid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, geom POINT, asset_id TEXT NOT NULL UNIQUE,
               plugin_id TEXT NOT NULL, "position" INTEGER NOT NULL, status TEXT NOT NULL, severity TEXT NOT NULL, captured_at TEXT,
               properties_json TEXT NOT NULL$extra)"""
        )
    }

    private fun insertAssets(conn: Connection, assets: List<Asset>, columns: Map<String, String>, onProgress: (Int, Int) -> Unit) {
        val names = columns.keys.toList()
        val sql = "INSERT INTO \"$TABLE\" (geom, asset_id, plugin_id, \"position\", status, severity, captured_at, properties_json" +
            names.joinToString("") { ", \"${columns.getValue(it)}\"" } + ") VALUES (?,?,?,?,?,?,?,?" + ", ?".repeat(names.size) + ")"
        conn.prepareStatement(sql).use { ps ->
            assets.forEachIndexed { index, asset ->
                ps.setBytes(1, if (hasPosition(asset)) pointBlob(asset.latitude, asset.longitude) else null)
                ps.setString(2, asset.id)
                ps.setString(3, asset.pluginId)
                ps.setInt(4, asset.position)
                ps.setString(5, asset.status.name)
                ps.setString(6, asset.severity.name)
                ps.setString(7, asset.capturedAt?.toString())
                ps.setString(8, json.encodeToString(asset.properties))
                names.forEachIndexed { i, key -> ps.setString(9 + i, asset.properties[key]) }
                ps.executeUpdate()
                onProgress(index + 1, assets.size)
            }
        }
    }

    private fun registerLayer(conn: Connection, assets: List<Asset>, title: String) {
        val located = assets.filter(::hasPosition)
        conn.prepareStatement(
            "INSERT INTO gpkg_contents (table_name, data_type, identifier, description, last_change, min_x, min_y, max_x, max_y, srs_id) " +
                "VALUES (?, 'features', ?, 'Exported from GeoSpatial Processor', ?, ?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, TABLE)
            ps.setString(2, title.ifBlank { "Assets" })
            ps.setString(3, timestamp.format(Instant.now()))
            fun bound(i: Int, v: Double?) = if (v == null) ps.setNull(i, java.sql.Types.DOUBLE) else ps.setDouble(i, v)
            bound(4, located.minOfOrNull { it.longitude })
            bound(5, located.minOfOrNull { it.latitude })
            bound(6, located.maxOfOrNull { it.longitude })
            bound(7, located.maxOfOrNull { it.latitude })
            ps.setInt(8, WGS84)
            ps.executeUpdate()
        }
        conn.createStatement().use { it.execute("INSERT INTO gpkg_geometry_columns VALUES ('$TABLE', 'geom', 'POINT', $WGS84, 0, 0)") }
    }

    private fun moveInto(tmp: File, dest: File) {
        try {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun hasPosition(a: Asset) = !(a.latitude == 0.0 && a.longitude == 0.0) &&
        a.latitude.isFinite() && a.longitude.isFinite()

    /** GeoPackage binary header (little endian, no envelope, SRS 4326) followed by a WKB point: x = longitude, y = latitude. */
    internal fun pointBlob(lat: Double, lon: Double): ByteArray =
        ByteBuffer.allocate(8 + 21).order(ByteOrder.LITTLE_ENDIAN).apply {
            put('G'.code.toByte()); put('P'.code.toByte())
            put(0)          // version 1
            put(0x01)       // flags: standard binary, not empty, no envelope, little endian
            putInt(WGS84)
            put(1)          // WKB byte order: little endian
            putInt(1)       // WKB type: Point
            putDouble(lon)
            putDouble(lat)
        }.array()

    // --- import -------------------------------------------------------------------------------------

    /**
     * Reads the assets of a GeoPackage written by [write]. The file is opened read-only and never modified. Rows that are
     * damaged are skipped and reported in [GeoPackageContent.skipped]; a file that is not a usable GeoPackage throws.
     */
    fun read(file: File): GeoPackageContent {
        if (!file.isFile) throw GeoPackageException("The file doesn't exist.")
        try {
            connect(file, readOnly = true).use { conn ->
                checkIsOurLayer(conn)
                return readRows(conn)
            }
        } catch (e: GeoPackageException) {
            throw e
        } catch (e: Exception) {
            throw GeoPackageException("This is not a readable GeoPackage: ${e.message}", e)
        }
    }

    private fun checkIsOurLayer(conn: Connection) {
        val isGpkg = conn.createStatement().use { st ->
            st.executeQuery("PRAGMA application_id").use { it.next() && it.getInt(1) == APPLICATION_ID }
        }
        if (!isGpkg) throw GeoPackageException("This file is not a GeoPackage.")
        val hasLayer = conn.prepareStatement("SELECT 1 FROM gpkg_contents WHERE table_name = ? AND data_type = 'features'").use { ps ->
            ps.setString(1, TABLE)
            ps.executeQuery().use { it.next() }
        }
        if (!hasLayer) throw GeoPackageException("This GeoPackage has no \"$TABLE\" layer. Only GeoPackages exported by this app can be imported.")
        val columns = conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(\"$TABLE\")").use { rs -> buildSet { while (rs.next()) add(rs.getString("name")) } }
        }
        val missing = listOf("geom", "asset_id", "plugin_id", "properties_json").filter { it !in columns }
        if (missing.isNotEmpty()) throw GeoPackageException("The \"$TABLE\" layer is missing: ${missing.joinToString()}.")
    }

    private fun readRows(conn: Connection): GeoPackageContent {
        val assets = ArrayList<Asset>()
        val skipped = ArrayList<String>()
        val seen = HashSet<String>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT fid, geom, asset_id, plugin_id, \"position\", status, severity, captured_at, properties_json FROM \"$TABLE\" ORDER BY \"position\", fid").use { rs ->
                while (rs.next()) {
                    val fid = rs.getLong("fid")
                    val id = rs.getString("asset_id")?.takeIf { it.isNotBlank() }
                    val plugin = rs.getString("plugin_id")?.takeIf { it.isNotBlank() }
                    val props = rs.getString("properties_json")?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
                    when {
                        id == null || plugin == null -> { skipped += "row $fid: no id or plugin"; continue }
                        props == null -> { skipped += "row $fid ($id): damaged properties"; continue }
                        !seen.add(id) -> { skipped += "row $fid ($id): duplicate id in file"; continue }
                    }
                    val geom = rs.getBytes("geom")
                    val point = if (geom == null) Point(0.0, 0.0) else parsePoint(geom)
                    if (point == null) { skipped += "row $fid ($id): unreadable or out-of-range position"; continue }
                    assets += Asset(
                        id = id!!, pluginId = plugin!!, position = rs.getInt("position"),
                        status = runCatching { RecordStatus.valueOf(rs.getString("status")) }.getOrDefault(RecordStatus.DRAFT),
                        latitude = point.lat, longitude = point.lon, properties = props!!,
                        capturedAt = rs.getString("captured_at")?.let { runCatching { Instant.parse(it) }.getOrNull() },
                        severity = Severity.fromName(rs.getString("severity")),
                    )
                }
            }
        }
        return GeoPackageContent(assets, skipped)
    }

    internal data class Point(val lat: Double, val lon: Double)

    /** The point in a GeoPackage geometry blob, or null when it is not a valid, in-range point. Never throws. */
    internal fun parsePoint(blob: ByteArray): Point? = try {
        val header = ByteBuffer.wrap(blob)
        if (blob.size < 8 || blob[0] != 'G'.code.toByte() || blob[1] != 'P'.code.toByte() || blob[2] != 0.toByte()) null
        else {
            val flags = blob[3].toInt() and 0xFF
            header.order(if (flags and 0x01 != 0) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            val envelopeBytes = when ((flags shr 1) and 0x07) { 0 -> 0; 1 -> 32; 2, 3 -> 48; 4 -> 64; else -> -1 }
            val empty = flags and 0x10 != 0
            if (envelopeBytes < 0 || empty) null
            else {
                val wkb = ByteBuffer.wrap(blob, 8 + envelopeBytes, blob.size - 8 - envelopeBytes)
                wkb.order(if (wkb.get().toInt() == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
                val type = wkb.getInt()
                val x = wkb.getDouble()
                val y = wkb.getDouble()
                // Point, or its Z / M / ZM variants (extra ordinates are ignored).
                if (type !in setOf(1, 1001, 2001, 3001)) null
                else if (!x.isFinite() || !y.isFinite() || y !in -90.0..90.0 || x !in -180.0..180.0) null
                else Point(lat = y, lon = x)
            }
        }
    } catch (e: Exception) {
        null // truncated or otherwise malformed
    }

    private fun connect(file: File, readOnly: Boolean): Connection {
        val props = Properties()
        if (readOnly) props.setProperty("open_mode", "1") // SQLITE_OPEN_READONLY
        return DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}", props)
    }
}
