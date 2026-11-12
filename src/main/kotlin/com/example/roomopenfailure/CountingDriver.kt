package com.example.roomopenfailure

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import java.util.concurrent.atomic.AtomicInteger

/** Counts the connections opened by the real driver, and how many of them get closed. */
class CountingDriver(private val delegate: SQLiteDriver) : SQLiteDriver by delegate {
    val opened = AtomicInteger()
    val closed = AtomicInteger()

    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        opened.incrementAndGet()
        return object : SQLiteConnection by connection {
            override fun close() {
                closed.incrementAndGet()
                connection.close()
            }
        }
    }
}
