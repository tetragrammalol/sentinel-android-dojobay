package com.samourai.sentinel.data.repository

import com.samourai.sentinel.data.db.entity.LabelEntry
import com.samourai.sentinel.data.db.entity.LabelType
import com.samourai.sentinel.data.db.entity.UtxoLabel
import com.samourai.sentinel.data.whirlpool.WhirlpoolConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Hand edits saved while the origin was still auto: are marked manual
 * once. Exact auto text stays auto. Hashes below are synthetic.
 */
class AutoOriginMigrationTest {

    @Test
    fun `a non matching auto label becomes manual and an exact one stays`() {
        val keep = "KEEP"
        val entries = listOf(
            entry("edited", "savings", "auto:whirlpool:v1"),
            entry("padded", "$keep ", "auto:whirlpool:v1"),
            entry("blank", "", "auto:whirlpool:v1"),
            entry("kept", keep, "auto:whirlpool:v1"),
            entry("manual", "savings", null),
            entry("foreign", "savings", "imported"),
            entry("addr", "savings", "auto:whirlpool:v1", type = LabelType.ADDR.wire),
        )
        val utxos = listOf(
            utxo("u-edited", "note", "auto:x"),
            utxo("u-kept", keep, "auto:whirlpool:v1"),
        )
        val plan = planAutoOriginDemotion(entries, utxos, setOf(keep))
        assertEquals(listOf("edited", "padded", "blank"), plan.entries.map { it.ref })
        plan.entries.forEach { row ->
            assertNull(row.origin)
            assertEquals(1L, row.createdAt)
            assertEquals(2L, row.updatedAt)
        }
        assertEquals("savings", plan.entries[0].label)
        assertEquals("", plan.entries[2].label)
        assertEquals(listOf("u-edited"), plan.utxos.map { it.txid })
        assertNull(plan.utxos[0].origin)
        assertEquals("note", plan.utxos[0].label)
    }

    @Test
    fun `default auto texts are the labeller strings`() {
        val labels = WhirlpoolConfig.DEFAULT.labels
        assertEquals(
            setOf(labels.tx0, labels.firstMix, labels.badbank),
            labels.autoWritten(),
        )
        val plan = planAutoOriginDemotion(
            listOf(
                entry("tx0", labels.tx0, "auto:whirlpool:v1"),
                entry("mix", labels.firstMix, "auto:whirlpool:v1"),
                entry("other", labels.tx0 + " note", "auto:whirlpool:v1"),
            ),
            listOf(utxo("bb", labels.badbank, "auto:whirlpool:v1")),
        )
        assertEquals(listOf("other"), plan.entries.map { it.ref })
        assertNull(plan.entries[0].origin)
        assertTrue(plan.utxos.isEmpty())
    }

    @Test
    fun `the migration runs once`() = runBlocking {
        var ran = false
        var loads = 0
        var applies = 0
        val rows = mutableListOf(entry("edited", "savings", "auto:whirlpool:v1"))
        suspend fun go() = runAutoOriginMigration(
            alreadyRan = { ran },
            markRan = { ran = true },
            loadEntries = { loads++; rows.toList() },
            loadUtxos = { emptyList() },
            apply = { plan ->
                applies++
                plan.entries.forEach { updated ->
                    val i = rows.indexOfFirst { it.ref == updated.ref }
                    rows[i] = updated
                }
            },
        )
        go()
        assertEquals(1, loads)
        assertEquals(1, applies)
        assertTrue(ran)
        assertNull(rows[0].origin)
        rows.add(entry("later", "savings", "auto:whirlpool:v1"))
        go()
        assertEquals(1, loads)
        assertEquals(1, applies)
        assertEquals("auto:whirlpool:v1", rows[1].origin)
    }

    @Test
    fun `a failed run does not stick the flag`() {
        var marked = false
        try {
            runBlocking {
                runAutoOriginMigration(
                    alreadyRan = { false },
                    markRan = { marked = true },
                    loadEntries = { error("boom") },
                    loadUtxos = { emptyList() },
                    apply = { _ -> },
                )
            }
            fail("expected the load to throw")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
            assertFalse(marked)
        }
    }

    @Test
    fun `auto label text is taken from the config`() {
        val labels = WhirlpoolConfig.DEFAULT.labels
        val migration = source(
            "src/main/java/com/samourai/sentinel/data/repository/AutoOriginMigration.kt",
            "app/src/main/java/com/samourai/sentinel/data/repository/AutoOriginMigration.kt",
        )
        val detector = source(
            "src/main/java/com/samourai/sentinel/data/whirlpool/WhirlpoolDetector.kt",
            "app/src/main/java/com/samourai/sentinel/data/whirlpool/WhirlpoolDetector.kt",
        )
        for (text in labels.autoWritten()) {
            assertFalse(migration.contains("\"$text\""))
        }
        val fnAt = detector.indexOf("fun autoWritten()")
        val fnEnd = detector.indexOf("}", fnAt)
        assertTrue(fnAt >= 0 && fnEnd > fnAt)
        assertFalse(detector.substring(fnAt, fnEnd).contains("\""))
        val repo = source(
            "src/main/java/com/samourai/sentinel/data/repository/LabelRepository.kt",
            "app/src/main/java/com/samourai/sentinel/data/repository/LabelRepository.kt",
        )
        val app = source(
            "src/main/java/com/samourai/sentinel/SentinelApplication.kt",
            "app/src/main/java/com/samourai/sentinel/SentinelApplication.kt",
        )
        val prefs = source(
            "src/main/java/com/samourai/sentinel/ui/utils/PrefsUtil.kt",
            "app/src/main/java/com/samourai/sentinel/ui/utils/PrefsUtil.kt",
        )
        assertTrue(repo.contains("migrateHandEditedAutoOrigins()"))
        assertTrue(repo.contains("runAutoOriginMigration("))
        val getTx = repo.substring(
            repo.indexOf("suspend fun getTxLabel"),
            repo.indexOf("suspend fun setAutoTxLabel"),
        )
        assertTrue(getTx.contains("migrateHandEditedAutoOrigins()"))
        val getUtxo = repo.substring(
            repo.indexOf("suspend fun getUtxoLabel"),
            repo.indexOf("suspend fun setAutoUtxoLabel"),
        )
        assertTrue(getUtxo.contains("migrateHandEditedAutoOrigins()"))
        assertTrue(app.contains("scheduleAutoOriginMigration()"))
        assertTrue(app.contains("dataBaseScope.launch"))
        assertTrue(prefs.contains("autoOriginHandEditsMigrated"))
        assertTrue(prefs.contains("\"autoOriginHandEditsMigrated\""))
    }

    private fun entry(
        ref: String,
        label: String,
        origin: String?,
        type: String = LabelType.TX.wire,
    ) = LabelEntry(
        network = "mainnet",
        type = type,
        ref = ref,
        label = label,
        origin = origin,
        createdAt = 1L,
        updatedAt = 2L,
    )

    private fun utxo(txid: String, label: String, origin: String?) = UtxoLabel(
        network = "mainnet",
        txid = txid,
        vout = 0,
        label = label,
        origin = origin,
        createdAt = 1L,
        updatedAt = 2L,
    )

    private fun source(vararg relative: String): String {
        val file = relative.map { File(it) }.firstOrNull { it.isFile }
            ?: error("source not found from ${File(".").absolutePath}: ${relative.toList()}")
        return file.readText()
    }
}
