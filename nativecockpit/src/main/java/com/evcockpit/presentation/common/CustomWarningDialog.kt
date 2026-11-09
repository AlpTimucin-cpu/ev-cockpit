package com.evcockpit.presentation.common

import androidx.compose.ui.res.stringResource
import com.evcockpit.R

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** Only application-owned dialogs can be themed, not Android permission sheets. */
@Composable
fun CustomWarningDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit = onDismiss,
    confirmText: String = "OK"
) {
    val dark = Color(0xFF0A0F1A)
    val neon = Color(0xFF00E676)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().border(
                1.dp,
                Brush.linearGradient(
                    listOf(Color(0x8853E5EA), Color(0x445D7295), neon.copy(alpha = 0.55f))
                ),
                RoundedCornerShape(22.dp)
            ),
            color = dark,
            contentColor = Color.White,
            shape = RoundedCornerShape(22.dp),
            shadowElevation = 16.dp
        ) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.native_close), color = neon) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = neon, contentColor = dark
                        )
                    ) { Text(confirmText) }
                }
            }
        }
    }
}
