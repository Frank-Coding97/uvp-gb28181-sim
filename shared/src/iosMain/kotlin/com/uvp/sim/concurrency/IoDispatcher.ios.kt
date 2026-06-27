package com.uvp.sim.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Native (iOS) target — no dedicated IO pool. Use Default; blocking syscalls
 * on Native happen in worker threads regardless.
 */
internal actual val IoDispatcher: CoroutineDispatcher = Dispatchers.Default
