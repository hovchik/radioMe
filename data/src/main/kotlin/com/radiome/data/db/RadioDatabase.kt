package com.radiome.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TransferEntity::class, ChunkEntity::class, PositionEntity::class], version = 4, exportSchema = false)
abstract class RadioDatabase : RoomDatabase() {
    abstract fun transfers(): TransferDao
    abstract fun positions(): PositionDao

    companion object {
        /** v2: callsign of the other station on each transfer. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transfers ADD COLUMN peer TEXT")
            }
        }

        /** v3: decrypted GPS positions, and whether a transfer was encrypted. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transfers ADD COLUMN encrypted INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `positions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`callsign` TEXT NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `altitudeM` INTEGER, " +
                        "`accuracyM` INTEGER, `speedMps` REAL, `courseDeg` REAL, `fixTimeSec` INTEGER NOT NULL, " +
                        "`receivedAt` INTEGER NOT NULL, `own` INTEGER NOT NULL, `snrDb` REAL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_positions_callsign` ON `positions` (`callsign`)")
            }
        }

        /** v4: delivery receipts (who confirmed an outgoing transfer). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transfers ADD COLUMN deliveredTo TEXT")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        fun create(context: Context): RadioDatabase =
            Room.databaseBuilder(context.applicationContext, RadioDatabase::class.java, "radiome.db")
                .addMigrations(*MIGRATIONS)
                .build()

        fun inMemory(context: Context): RadioDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, RadioDatabase::class.java).allowMainThreadQueries().build()
    }
}
