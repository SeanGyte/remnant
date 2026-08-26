package com.remnant.dreams

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application-lifetime coroutine scope for background work that must survive the screen
 * that started it -- a lifecycleScope job is cancelled the moment its activity finishes.
 */
object AppScope {

    val io: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
