package com.uvp.sim.app

import platform.Foundation.NSUUID
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

    private fun saveOrSkip(account: String, password: String): Boolean {
        if (store.save(account, password)) return true
        if (store.lastStatusForTest == errSecNotAvailable) return false
        fail("Keychain save failed status=${store.lastStatusForTest}")
    }
}
