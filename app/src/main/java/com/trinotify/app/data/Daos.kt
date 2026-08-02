package com.trinotify.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NotifDao {
    @Insert
    suspend fun insert(r: NotifRecord): Long

    @Query("SELECT * FROM notif ORDER BY id DESC LIMIT 1500")
    fun recent(): Flow<List<NotifRecord>>

    @Query("UPDATE notif SET userLabel = :label WHERE id = :id")
    suspend fun setLabel(id: Long, label: String?)

    @Query("SELECT * FROM notif WHERE userLabel IS NOT NULL AND isCall = 0")
    suspend fun labeled(): List<NotifRecord>

    @Query("SELECT COUNT(*) FROM notif WHERE userLabel IS NOT NULL AND isCall = 0")
    fun labeledCount(): Flow<Int>

    @Query("DELETE FROM notif WHERE userLabel IS NULL AND postedAt < :before")
    suspend fun pruneUnlabeled(before: Long)

    @Query("DELETE FROM notif WHERE userLabel IS NULL")
    suspend fun clearUnlabeled()

    @Query("DELETE FROM notif")
    suspend fun clearAll()

    @Query("SELECT * FROM notif ORDER BY id")
    suspend fun allRecords(): List<NotifRecord>

    @Insert
    suspend fun insertAll(rs: List<NotifRecord>)
}

@Dao
interface RuleDao {
    // Правила приложений
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAppRule(r: AppRule)

    @Query("DELETE FROM app_rule WHERE pkg = :pkg")
    suspend fun deleteAppRule(pkg: String)

    @Query("SELECT * FROM app_rule")
    fun appRules(): Flow<List<AppRule>>

    @Query("SELECT * FROM app_rule WHERE pkg = :pkg")
    suspend fun appRule(pkg: String): AppRule?

    @Query("SELECT * FROM app_rule")
    suspend fun appRulesSnapshot(): List<AppRule>

    @Query("UPDATE app_rule SET hits = hits + 1 WHERE pkg = :pkg")
    suspend fun incAppHit(pkg: String)

    @Query("UPDATE sender_rule SET hits = hits + 1 WHERE id = :id")
    suspend fun incSenderHit(id: Long)

    @Query("UPDATE call_rule SET hits = hits + 1 WHERE id = :id")
    suspend fun incCallHit(id: Long)

    // Правила отправителей
    @Insert
    suspend fun insertSenderRule(r: SenderRule)

    @Query("DELETE FROM sender_rule WHERE id = :id")
    suspend fun deleteSenderRule(id: Long)

    @Query("SELECT * FROM sender_rule")
    fun senderRules(): Flow<List<SenderRule>>

    @Query("SELECT * FROM sender_rule")
    suspend fun senderRulesSnapshot(): List<SenderRule>

    // Правила звонков
    @Insert
    suspend fun insertCallRule(r: CallRule)

    @Query("DELETE FROM call_rule WHERE id = :id")
    suspend fun deleteCallRule(id: Long)

    @Query("SELECT * FROM call_rule")
    fun callRules(): Flow<List<CallRule>>

    @Query("SELECT * FROM call_rule")
    suspend fun callRulesSnapshot(): List<CallRule>
}

@Dao
interface BayesDao {
    @Query("DELETE FROM bayes")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<BayesToken>)

    @Query("SELECT * FROM bayes")
    suspend fun all(): List<BayesToken>
}
