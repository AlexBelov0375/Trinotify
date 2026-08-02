package com.trinotify.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [NotifRecord::class, AppRule::class, SenderRule::class, CallRule::class, BayesToken::class],
    version = 2,
    exportSchema = false,
)
abstract class Db : RoomDatabase() {
    abstract fun notifDao(): NotifDao
    abstract fun ruleDao(): RuleDao
    abstract fun bayesDao(): BayesDao

    companion object {
        private const val PLAIN_NAME = "trinotify.db"
        private const val ENC_NAME = "trinotify_enc.db"

        @Volatile private var instance: Db? = null
        @Volatile private var cipherLoaded = false

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_rule ADD COLUMN hits INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sender_rule ADD COLUMN hits INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE call_rule ADD COLUMN hits INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): Db = instance ?: synchronized(this) {
            instance ?: openOrRecover(context.applicationContext).also { instance = it }
        }

        /**
         * Открывает БД. Если зашифрованная БД нечитаема (Keystore-ключ пропал после
         * очистки данных, повреждённый файл, прерванное переключение) — сбрасывает
         * шифрование и стартует с чистой незашифрованной базой, вместо крэш-петли.
         */
        private fun openOrRecover(appCtx: Context): Db {
            val prefs = Prefs.get(appCtx)
            try {
                val db = build(appCtx, prefs.dbEncrypted)
                db.query("SELECT count(*) FROM sqlite_master", null).close() // форсируем открытие
                return db
            } catch (_: Throwable) {
                if (prefs.dbEncrypted) {
                    runCatching { KeyVault.reset(appCtx) }
                    runCatching { appCtx.deleteDatabase(ENC_NAME) }
                    prefs.dbEncrypted = false
                }
                return build(appCtx, false)
            }
        }

        private fun build(context: Context, encrypted: Boolean): Db {
            val builder =
                if (encrypted) {
                    if (!cipherLoaded) {
                        System.loadLibrary("sqlcipher")
                        cipherLoaded = true
                    }
                    Room.databaseBuilder(context, Db::class.java, ENC_NAME)
                        .openHelperFactory(SupportOpenHelperFactory(KeyVault.dbPassphrase(context)))
                } else {
                    Room.databaseBuilder(context, Db::class.java, PLAIN_NAME)
                }
            return builder.addMigrations(MIGRATION_1_2).build()
        }

        /**
         * Включение/выключение шифрования: копирует все данные в новую базу,
         * переключает настройку и удаляет старый файл. Вызывать не с главного потока.
         */
        suspend fun switchEncryption(context: Context, enable: Boolean): Boolean {
            val appCtx = context.applicationContext
            val prefs = Prefs.get(appCtx)
            if (prefs.dbEncrypted == enable) return true
            try {
                val src = get(appCtx)
                val notifs = src.notifDao().allRecords()
                val appRules = src.ruleDao().appRulesSnapshot()
                val senderRules = src.ruleDao().senderRulesSnapshot()
                val callRules = src.ruleDao().callRulesSnapshot()
                val bayes = src.bayesDao().all()

                // Целевой файл могла оставить прерванная попытка — начинаем с чистого,
                // иначе перенос задублировал бы правила (у них нет уникальных ключей).
                val targetName = if (enable) ENC_NAME else PLAIN_NAME
                if (enable) runCatching { KeyVault.reset(appCtx) } // свежий ключ под новую базу
                appCtx.deleteDatabase(targetName)

                val dst = build(appCtx, enable)
                dst.notifDao().insertAll(notifs)
                for (r in appRules) dst.ruleDao().upsertAppRule(r)
                for (r in senderRules) dst.ruleDao().insertSenderRule(r.copy(id = 0))
                for (r in callRules) dst.ruleDao().insertCallRule(r.copy(id = 0))
                if (bayes.isNotEmpty()) dst.bayesDao().insertAll(bayes)

                synchronized(this) {
                    instance?.close()
                    prefs.dbEncrypted = enable
                    instance = dst
                }
                val oldName = if (enable) PLAIN_NAME else ENC_NAME
                appCtx.deleteDatabase(oldName)
                return true
            } catch (_: Exception) {
                return false
            }
        }
    }
}
