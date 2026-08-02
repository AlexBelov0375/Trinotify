package com.trinotify.app.logic

import android.content.Context
import android.net.Uri
import com.trinotify.app.data.AppRule
import com.trinotify.app.data.CallRule
import com.trinotify.app.data.Db
import com.trinotify.app.data.NotifRecord
import com.trinotify.app.data.SenderRule
import com.trinotify.app.ml.Ml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Экспорт/импорт правил и датасета (размеченных записей) в JSON через SAF. */
object DataIO {

    /** Экспорт правил + размеченного датасета (без остального архива). */
    suspend fun exportRulesAndDataset(context: Context, uri: Uri): Boolean =
        export(context, uri, includeFullArchive = false)

    /** Полный бэкап архива: все записи + правила + датасет. */
    suspend fun exportFullBackup(context: Context, uri: Uri): Boolean =
        export(context, uri, includeFullArchive = true)

    private suspend fun export(context: Context, uri: Uri, includeFullArchive: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val db = Db.get(context)
                val root = JSONObject()
                root.put("app", "trinotify")
                root.put("version", 1)
                root.put("exportedAt", System.currentTimeMillis())

                root.put("appRules", JSONArray().apply {
                    db.ruleDao().appRulesSnapshot().forEach {
                        put(JSONObject()
                            .put("pkg", it.pkg).put("appLabel", it.appLabel)
                            .put("action", it.action).put("hits", it.hits))
                    }
                })
                root.put("senderRules", JSONArray().apply {
                    db.ruleDao().senderRulesSnapshot().forEach {
                        put(JSONObject()
                            .put("pkg", it.pkg ?: JSONObject.NULL).put("pattern", it.pattern)
                            .put("inText", it.inText).put("action", it.action).put("hits", it.hits))
                    }
                })
                root.put("callRules", JSONArray().apply {
                    db.ruleDao().callRulesSnapshot().forEach {
                        put(JSONObject()
                            .put("pattern", it.pattern).put("action", it.action).put("hits", it.hits))
                    }
                })

                val records =
                    if (includeFullArchive) db.notifDao().allRecords()
                    else db.notifDao().labeled()
                root.put("records", JSONArray().apply {
                    records.forEach {
                        put(JSONObject()
                            .put("pkg", it.pkg).put("appLabel", it.appLabel)
                            .put("title", it.title).put("text", it.text)
                            .put("postedAt", it.postedAt).put("verdict", it.verdict)
                            .put("verdictSource", it.verdictSource)
                            .put("userLabel", it.userLabel ?: JSONObject.NULL)
                            .put("isCall", it.isCall))
                    }
                })

                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(root.toString(2).toByteArray(Charsets.UTF_8))
                } ?: return@withContext false
                true
            } catch (_: Exception) {
                false
            }
        }

    data class ImportResult(val rules: Int, val records: Int)

    /**
     * Импорт: правила добавляются (правило приложения перезаписывает существующее),
     * записи добавляются в архив, затем модель переобучается.
     */
    suspend fun importAll(context: Context, uri: Uri): ImportResult? =
        withContext(Dispatchers.IO) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: return@withContext null
                val root = JSONObject(text)
                if (root.optString("app") != "trinotify") return@withContext null
                val db = Db.get(context)
                var rules = 0

                val appRules = root.optJSONArray("appRules") ?: JSONArray()
                for (i in 0 until appRules.length()) {
                    val o = appRules.getJSONObject(i)
                    db.ruleDao().upsertAppRule(
                        AppRule(
                            pkg = o.getString("pkg"),
                            appLabel = o.optString("appLabel", o.getString("pkg")),
                            action = o.getString("action"),
                            hits = o.optLong("hits", 0),
                        )
                    )
                    rules++
                }

                val existingSender = db.ruleDao().senderRulesSnapshot()
                    .map { Triple(it.pkg, it.pattern, it.action) }.toSet()
                val senderRules = root.optJSONArray("senderRules") ?: JSONArray()
                for (i in 0 until senderRules.length()) {
                    val o = senderRules.getJSONObject(i)
                    val pkg = if (o.isNull("pkg")) null else o.getString("pkg")
                    val rule = SenderRule(
                        pkg = pkg, pattern = o.getString("pattern"),
                        inText = o.optBoolean("inText", false),
                        action = o.getString("action"), hits = o.optLong("hits", 0),
                    )
                    if (Triple(rule.pkg, rule.pattern, rule.action) !in existingSender) {
                        db.ruleDao().insertSenderRule(rule)
                        rules++
                    }
                }

                val existingCall = db.ruleDao().callRulesSnapshot()
                    .map { it.pattern to it.action }.toSet()
                val callRules = root.optJSONArray("callRules") ?: JSONArray()
                for (i in 0 until callRules.length()) {
                    val o = callRules.getJSONObject(i)
                    val rule = CallRule(
                        pattern = o.getString("pattern"),
                        action = o.getString("action"), hits = o.optLong("hits", 0),
                    )
                    if (rule.pattern to rule.action !in existingCall) {
                        db.ruleDao().insertCallRule(rule)
                        rules++
                    }
                }

                val records = root.optJSONArray("records") ?: JSONArray()
                val toInsert = ArrayList<NotifRecord>(records.length())
                for (i in 0 until records.length()) {
                    val o = records.getJSONObject(i)
                    toInsert.add(
                        NotifRecord(
                            pkg = o.getString("pkg"),
                            appLabel = o.optString("appLabel", ""),
                            title = o.optString("title", ""),
                            text = o.optString("text", ""),
                            postedAt = o.optLong("postedAt", 0),
                            verdict = o.optString("verdict", "ALLOW"),
                            verdictSource = o.optString("verdictSource", "Импорт"),
                            userLabel = if (o.isNull("userLabel")) null else o.getString("userLabel"),
                            isCall = o.optBoolean("isCall", false),
                        )
                    )
                }
                if (toInsert.isNotEmpty()) db.notifDao().insertAll(toInsert)
                Ml.rebuild(db)
                ImportResult(rules, toInsert.size)
            } catch (_: Exception) {
                null
            }
        }
}
