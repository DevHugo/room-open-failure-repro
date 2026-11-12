package com.example.roomopenfailure

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import java.util.Collections

/** Records every callback Room invokes. */
class RecordingCallback : RoomDatabase.Callback() {
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun onCreate(connection: SQLiteConnection) {
        events += "onCreate"
    }

    override fun onOpen(connection: SQLiteConnection) {
        events += "onOpen"
    }

    override fun onDestructiveMigration(connection: SQLiteConnection) {
        events += "onDestructiveMigration"
    }
}
