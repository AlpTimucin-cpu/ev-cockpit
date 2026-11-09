package com.evcockpit.presentation.media

import androidx.car.app.connection.CarConnection
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Phone browsing is available; a projected host never inherits phone video permission. */
@Composable
fun rememberPhoneMediaAllowed(): Boolean {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val connection = remember(context.applicationContext) { CarConnection(context.applicationContext) }
    var allowed by remember { mutableStateOf(false) }
    DisposableEffect(connection, owner) {
        val observer = Observer<Int> { type ->
            allowed = type == CarConnection.CONNECTION_TYPE_NOT_CONNECTED
        }
        connection.type.observe(owner, observer)
        onDispose { connection.type.removeObserver(observer) }
    }
    return allowed
}
