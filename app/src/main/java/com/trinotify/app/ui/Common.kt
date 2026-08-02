package com.trinotify.app.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.trinotify.app.data.Action

fun actionName(a: String): String = when (a) {
    Action.ALLOW -> "Со звуком"
    Action.SILENCE -> "Тихо"
    Action.BLOCK -> "Блокировать"
    Action.ML -> "Решает ML"
    else -> "По умолчанию"
}

fun actionColor(a: String): Color = when (a) {
    Action.ALLOW -> Color(0xFF2E7D32)
    Action.SILENCE -> Color(0xFF616161)
    Action.BLOCK -> Color(0xFFC62828)
    Action.ML -> Color(0xFF1565C0)
    else -> Color(0xFF8D6E63)
}

@Composable
fun ActionChip(action: String) {
    Surface(
        color = actionColor(action).copy(alpha = 0.15f),
        contentColor = actionColor(action),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            actionName(action),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

fun appIconBitmap(context: Context, pkg: String): ImageBitmap? = try {
    context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
} catch (_: PackageManager.NameNotFoundException) {
    null
} catch (_: Exception) {
    null
}

@Composable
fun AppIcon(pkg: String, context: Context, sizeDp: Int = 36) {
    val bmp = remember(pkg) { appIconBitmap(context, pkg) }
    if (bmp != null) {
        Image(bmp, contentDescription = null, modifier = Modifier.size(sizeDp.dp).clip(CircleShape))
    } else {
        Surface(
            modifier = Modifier.size(sizeDp.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {}
    }
}
