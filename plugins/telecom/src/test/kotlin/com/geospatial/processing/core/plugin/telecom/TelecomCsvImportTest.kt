package com.geospatial.processing.core.plugin.telecom

import com.geospatial.processing.core.plugin.telecom.TelecomKeys as K
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The survey CSV as it arrives in real life: Excel exports, other locales, other encodings, damaged and odd files. */
class TelecomCsvImportTest {

    private val dir: File = Files.createTempDirectory("csv").toFile()
    private val csv = TelecomCsvImport()

    private fun file(name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }
    private fun file(name: String, text: String, charset: Charset = Charsets.UTF_8) = file(name, text.toByteArray(charset))

    private val header = "Tower No.,Line Name,CKT,Lat.,Long."

    // ---- encodings ----

    @Test
    fun `a UTF-8 byte-order mark does not corrupt the first column`() {
        // Excel's "CSV UTF-8" starts the file with EF BB BF; unhandled, the first header becomes "﻿Tower No." and every row is dropped.
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "$header\n76_0,Line A,1,12.5,77.5\n".toByteArray()

        val result = csv.read(file("bom.csv", bytes))

        assertNull(result.problem)
        assertEquals(1, result.drafts.size)
        assertEquals("76_0", result.drafts[0].properties[K.TOWER_NUMBER])
        assertTrue(result.drafts[0].properties.keys.none { it.contains('﻿') })
    }

    @Test
    fun `UTF-16 files with a byte-order mark are read`() {
        val text = "$header\n76_0,Line A,1,12.5,77.5\n"
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)

