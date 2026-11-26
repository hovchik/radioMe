package com.radiome.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TransferEntity::class, ChunkEntity::class], version = 1, exportSchema = false)
abstract class RadioDatabase : RoomDatabase() {
    abstract fun transfers(): TransferDao

    companion object {
        fun create(context: Context): RadioDatabase =
            Room.databaseBuilder(context.applicationContext, RadioDatabase::class.java, "radiome.db").build()

        fun inMemory(context: Context): RadioDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, RadioDatabase::class.java).allowMainThreadQueries().build()
    }
}
