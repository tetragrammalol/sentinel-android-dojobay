package com.samourai.sentinel.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.TxInputConverter
import com.samourai.sentinel.data.Utxo
import com.samourai.sentinel.data.db.dao.TxDao
import com.samourai.sentinel.data.db.dao.UtxoDao
import com.samourai.sentinel.data.db.dao.UtxoLabelDao
import com.samourai.sentinel.data.db.entity.UtxoLabel
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Tx::class, Utxo::class, UtxoLabel::class], version = 2, exportSchema = false)
@TypeConverters(TxInputConverter::class)
abstract class SentinelRoomDb : RoomDatabase() {

    abstract fun txDao(): TxDao
    abstract fun utxoDao(): UtxoDao
    abstract fun utxoLabelDao(): UtxoLabelDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `utxo_labels` (" +
                            "`network` TEXT NOT NULL, " +
                            "`txid` TEXT NOT NULL, " +
                            "`vout` INTEGER NOT NULL, " +
                            "`label` TEXT NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`network`, `txid`, `vout`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_utxo_labels_network` " +
                            "ON `utxo_labels` (`network`)"
                )
            }
        }

        @Volatile
        private var INSTANCE: SentinelRoomDb? = null
        fun getDatabase(context: Context): SentinelRoomDb {
            val tempInstance = INSTANCE
            if (tempInstance != null) {
                return tempInstance
            }
            synchronized(this) {
                val instance = Room.databaseBuilder(
                        context.applicationContext,
                        SentinelRoomDb::class.java,
                        "sentinel_database"
                )
                        // v1 -> v2: add utxo_labels. CREATE-only migration so
                        // existing user data (utxos, txs) is never touched.
                        // Destructive fallback stays disabled on purpose.
                        .addMigrations(MIGRATION_1_2)
                        .build()
                INSTANCE = instance
                return instance
            }
        }
    }
}