        for ((name, bytes) in listOf("le" to le, "be" to be)) {
            val result = csv.read(file("$name.csv", bytes))
            assertEquals(1, result.drafts.size, name)
            assertEquals(12.5, result.drafts[0].latitude, name)
        }
    }

    @Test
    fun `an ANSI file from Excel keeps its degree signs, so degrees-minutes-seconds positions survive`() {
        val ansi = Charset.forName("windows-1252")
        val text = "Tower No.,Line Name,Lat.,Long.\n76_0,Line A,\"27°30'00\"\"N\",\"73°54'00\"\"E\"\n"
        val bytes = text.toByteArray(ansi)
        assertTrue(bytes.contains(0xB0.toByte()), "the fixture really is ANSI (° is one byte 0xB0)")

        val result = csv.read(file("ansi.csv", bytes))

        assertEquals(1, result.drafts.size)
        assertEquals(27.5, result.drafts[0].latitude, 1e-9)
        assertEquals(73.9, result.drafts[0].longitude, 1e-9)
    }

    @Test
    fun `accented names in ANSI and in UTF-8 both survive`() {
        val text = "$header\nÄ-1,Línea Ñandú,1,12.5,77.5\n"
        for (charset in listOf(Charsets.UTF_8, Charset.forName("windows-1252"))) {
            val d = csv.read(file("acc-${charset.name()}.csv", text, charset)).drafts.single()
            assertEquals("Ä-1", d.properties[K.TOWER_NUMBER], charset.name())
            assertEquals("Línea Ñandú", d.properties[K.LINE_NAME], charset.name())
        }
    }

    @Test
    fun `decode picks the encoding from the bytes`() {
        assertEquals("°", TelecomCsvImport.decode(byteArrayOf(0xB0.toByte())), "invalid UTF-8 falls back to ANSI")
        assertEquals("°", TelecomCsvImport.decode("°".toByteArray(Charsets.UTF_8)))
        assertEquals("a", TelecomCsvImport.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'a'.code.toByte())))
        assertEquals("", TelecomCsvImport.decode(ByteArray(0)))
    }

    // ---- delimiters ----

    @Test
    fun `semicolon separated files, the Excel default in many countries, are read`() {
        val text = "Tower No.;Line Name;CKT;Lat.;Long.\n76_0;Line A;1;27,5123;73,9\n"

        val d = csv.read(file("semi.csv", text)).drafts.single()

        assertEquals("76_0", d.properties[K.TOWER_NUMBER])
        assertEquals(27.5123, d.latitude, 1e-9, "decimal comma")
        assertEquals(73.9, d.longitude, 1e-9)
    }

    @Test
    fun `tab separated files are read`() {
        val d = csv.read(file("tab.csv", "Tower No.\tLine Name\tLat.\tLong.\n76_0\tLine A\t12.5\t77.5\n")).drafts.single()
        assertEquals(12.5, d.latitude)
    }

    @Test
    fun `the delimiter is judged on the header, ignoring quoted separators`() {
        assertEquals(',', TelecomCsvImport.detectDelimiter("a,b,c\n1;2;3;4;5"))
        assertEquals(';', TelecomCsvImport.detectDelimiter("a;b;c\n1,2,3,4,5"))
        assertEquals(',', TelecomCsvImport.detectDelimiter("\"a;b;c\",d,e"), "semicolons inside quotes do not count")
        assertEquals(',', TelecomCsvImport.detectDelimiter("single column"))
        assertEquals(',', TelecomCsvImport.detectDelimiter(""))
        assertEquals(',', TelecomCsvImport.detectDelimiter("a,b;c"), "a tie goes to the comma")
        assertEquals(';', TelecomCsvImport.detectDelimiter("\n\na;b;c"), "leading blank lines are skipped")
    }

    // ---- structure ----

    @Test
    fun `empty header cells after the last column, as Excel leaves them, do not break the import`() {
        val text = "Tower No.,Line Name,CKT,Lat.,Long.,,\n76_0,Line A,1,12.5,77.5,,\n"

        val result = csv.read(file("trailing.csv", text))

        assertNull(result.problem)
        assertEquals(1, result.drafts.size)
        assertTrue(result.drafts[0].properties.keys.none { it == K.LOAD_PREFIX }, "no load column with an empty name")
    }

    @Test
    fun `rows of empty cells at the end of a sheet are ignored and not counted as skipped`() {
        val text = "$header\n76_0,Line A,1,12.5,77.5\n,,,,\n,,,,\n\n   \n"

        val result = csv.read(file("blank.csv", text))

        assertEquals(1, result.drafts.size)
        assertEquals(0, result.skippedRows)
    }

    @Test
    fun `rows without a tower number or line name are counted and named by row number`() {
        val text = "$header\n76_0,Line A,1,12.5,77.5\n,Line A,1,12.5,77.5\n77_0,,1,12.5,77.5\n78_0,Line A,1,12.5,77.5\n"

        val result = csv.read(file("skip.csv", text))

        assertEquals(listOf("76_0", "78_0"), result.drafts.map { it.properties[K.TOWER_NUMBER] })
        assertEquals(2, result.skippedRows)
        assertContains(result.skippedDetails[0], "row 3")
        assertContains(result.skippedDetails[1], "row 4")
        assertNull(result.problem)
    }

    @Test
    fun `only the first few skipped rows are described, all are counted`() {
        val rows = (1..40).joinToString("") { ",Line A,1,0,0\n" }

        val result = csv.read(file("many-skips.csv", "$header\n$rows"))

        assertEquals(40, result.skippedRows)
        assertEquals(10, result.skippedDetails.size)
    }

    @Test
    fun `short rows and rows with extra cells are tolerated`() {
        val text = "$header,Load CKT1\n76_0,Line A\n77_0,Line A,1,12.5,77.5,120A,unexpected,extra\n"

        val drafts = csv.read(file("ragged.csv", text)).drafts

        assertEquals(2, drafts.size)
        assertEquals(0.0, drafts[0].latitude, "missing position cells mean no position")
        assertEquals("120A", drafts[1].properties[K.LOAD_PREFIX + "Load CKT1"])
    }

    @Test
    fun `quoted cells with commas, quotes and line breaks are kept whole`() {
        val text = "$header,Fault Description\n76_0,\"Line A, north\",1,12.5,77.5,\"Hot \"\"joint\"\",\nsecond line\"\n"

        val d = csv.read(file("quoted.csv", text)).drafts.single()

        assertEquals("Line A, north", d.properties[K.LINE_NAME])
        assertEquals("Hot \"joint\",\nsecond line", d.properties[K.FAULT_DESCRIPTION])
    }

    @Test
    fun `headers match whatever their case, and cell values are trimmed`() {
        val text = "  TOWER NO. , line name ,ckt,LAT.,Long.\n  76_0  ,  Line A  , 1 , 12.5 , 77.5 \n"

        val d = csv.read(file("case.csv", text)).drafts.single()

        assertEquals("76_0", d.properties[K.TOWER_NUMBER])
        assertEquals("Line A", d.properties[K.LINE_NAME])
        assertEquals("1", d.properties[K.CIRCUIT])
        assertEquals(12.5, d.latitude)
    }

    @Test
    fun `Windows, Unix and old Mac line endings all work`() {
        for ((name, eol) in listOf("crlf" to "\r\n", "lf" to "\n", "cr" to "\r")) {
            val text = "$header$eol" + "76_0,Line A,1,12.5,77.5$eol" + "77_0,Line A,1,12.6,77.6$eol"
            assertEquals(2, csv.read(file("$name.csv", text)).drafts.size, name)
        }
    }

    @Test
    fun `a file without a trailing newline keeps its last row`() {
        assertEquals(2, csv.read(file("nonl.csv", "$header\n76_0,Line A,1,12.5,77.5\n77_0,Line A,1,12.6,77.6")).drafts.size)
    }

    @Test
    fun `duplicate column names do not stop the import`() {
        val text = "Tower No.,Line Name,Load,Load\n76_0,Line A,1,2\n"
        assertEquals(1, csv.read(file("dup.csv", text)).drafts.size)
    }

    @Test
    fun `extra columns become load data, with the order of the file`() {
        val d = csv.read(file("load.csv", "$header,Load CKT2,Load CKT1\n76_0,Line A,1,12.5,77.5,98A,120A\n")).drafts.single()
        assertEquals(listOf(K.LOAD_PREFIX + "Load CKT2", K.LOAD_PREFIX + "Load CKT1"), d.properties.keys.filter { it.startsWith(K.LOAD_PREFIX) })
    }

    // ---- files that cannot be used: the user gets a reason, never an exception or a silent "0 towers" ----

    @Test
    fun `a missing file gives a problem, not an exception`() {
        val result = csv.read(File(dir, "nope.csv"))
        assertTrue(result.drafts.isEmpty())
        assertNotNull(result.problem)
        assertContains(result.problem!!, "could not be read")
    }

    @Test
    fun `an empty file or one with only blanks gives a problem`() {
        assertContains(csv.read(file("empty.csv", ByteArray(0))).problem!!, "empty")
        assertContains(csv.read(file("spaces.csv", "  \n \n")).problem!!, "empty")
    }

    @Test
    fun `a header without data rows gives a problem`() {
        assertContains(csv.read(file("headeronly.csv", "$header\n")).problem!!, "no data rows")
    }

    @Test
    fun `a file without the required columns names what is missing and what was found`() {
        val result = csv.read(file("wrong.csv", "Name,Latitude,Longitude\nA,1,2\n"))

        assertTrue(result.drafts.isEmpty())
        val problem = result.problem!!
        assertContains(problem, "\"Tower No.\"")
        assertContains(problem, "\"Line Name\"")
        assertContains(problem, "Name, Latitude, Longitude")
    }

    @Test
    fun `a file missing only one required column says which`() {
        val problem = csv.read(file("oneless.csv", "Tower No.,CKT\n76_0,1\n")).problem!!
        assertContains(problem, "\"Line Name\"")
        assertTrue("\"Tower No.\"" !in problem)
    }

    @Test
    fun `a binary file gives a problem and never throws`() {
        val result = csv.read(file("binary.csv", ByteArray(2000) { (it * 31).toByte() }))
        assertTrue(result.drafts.isEmpty())
        assertNotNull(result.problem)
    }

    @Test
    fun `parse keeps returning just the drafts`() {
        val f = file("parse.csv", "$header\n76_0,Line A,1,12.5,77.5\n")
        assertEquals(1, csv.parse(f).size)
        assertTrue(csv.parse(File(dir, "gone.csv")).isEmpty())
    }

    // ---- resources and scale ----

    @Test
    fun `the file is released after reading, so it can be deleted or renamed straight away`() {
        val f = file("lock.csv", "$header\n76_0,Line A,1,12.5,77.5\n")
        csv.read(f)

        assertTrue(f.delete(), "Windows refuses to delete a file that is still open")
    }

    @Test
    fun `progress rises monotonically, ends at 1 and is not reported once per row`() {
        val rows = (1..2000).joinToString("") { "T$it,Line A,1,12.5,77.5\n" }
        val seen = mutableListOf<Float>()

        val result = csv.read(file("progress.csv", "$header\n$rows")) { seen += it }

        assertEquals(2000, result.drafts.size)
        assertEquals(seen.sorted(), seen)
        assertEquals(1.0f, seen.last())
        assertTrue(seen.size <= 110, "about one update per percent, was ${seen.size}")
    }

    @Test
    fun `a large survey imports quickly`() {
        val rows = StringBuilder()
        repeat(30_000) { rows.append("T$it,Line ${it % 40},${it % 3},${12 + it / 100000.0},77.5,R,30,tower,normal,${100 + it % 50}A\n") }
        val f = file("big.csv", "Tower No.,Line Name,CKT,Lat.,Long.,Phase,Ambeint Temp.,report_Type,Fault,Load CKT1\n$rows")

        val start = System.nanoTime()
        val result = csv.read(f)
        val ms = (System.nanoTime() - start) / 1_000_000

        assertEquals(30_000, result.drafts.size)
        assertTrue(ms < 10_000, "30,000 rows took $ms ms")
    }

    @Test
    fun `coordinates written as degrees minutes seconds or with hemisphere letters are understood, bad ones mean no position`() {
        val text = "$header\n" +
            "A,L,1,\"12°30'00\"\"N\",\"77°15'00\"\"E\"\n" +
            "B,L,1,not a number,77.5\n" +
            "C,L,1,,\n"

        val drafts = csv.read(file("coords.csv", text)).drafts

        assertEquals(12.5, drafts[0].latitude, 1e-9)
        assertEquals(77.25, drafts[0].longitude, 1e-9)
        assertEquals(0.0, drafts[1].latitude)
        assertEquals(0.0, drafts[1].longitude, "a half-readable pair is dropped rather than half-used")
        assertEquals(0.0, drafts[2].latitude)
    }
}
