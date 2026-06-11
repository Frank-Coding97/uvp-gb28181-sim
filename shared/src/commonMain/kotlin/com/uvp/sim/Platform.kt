package com.uvp.sim

expect class Platform() {
    val name: String
}

fun getPlatform(): Platform = Platform()
