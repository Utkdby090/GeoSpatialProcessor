package com.geospatial.processing.data.geopackage

import com.geospatial.processing.domain.model.Asset
import com.geospatial.processing.domain.model.RecordStatus
import com.geospatial.processing.domain.model.Severity
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeoPackageTest {

    private val dir: File = Files.createTempDirectory("gpkg").toFile()
    private val file = File(dir, "out.gpkg")

    private fun asset(
        n: Int, lat: Double = 12.0 + n / 100.0, lon: Double = 77.0 + n / 100.0,
        props: Map<String, String> = mapOf("Tower No." to "T$n", "Line Name" to "Line A"),
    ) = Asset(
        id = "id-$n", pluginId = "com.geo.telecom", position = n, status = RecordStatus.READY, latitude = lat, longitude = lon,
        properties = props, capturedAt = Instant.parse("2026-03-04T05:06:07Z"), severity = Severity.HIGH,
    )

    private fun <T> sql(f: File = file, block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${f.absolutePath}").use(block)

    private fun query(f: File = file, sql: String): List<List<Any?>> = sql(f) { c ->
        c.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val n = rs.metaData.columnCount
                buildList { while (rs.next()) add((1..n).map { rs.getObject(it) }) }
            }
        }
    }

    // --- round trip ---------------------------------------------------------------------------------

    @Test
    fun `every field survives a round trip`() {
        val original = listOf(asset(1), asset(2, props = mapOf("Tower No." to "T2", "load.CKT3" to "120A", "Note" to "")))

        assertEquals(2, GeoPackage.write(original, file))
        val read = GeoPackage.read(file)

        assertEquals(emptyList(), read.skipped)
        assertEquals(original, read.assets)
    }

    @Test
    fun `an asset without position gets an empty geometry and comes back at 0,0`() {
        val located = asset(1)
        val unlocated = asset(2, lat = 0.0, lon = 0.0)
        GeoPackage.write(listOf(located, unlocated), file)

        assertEquals(listOf(false, true), query(sql = "SELECT geom IS NULL FROM assets ORDER BY fid").map { (it[0] as Number).toInt() == 1 })
        val bbox = query(sql = "SELECT min_x, min_y, max_x, max_y FROM gpkg_contents").single()
        assertEquals(listOf(located.longitude, located.latitude, located.longitude, located.latitude), bbox.map { (it as Number).toDouble() })
        assertEquals(listOf(located, unlocated), GeoPackage.read(file).assets)
    }

    @Test
    fun `an empty project writes a valid file that reads back empty`() {
        assertEquals(0, GeoPackage.write(emptyList(), file))

        assertEquals(GeoPackageContent(emptyList(), emptyList()), GeoPackage.read(file))
        assertNull(query(sql = "SELECT min_x FROM gpkg_contents").single()[0])
    }

    @Test
    fun `unicode, quotes and long values are kept`() {
        val nasty = mapOf(
            "Tower \"No\"; DROP TABLE assets;--" to "O'Brien \"quoted\" – ताज 日本語 \n newline",
            "long" to "x".repeat(200_000),
        )
        GeoPackage.write(listOf(asset(1, props = nasty)), file)

        assertEquals(nasty, GeoPackage.read(file).assets.single().properties)
        assertEquals(1, query(sql = "SELECT count(*) FROM assets").single()[0].let { (it as Number).toInt() })
    }

    // --- standard structure -------------------------------------------------------------------------

    @Test
    fun `the file carries the GeoPackage identification and mandatory tables`() {
        GeoPackage.write(listOf(asset(1)), file, layerTitle = "Line A towers")

        assertEquals(0x47504B47, (query(sql = "PRAGMA application_id").single()[0] as Number).toInt())
        assertEquals(10300, (query(sql = "PRAGMA user_version").single()[0] as Number).toInt())
        assertEquals(listOf(-1, 0, 4326), query(sql = "SELECT srs_id FROM gpkg_spatial_ref_sys ORDER BY srs_id").map { (it[0] as Number).toInt() })
        assertEquals(listOf("assets", "features", "Line A towers", 4326), query(sql = "SELECT table_name, data_type, identifier, srs_id FROM gpkg_contents").single().map { (it as? Number)?.toInt() ?: it })
        assertEquals(listOf("assets", "geom", "POINT", 4326), query(sql = "SELECT table_name, column_name, geometry_type_name, srs_id FROM gpkg_geometry_columns").single().map { (it as? Number)?.toInt() ?: it })
        assertEquals("ok", query(sql = "PRAGMA integrity_check").single()[0])
        assertTrue(query(sql = "PRAGMA foreign_key_check").isEmpty())
        assertTrue(query(sql = "SELECT last_change FROM gpkg_contents").single()[0].toString().matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z""")))
    }

    @Test
    fun `the geometry is a standard point blob, x is longitude and y is latitude`() {
        GeoPackage.write(listOf(asset(1, lat = 12.5, lon = 77.25)), file)

        val blob = query(sql = "SELECT geom FROM assets").single()[0] as ByteArray
        assertEquals(29, blob.size)
        assertEquals("GP", String(blob, 0, 2))
        val b = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(4326, b.getInt(4))
        assertEquals(1, b.getInt(9))
        assertEquals(77.25, b.getDouble(13))
        assertEquals(12.5, b.getDouble(21))
    }

    @Test
    fun `properties also become readable columns for GIS tools, with safe unique names`() {
        val props = mapOf("Tower No." to "T1", "Tower No" to "dup", "status" to "clash", "9 lives" to "x", "!!!" to "y", "load.CKT3" to "120A")
        GeoPackage.write(listOf(asset(1, props = props)), file)

        val row = query(sql = "SELECT tower_no, tower_no_2, status_2, p_9_lives, p_, load_ckt3 FROM assets").single()
        assertEquals(listOf("T1", "dup", "clash", "x", "y", "120A"), row)
        assertEquals(props, GeoPackage.read(file).assets.single().properties) // json stays authoritative
    }

    @Test
    fun `a huge number of distinct properties is capped in columns but not in the data`() {
        val props = (1..1000).associate { "key$it" to "v$it" }
        GeoPackage.write(listOf(asset(1, props = props)), file)

        val columns = query(sql = "SELECT count(*) FROM pragma_table_info('assets')").single()[0] as Number
        assertTrue(columns.toInt() < 400)
        assertEquals(props, GeoPackage.read(file).assets.single().properties)
    }

    @Test
    fun `thousands of assets are written and read back in order`() {
        val many = (1..5000).map { asset(it) }
        var last = 0
        GeoPackage.write(many, file) { current, total -> last = current; assertEquals(5000, total) }

        assertEquals(5000, last)
        assertEquals(many, GeoPackage.read(file).assets)
    }

    // --- writing safely -----------------------------------------------------------------------------

    @Test
    fun `writing replaces an existing file and leaves no temporary files`() {
        file.writeText("old content")

        GeoPackage.write(listOf(asset(1)), file)
        GeoPackage.write(listOf(asset(1), asset(2)), file)

        assertEquals(2, GeoPackage.read(file).assets.size)
        assertEquals(listOf("out.gpkg"), dir.list()!!.toList())
    }

    @Test
    fun `a failed write leaves the destination untouched and no temporary files`() {
        val blocked = File(dir, "blocked.gpkg").apply { mkdirs(); File(this, "keep.txt").writeText("keep") }

        assertFailsWith<GeoPackageException> { GeoPackage.write(listOf(asset(1)), blocked) }

        assertEquals("keep", File(blocked, "keep.txt").readText())
        assertEquals(listOf("blocked.gpkg"), dir.list()!!.toList())
    }

    @Test
    fun `a missing destination folder is reported, not created`() {
        assertFailsWith<GeoPackageException> { GeoPackage.write(listOf(asset(1)), File(dir, "nope/out.gpkg")) }
        assertFalse(File(dir, "nope").exists())
    }

    // --- reading safely -----------------------------------------------------------------------------

    @Test
    fun `reading never modifies the file`() {
        GeoPackage.write(listOf(asset(1)), file)
        val before = file.readBytes()

        GeoPackage.read(file)

        assertContentEquals(before, file.readBytes())
        assertEquals(listOf("out.gpkg"), dir.list()!!.toList(), "no journal or temp files")
    }

    @Test
    fun `files that are not usable GeoPackages are refused with a message`() {
        assertFailsWith<GeoPackageException> { GeoPackage.read(File(dir, "missing.gpkg")) }
        assertFailsWith<GeoPackageException> { GeoPackage.read(File(dir, "text.gpkg").apply { writeText("hello, not sqlite") }) }
        assertFailsWith<GeoPackageException> { GeoPackage.read(File(dir, "empty.gpkg").apply { writeBytes(ByteArray(0)) }) }
        assertFailsWith<GeoPackageException> { GeoPackage.read(dir) }

        // Plain SQLite without the GeoPackage identification.
        val plain = File(dir, "plain.gpkg")
        sql(plain) { it.createStatement().use { st -> st.execute("CREATE TABLE assets (x)") } }
        assertEquals("This file is not a GeoPackage.", assertFailsWith<GeoPackageException> { GeoPackage.read(plain) }.message)
    }

    @Test
    fun `a GeoPackage from another tool without our layer says so`() {
        GeoPackage.write(listOf(asset(1)), file)
        sql { c -> c.createStatement().use { st ->
            st.execute("DELETE FROM gpkg_geometry_columns"); st.execute("DELETE FROM gpkg_contents")
        } }

        assertTrue(assertFailsWith<GeoPackageException> { GeoPackage.read(file) }.message!!.contains("no \"assets\" layer"))
    }

    @Test
    fun `a layer missing required columns is refused`() {
        GeoPackage.write(listOf(asset(1)), file)
        sql { c -> c.createStatement().use { st -> st.execute("ALTER TABLE assets DROP COLUMN properties_json") } }

        assertTrue(assertFailsWith<GeoPackageException> { GeoPackage.read(file) }.message!!.contains("properties_json"))
    }

    @Test
    fun `damaged rows are skipped with a reason and the rest still imports`() {
        GeoPackage.write((1..6).map { asset(it) }, file)
        sql { c -> c.createStatement().use { st ->
            st.execute("UPDATE assets SET properties_json = '{not json' WHERE asset_id = 'id-2'")
            st.execute("UPDATE assets SET geom = x'4750' WHERE asset_id = 'id-3'")                 // truncated
            st.execute("UPDATE assets SET asset_id = '   ' WHERE asset_id = 'id-4'")
            st.execute("UPDATE assets SET status = 'WAT', severity = 'WAT', captured_at = 'yesterday' WHERE asset_id = 'id-5'")
        } }

        val read = runCatching { GeoPackage.read(file) }.getOrThrow()

        val ids = read.assets.map { it.id }
        assertEquals(listOf("id-1", "id-5", "id-6"), ids)
        val tolerant = read.assets.single { it.id == "id-5" }
        assertEquals(RecordStatus.DRAFT, tolerant.status)
        assertEquals(Severity.NONE, tolerant.severity)
        assertNull(tolerant.capturedAt)
        assertEquals(3, read.skipped.size, read.skipped.toString())
    }

    // --- geometry parsing ---------------------------------------------------------------------------

    private fun blob(flags: Int = 0x01, wkbOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN, type: Int = 1, x: Double = 77.0, y: Double = 12.0, envelope: Int = 0): ByteArray {
        val b = ByteBuffer.allocate(8 + envelope + 21 + 16).order(ByteOrder.LITTLE_ENDIAN)
        b.put('G'.code.toByte()).put('P'.code.toByte()).put(0).put(flags.toByte()).putInt(4326)
        b.position(8 + envelope)
        b.put(if (wkbOrder == ByteOrder.LITTLE_ENDIAN) 1 else 0)
        b.order(wkbOrder).putInt(type).putDouble(x).putDouble(y)
        return b.array().copyOf(b.position())
    }

    @Test
    fun `point blobs in the variants other tools write are understood`() {
        val expected = GeoPackage.Point(lat = 12.0, lon = 77.0)
        assertEquals(expected, GeoPackage.parsePoint(blob()))
        assertEquals(expected, GeoPackage.parsePoint(blob(wkbOrder = ByteOrder.BIG_ENDIAN)))
        assertEquals(expected, GeoPackage.parsePoint(blob(flags = 0x01 or (1 shl 1), envelope = 32)), "with an XY envelope")
        assertEquals(expected, GeoPackage.parsePoint(blob(type = 1001)), "PointZ")
        assertEquals(expected, GeoPackage.parsePoint(blob(type = 3001)), "PointZM")
    }

    @Test
    fun `malformed point blobs give null and never throw`() {
        val good = blob()
        assertNull(GeoPackage.parsePoint(ByteArray(0)))
        assertNull(GeoPackage.parsePoint(good.copyOf(7)))
        assertNull(GeoPackage.parsePoint(good.copyOf(20)), "truncated WKB")
        assertNull(GeoPackage.parsePoint(good.copyOf().also { it[0] = 'X'.code.toByte() }), "bad magic")
        assertNull(GeoPackage.parsePoint(good.copyOf().also { it[2] = 1 }), "unknown version")
        assertNull(GeoPackage.parsePoint(blob(flags = 0x01 or 0x10)), "flagged empty")
        assertNull(GeoPackage.parsePoint(blob(flags = 0x01 or (7 shl 1))), "invalid envelope code")
        assertNull(GeoPackage.parsePoint(blob(flags = 0x01 or (4 shl 1), envelope = 8)), "envelope runs past the data")
        assertNull(GeoPackage.parsePoint(blob(type = 3)), "a polygon")
        assertNull(GeoPackage.parsePoint(blob(x = Double.NaN)))
        assertNull(GeoPackage.parsePoint(blob(y = Double.POSITIVE_INFINITY)))
        assertNull(GeoPackage.parsePoint(blob(y = 91.0)), "latitude out of range")
        assertNull(GeoPackage.parsePoint(blob(x = -180.5)), "longitude out of range")
        assertNotNull(GeoPackage.parsePoint(blob(x = 180.0, y = -90.0)), "the exact limits are valid")
    }

    @Test
    fun `random garbage never throws`() {
        val random = java.util.Random(42)
        repeat(2000) {
            val bytes = ByteArray(random.nextInt(60)).also(random::nextBytes)
            if (bytes.size > 3 && it % 2 == 0) { bytes[0] = 'G'.code.toByte(); bytes[1] = 'P'.code.toByte(); bytes[2] = 0 }
            GeoPackage.parsePoint(bytes) // must return, not throw
        }
    }
}
