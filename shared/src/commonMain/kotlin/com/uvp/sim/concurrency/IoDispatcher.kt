package com.uvp.sim.concurrency

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Cross-platform IO dispatcher abstraction.
 *
 * `kotlinx.coroutines.Dispatchers.IO` is JVM-only — referencing it from
 * commonMain breaks Kotlin/Native (iOS) compilation. This expect/actual lets
 * each target pick the right dispatcher:
 *  - JVM / Android: `Dispatchers.IO` (real blocking-IO thread pool)
 *  - iOS / Native:  `Dispatchers.Default` (no dedicated IO pool on Native;
 *    Default is the canonical fallback for blocking ops on Native)
 *
 * Internal — not part of the SDK public surface.
 */
internal expect val IoDispatcher: CoroutineDispatcher
