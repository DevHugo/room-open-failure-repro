# Room: no hook when the database fails to open (driver mode)

Minimal reproduction for a Room feature request: in driver mode (`setDriver`), when Room fails to open, migrate or
validate the database, the exception goes only to the caller that asked for the connection, and nothing else is told.

- Room `2.8.4`, `androidx.sqlite:sqlite-bundled` `2.6.2` (`BundledSQLiteDriver`)
- Plain Kotlin/JVM project: the code involved (`RoomConnectionManager`) is in Room's `commonMain`, so the behaviour is
  the same on Android.

## Run

```bash
./gradlew test
```

Every test **passes**: each one asserts the current behaviour. The output prints what each caller received.

## Setup

A database is created at version 1 (`item(id, name)`), then opened by version 2 of the app, whose `@Entity` also has a
`quantity` column ([Databases.kt](src/main/kotlin/com/example/roomopenfailure/Databases.kt)).
A `RoomDatabase.Callback` records every callback Room invokes.

## What the tests show

[RoomOpenFailureTest.kt](src/test/kotlin/com/example/roomopenfailure/RoomOpenFailureTest.kt)

| # | Test | Result on 2.8.4 |
|---|---|---|
| 1 | A migration that forgets `ALTER TABLE` | `IllegalStateException: Migration didn't properly handle…` thrown to the caller, **no callback** |
| 1 | No migration path | `IllegalStateException: A migration from 1 to 2 was required but not found…`, **no callback** |
| 1 | Schema changed without bumping the version | `IllegalStateException: Room cannot verify the data integrity…`, **no callback** |
| 2 | 3 sequential calls | the migration **runs 3 times**, each caller gets its own exception |
| 2 | 4 concurrent callers | each of them gets the exception, on its own coroutine |
| 3 | One caller catches the error (`runCatching`) | `user_version` stays at 1, no callback: the app keeps running on the old database and nothing else knows |
| 4 | 3 failed opens, then `close()` | **3 connections opened, 0 closed** (side issue, see below) |
| – | Control: correct migration | 1 opened / 1 closed, `onOpen` called |

## Why

`RoomConnectionManager.DriverWrapper.openLocked()` configures the database on whichever caller opens the first
connection, and `isConfigured` only becomes `true` at the end of a successful `onOpen()`:

```kotlin
val connection = actual.open(filename)
if (!isConfigured) {
    try {
        isInitializing = true
        configureDatabase(connection)
    } finally {
        isInitializing = false
    }
}
```

```kotlin
protected fun onOpen(connection: SQLiteConnection) {
    checkIdentity(connection)
    openDelegate.onOpen(connection)
    invokeOpenCallback(connection)
    isConfigured = true
}
```

So a failure is thrown to that caller only, and every later caller re-runs the whole configuration.
`RoomDatabase.Callback` only has `onCreate`, `onOpen` and `onDestructiveMigration`, so there is no hook for the failure.
Most of these failures are Room's own Kotlin checks (`onValidateSchema`, `checkIdentity`), so a `SQLiteDriver` wrapper
cannot see them either.

Sources:
- Room 2.x: [RoomConnectionManager.kt (androidx-room-release)](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-room-release/room/room-runtime/src/commonMain/kotlin/androidx/room/RoomConnectionManager.kt)
- Room 3: [RoomConnectionManager.kt (androidx-main)](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/room3/room3-runtime/src/commonMain/kotlin/androidx/room3/RoomConnectionManager.kt)

## Consequences on Android

Apps that recover from a broken database (delete it and re-sync from a server) could do it with
`SupportSQLiteOpenHelper`, by wrapping the open helper so every caller went through their code. In driver mode, and in
Room 3 where SupportSQLite is removed:

- a caller that catches the exception leaves the app on a broken database (test 3);
- a caller that does not catch it crashes the app: for example a `LiveData` computed on Room's executor, which wraps it in
  `RuntimeException("Exception while computing database live data.")`, or code running on the main thread at startup;
- the only general workaround is a default uncaught exception handler that matches Room's exception messages.

## Request

A hook called from `configureDatabase()` when it fails, before the exception is thrown to the caller, whatever caller
or thread triggered the open. For example `RoomDatabase.Callback.onOpenFailure(exception: Throwable)`, or a builder
option that can also choose the recovery (rethrow, or delete and recreate the database).

## Side issue: leaked connection

In `openLocked()`, the connection returned by `actual.open(filename)` is not closed when `configureDatabase()` throws,
and the connection pool never received it (`ConnectionPoolImpl.tryOpenNewConnectionLocked()` fails inside
`connectionFactory.invoke()`), so closing the database does not close it either. `BundledSQLiteConnection` has no
finalizer, so the native handle stays open until the process dies. Test 4 counts the connections with a wrapping driver
([CountingDriver.kt](src/main/kotlin/com/example/roomopenfailure/CountingDriver.kt)): every failed attempt leaks one.
