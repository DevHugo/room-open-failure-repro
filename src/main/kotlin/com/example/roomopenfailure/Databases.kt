package com.example.roomopenfailure

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

// Version 1 of the app: table `item` with (id, name).

@Entity(tableName = "item")
data class ItemV1(@PrimaryKey val id: Long, val name: String)

@Dao
interface ItemDaoV1 {
    @Insert
    suspend fun insert(item: ItemV1)

    @Query("SELECT COUNT(*) FROM item")
    suspend fun count(): Int
}

@Database(entities = [ItemV1::class], version = 1, exportSchema = false)
abstract class DatabaseV1 : RoomDatabase() {
    abstract fun itemDao(): ItemDaoV1
}

// Version 2 of the app: the entity gains a `quantity` column.

@Entity(tableName = "item")
data class ItemV2(@PrimaryKey val id: Long, val name: String, val quantity: Int)

@Dao
interface ItemDaoV2 {
    @Query("SELECT COUNT(*) FROM item")
    suspend fun count(): Int
}

@Database(entities = [ItemV2::class], version = 2, exportSchema = false)
abstract class DatabaseV2 : RoomDatabase() {
    abstract fun itemDao(): ItemDaoV2
}

// Same version number as V1 but a different schema: the developer forgot to bump the version.

@Database(entities = [ItemV2::class], version = 1, exportSchema = false)
abstract class DatabaseV1ChangedSchema : RoomDatabase() {
    abstract fun itemDao(): ItemDaoV2
}
