package com.trinotify.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Действия над уведомлением/звонком. */
object Action {
    const val ALLOW = "ALLOW"     // показать со звуком
    const val SILENCE = "SILENCE" // показать без звука
    const val BLOCK = "BLOCK"     // скрыть/закрыть
    const val ML = "ML"           // решает модель (только для правил приложений)
    const val DEFAULT = "DEFAULT" // нет правила
}

/** Запись архива: уведомление или звонок. */
@Entity(tableName = "notif")
data class NotifRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val verdict: String,
    val verdictSource: String,
    val userLabel: String? = null,
    val isCall: Boolean = false,
)

/** Правило для приложения целиком. */
@Entity(tableName = "app_rule")
data class AppRule(
    @PrimaryKey val pkg: String,
    val appLabel: String,
    val action: String,
    val hits: Long = 0,
)

/** Правило по отправителю/содержимому: подстрока в заголовке (отправителе) или тексте. */
@Entity(tableName = "sender_rule")
data class SenderRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String?,      // null = любое приложение
    val pattern: String,   // подстрока, без учёта регистра
    val inText: Boolean,   // искать и в тексте, не только в заголовке
    val action: String,
    val hits: Long = 0,
)

/** Правило для номера телефона. */
@Entity(tableName = "call_rule")
data class CallRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,   // цифры, '*' — любые цифры (например +7900*)
    val action: String,    // ALLOW / BLOCK
    val hits: Long = 0,
)

/** Счётчики наивного Байеса. token = "__doc__" хранит число документов класса. */
@Entity(tableName = "bayes", primaryKeys = ["token", "label"])
data class BayesToken(
    val token: String,
    val label: String,
    val count: Int,
)
