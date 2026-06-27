package com.uvp.sim.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val IoDispatcher: CoroutineDispatcher = Dispatchers.IO
