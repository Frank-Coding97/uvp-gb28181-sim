package com.uvp.sim.app

import platform.Foundation.NSUUID
import platform.Security.errSecMissingEntitlement
import platform.Security.errSecNotAvailable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

class KeychainStoreTest {

    private val store = KeychainStore(service = "com.uvp.sim.device-password.test")
    private val accounts = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        accounts.forEach { store.delete(it) }
        accounts.clear()
    }

    @Test
    fun keychain_write_read_roundtrip() {
        val account = newAccount()

        if (!saveOrSkip(account, "secret123")) return

        assertEquals("secret123", store.read(account))
    }

    @Test
    fun keychain_overwrite_updates_value() {
        val account = newAccount()

        if (!saveOrSkip(account, "old")) return
        if (!saveOrSkip(account, "new")) return

        assertEquals("new", store.read(account))
    }

    @Test
    fun keychain_delete_removes_value() {
        val account = newAccount()

        if (!saveOrSkip(account, "secret123")) return
        store.delete(account)

        assertNull(store.read(account))
    }

    private fun newAccount(): String =
        "test-${NSUUID().UUIDString}".also { accounts += it }

    /**
     * Keychain 不可用时跳过(而非判失败)。
     *
     * `simctl spawn` 直接跑裸可执行文件时,进程没有 keychain-access-groups
     * entitlement,`SecItemAdd` 会返回 -34018(errSecMissingEntitlement)。
     * Gradle 的 `iosSimulatorArm64Test` 走的是同一条路径,所以这是环境限制,
     * 不是实现缺陷 —— 跟 [errSecNotAvailable] 一样按跳过处理。
     */
    private fun saveOrSkip(account: String, password: String): Boolean {
        if (store.save(account, password)) return true
        val status = store.lastStatusForTest
        if (status == errSecNotAvailable || status == errSecMissingEntitlement) return false
        fail("Keychain save failed status=$status")
    }
}
