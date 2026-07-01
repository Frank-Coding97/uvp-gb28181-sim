package com.uvp.sim

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * KMP entry point consumed by iosApp/ContentView.swift.
 *
 * Generated Swift binding lives at
 *   MainViewControllerKt.MainViewController()
 *
 * v1.1 status: standalone placeholder screen — proves the KMP framework
 * loads and Compose renders on iOS. Full App() wiring (which needs
 * AppEngine, AppUiState, AppActions from shared/commonMain) is
 * T1-follow-up (PR-iOS-5 UI 接入).
 */
@Suppress("FunctionName", "unused")  // Called from Swift via KMP-generated binding
fun MainViewController(): UIViewController = ComposeUIViewController {
    IosBootScreen()
}

@Composable
private fun IosBootScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1F3B)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "UVP Sim",
                color = Color.White,
                fontSize = 24.sp
            )
            Text(
                text = "iOS v1.1 · Compose Multiplatform boot ok",
                color = Color(0xFF7FB4FF),
                fontSize = 14.sp
            )
            Text(
                text = "Awaiting App() wire-up (PR-iOS-5)",
                color = Color(0xFFB8C6E0),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
