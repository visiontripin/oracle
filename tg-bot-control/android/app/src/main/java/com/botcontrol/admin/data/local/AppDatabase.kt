package com.botcontrol.admin.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val pluginId: String,
    val code: String,
    val manifest: String,
    val config: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts WHERE pluginId = :pluginId")
    suspend fun get(pluginId: String): DraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: DraftEntity)

    @Query("DELETE FROM drafts WHERE pluginId = :pluginId")
    suspend fun delete(pluginId: String)
}

@Entity(tableName = "bot_rules")
data class BotRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val type: String,          // "command" | "contains" | "button"
    val pattern: String,
    val responseText: String,
    val enabled: Boolean = true,
    val actionType: String = "text", // "text" | "script" | "llm" | "pack"
    val script: String = "",         // JS source when actionType == "script"
    val packId: String = "",         // набор ответов when actionType == "pack"
    val menu: String = "",           // JSON: inline-кнопки под ответом
    val botId: Long = 1L,            // какому боту принадлежит правило
)

@Dao
interface BotRuleDao {
    @Query("SELECT * FROM bot_rules WHERE botId = :botId ORDER BY id")
    suspend fun byBot(botId: Long): List<BotRuleEntity>

    @Query("SELECT * FROM bot_rules ORDER BY id")
    suspend fun all(): List<BotRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: BotRuleEntity): Long

    @Query("DELETE FROM bot_rules WHERE id = :id")
    suspend fun delete(id: Int)
}

@Database(entities = [DraftEntity::class, BotRuleEntity::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun draftDao(): DraftDao
    abstract fun botRuleDao(): BotRuleDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bot_rules ADD COLUMN botId INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bot_rules ADD COLUMN packId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE bot_rules ADD COLUMN menu TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bot_rules ADD COLUMN actionType TEXT NOT NULL DEFAULT 'text'")
                db.execSQL("ALTER TABLE bot_rules ADD COLUMN script TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "botcontrol.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
