package com.example.roomopenfailure

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Every test passes on Room 2.8.4 / sqlite-bundled 2.6.2: each one asserts the CURRENT behaviour.
 *
 * Setup: a database is created at version 1 (table `item(id, name)`), then opened by version 2 of the app,
 * whose entity also has a `quantity` column.
 */
class RoomOpenFailureTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val databaseFile: File by lazy { File(folder.root, "app.db") }

    /** A 1 → 2 migration with a bug: it forgets `ALTER TABLE item ADD COLUMN quantity`. */
    private class ForgetfulMigration : Migration(1, 2) {
        val runs = AtomicInteger()

        override fun migrate(connection: SQLiteConnection) {
            runs.incrementAndGet()
        }
    }

    private fun createVersion1Database() = runBlocking {
        val database = Room.databaseBuilder<DatabaseV1>(name = databaseFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        database.itemDao().insert(ItemV1(id = 1, name = "milk"))
        database.close()
    }

    private fun <T : RoomDatabase> RoomDatabase.Builder<T>.common(
        callback: RecordingCallback,
        driver: CountingDriver = CountingDriver(BundledSQLiteDriver()),
    ): RoomDatabase.Builder<T> =
        setDriver(driver)
            .setQueryCoroutineContext(Dispatchers.IO)
            .addCallback(callback)

    private fun openVersion2(
        callback: RecordingCallback,
        migration: Migration? = null,
        driver: CountingDriver = CountingDriver(BundledSQLiteDriver()),
    ): DatabaseV2 =
        Room.databaseBuilder<DatabaseV2>(name = databaseFile.absolutePath)
            .common(callback = callback, driver = driver)
            .also { builder -> migration?.let { builder.addMigrations(it) } }
            .build()

    private fun readUserVersion(): Long =
        BundledSQLiteDriver().open(databaseFile.absolutePath).use { connection ->
            connection.prepare("PRAGMA user_version").use { statement ->
                statement.step()
                statement.getLong(0)
            }
        }

    /** 1. The three ways Room fails to open the database: none of them invokes a callback. */

    @Test
    fun `a migration that leaves the wrong schema invokes no callback`() = runBlocking {
        createVersion1Database()
        val callback = RecordingCallback()
        val database = openVersion2(callback = callback, migration = ForgetfulMigration())

        val error = assertFailsWith<IllegalStateException> { database.itemDao().count() }

        println("Thrown to the caller: ${error.message?.lineSequence()?.first()}")
        assertTrue(error.message.orEmpty().startsWith("Migration didn't properly handle"))
        assertEquals(emptyList(), callback.events)
    }

    @Test
    fun `a missing migration invokes no callback`() = runBlocking {
        createVersion1Database()
        val callback = RecordingCallback()
        val database = openVersion2(callback = callback, migration = null)

        val error = assertFailsWith<IllegalStateException> { database.itemDao().count() }

        println("Thrown to the caller: ${error.message?.lineSequence()?.first()}")
        assertTrue(error.message.orEmpty().contains("was required but not found"))
        assertEquals(emptyList(), callback.events)
    }

    @Test
    fun `an identity hash mismatch invokes no callback`() = runBlocking {
        createVersion1Database()
        val callback = RecordingCallback()
        val database = Room.databaseBuilder<DatabaseV1ChangedSchema>(name = databaseFile.absolutePath)
            .common(callback = callback)
            .build()

        val error = assertFailsWith<IllegalStateException> { database.itemDao().count() }

        println("Thrown to the caller: ${error.message?.lineSequence()?.first()}")
        assertTrue(error.message.orEmpty().startsWith("Room cannot verify the data integrity"))
        assertEquals(emptyList(), callback.events)
    }

    /** 2. The failure is not remembered: every caller re-runs the whole open and gets its own exception. */

    @Test
    fun `every caller re-runs the failed migration and gets its own exception`() = runBlocking {
        createVersion1Database()
        val migration = ForgetfulMigration()
        val database = openVersion2(callback = RecordingCallback(), migration = migration)

        val failures = (1..3).map { runCatching { database.itemDao().count() }.exceptionOrNull() }

        println("Migration ran ${migration.runs.get()} times, failures: ${failures.map { it?.javaClass?.simpleName }}")
        assertTrue(failures.all { it is IllegalStateException })
        assertEquals(3, migration.runs.get())
    }

    @Test
    fun `concurrent callers each get the exception on their own coroutine`() = runBlocking {
        createVersion1Database()
        val migration = ForgetfulMigration()
        val database = openVersion2(callback = RecordingCallback(), migration = migration)

        val failures = (1..4).map { caller ->
            async(Dispatchers.IO) {
                caller to runCatching { database.itemDao().count() }.exceptionOrNull()
            }
        }.awaitAll()

        failures.forEach { (caller, error) -> println("Caller $caller got ${error?.javaClass?.simpleName}") }
        assertTrue(failures.all { (_, error) -> error is IllegalStateException })
        assertEquals(4, migration.runs.get())
    }

    /** 3. A caller that catches the error is the only one told: the app keeps running on the old database. */

    @Test
    fun `a caller that swallows the error leaves the database at the old version and nobody is told`() =
        runBlocking {
            createVersion1Database()
            val callback = RecordingCallback()
            val database = openVersion2(callback = callback, migration = ForgetfulMigration())

            runCatching { database.itemDao().count() } // e.g. a "best effort" read at startup
            database.close()

            println("user_version after the failed open: ${readUserVersion()}, callbacks: ${callback.events}")
            assertEquals(1L, readUserVersion())
            assertEquals(emptyList(), callback.events)
        }

    /**
     * 4. Side issue: the connection opened for the failed configuration is never closed.
     *
     * RoomConnectionManager.DriverWrapper.openLocked() calls `actual.open(filename)`, then configureDatabase(), with
     * no close() when it throws; the pool never received the connection, so closing the database does not close it.
     */

    @Test
    fun `the connection of a failed open is never closed`() = runBlocking {
        createVersion1Database()
        val driver = CountingDriver(BundledSQLiteDriver())
        val database = openVersion2(callback = RecordingCallback(), migration = ForgetfulMigration(), driver = driver)

        repeat(3) { runCatching { database.itemDao().count() } }
        database.close()

        println("Connections opened: ${driver.opened.get()}, closed: ${driver.closed.get()}")
        assertEquals(3, driver.opened.get())
        assertEquals(0, driver.closed.get())
    }

    @Test
    fun `control - the connections of a successful open are all closed`() = runBlocking {
        createVersion1Database()
        val driver = CountingDriver(BundledSQLiteDriver())
        val migration = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE item ADD COLUMN quantity INTEGER NOT NULL DEFAULT 0")
            }
        }
        val callback = RecordingCallback()
        val database = openVersion2(callback = callback, migration = migration, driver = driver)

        assertEquals(1, database.itemDao().count())
        database.close()

        println("Connections opened: ${driver.opened.get()}, closed: ${driver.closed.get()}, callbacks: ${callback.events}")
        assertEquals(driver.opened.get(), driver.closed.get())
        assertEquals(listOf("onOpen"), callback.events)
    }
}
