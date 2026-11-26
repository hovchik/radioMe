package com.radiome.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TransferEntity::class, ChunkEntity::class], version = 2, exportSchema = false)
abstract class RadioDatabase : RoomDatabase() {
    abstract fun transfers(): TransferDao

    companion object {
        /** v2: callsign of the other station on each transfer. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transfers ADD COLUMN peer TEXT")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2)

        fun create(context: Context): RadioDatabase =
            Room.databaseBuilder(context.applicationContext, RadioDatabase::class.java, "radiome.db")
                .addMigrations(*MIGRATIONS)
                .build()

        fun inMemory(context: Context): RadioDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, RadioDatabase::class.java).allowMainThreadQueries().build()
    }
}
