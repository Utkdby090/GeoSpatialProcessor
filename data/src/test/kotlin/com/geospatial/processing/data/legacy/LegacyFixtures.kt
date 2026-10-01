package com.geospatial.processing.data.legacy

import java.io.File
import java.sql.DriverManager

/** Builds a project.db the way v2.x wrote it: a `geo_data` table with fixed columns and image BLOBs. */
internal object LegacyFixtures {

    val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x11, 0x22)

    /** Same columns GeoDataTable (IntIdTable "geo_data") created in v2.x. */
    private const val CREATE_GEO_DATA = """
        CREATE TABLE geo_data (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            line_name VARCHAR(255) NOT NULL,
            tower_number VARCHAR(255) DEFAULT '' NOT NULL,
            circuit VARCHAR(255) DEFAULT '' NOT NULL,
            latitude DOUBLE PRECISION NOT NULL,
            longitude DOUBLE PRECISION NOT NULL,
            phase VARCHAR(255) NULL, side VARCHAR(255) NULL, direction VARCHAR(255) NULL,
            captured_date VARCHAR(255) NULL, captured_time VARCHAR(255) NULL,
            humidity VARCHAR(255) DEFAULT '' NOT NULL, emissivity VARCHAR(255) DEFAULT '' NOT NULL,
            ambient_temp VARCHAR(255) DEFAULT '' NOT NULL,
            dynamic_circuits TEXT DEFAULT '{}' NOT NULL,
            fault_desc TEXT DEFAULT '' NOT NULL, fault_temp VARCHAR(255) DEFAULT '' NOT NULL, rise_temp VARCHAR(255) NULL,
            img_thermal BLOB NULL, img_visual BLOB NULL, img_tower BLOB NULL, img_extra BLOB NULL,
            status VARCHAR(255) DEFAULT 'DRAFT' NOT NULL,
            updated_at TEXT NULL,
            report_type VARCHAR(50) DEFAULT 'TOWER' NOT NULL,
            fault_status VARCHAR(50) DEFAULT 'Normal' NOT NULL,
            company_name VARCHAR(255) DEFAULT '' NOT NULL
        )"""

    /**
     * Two towers:
     *  76_0 – every column set, thermal + tower images, location image CLEARED (empty BLOB), load data.
     *  77_0 – minimal row: nullable columns null, no images, status READY.
     */
    fun createLegacyProjectDb(dbFile: File) {
        dbFile.parentFile.mkdirs()
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().use { it.execute(CREATE_GEO_DATA) }
            conn.prepareStatement(
                """INSERT INTO geo_data (line_name, tower_number, circuit, latitude, longitude, phase, side, direction,
                   captured_date, captured_time, humidity, emissivity, ambient_temp, dynamic_circuits, fault_desc,
                   fault_temp, rise_temp, img_thermal, img_visual, img_tower, img_extra, status, report_type,
                   fault_status, company_name) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""
            ).use { st ->
                val full = listOf<Any?>(
                    "Line A", "76_0", "1", 12.5, 77.5, "R", "Left", "North",
                    "2026-01-05", "10:30", "40", "0.95", "31", """{"Load CKT1":"120A","Load CKT2":"98A"}""",
                    "Hot joint", "85", "54", JPEG, ByteArray(0), JPEG, null, "DRAFT", "tower_fault",
                    "Fault", "Acme Grid"
                )
                val minimal = listOf<Any?>(
                    "Line A", "77_0", "", 12.6, 77.6, null, null, null,
                    null, null, "", "", "", "{}",
                    "", "", null, null, null, null, null, "READY", "TOWER",
                    "Normal", ""
                )
                for (row in listOf(full, minimal)) {
                    row.forEachIndexed { i, v -> st.setObject(i + 1, v) }
                    st.executeUpdate()
                }
            }
        }
    }

    fun tables(dbFile: File): Set<String> =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs ->
                buildSet { while (rs.next()) add(rs.getString(1)) }
            }
        }

    fun userVersion(dbFile: File): Int =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) }
        }
}
