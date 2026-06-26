package com.uvp.sim

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PR-TEST-1 T3 — Smoke E2E:Activity 能起、不崩。
 *
 * 跑在真机 / 模拟器上(`./gradlew :androidApp:connectedAndroidTest`)。
 *
 * 验证目标:
 *   1. MainActivity 可启动(CameraX 初始化 + ViewModel 装配链 + Compose setContent 不抛)
 *   2. Activity 切到 RESUMED 后再销毁,不挂(Activity onCreate→onResume→onDestroy 全跑通)
 *
 * **故意不验** SIP 注册 / 媒体推流 — smoke 范围,UI 元素断言会随 PR-C 主屏改版抖,留着白干。
 * 后续 follow-up:加 `onView(withText("注册")).check(matches(isDisplayed()))` 等真 UI 断言,
 * 需要等 PR-C 主屏 import 稳定 + 用 testTag 锚定主关键元素。
 *
 * 跳过运行的情况(写到 README):
 *   - 没接真机 / 模拟器 → `connectedDebugAndroidTest` task 直接 skipped,不阻塞 CI
 *   - 模拟器没有 Camera 模拟 → MainActivity onCreate 走权限 deny 路径,Compose 仍能起
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchSmokeTest {

    @Test
    fun mainActivity_launches_without_crash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Activity 已 RESUMED — 走完 onCreate / onStart / onResume,Compose setContent 完成
                assert(!activity.isFinishing) {
                    "MainActivity finished unexpectedly during smoke launch"
                }
            }
        }
        // ActivityScenario.use 会自动走 onPause / onStop / onDestroy,验证完整生命周期不挂
    }
}
