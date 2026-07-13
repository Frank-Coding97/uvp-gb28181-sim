package com.uvp.sim.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecUseDataProtectionKeychain
import platform.Security.kSecValueData
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.value

interface DevicePasswordStore {
    fun read(account: String): String?
    fun save(account: String, password: String): Boolean
    fun delete(account: String): Boolean
}

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
class KeychainStore(
    private val service: String = SERVICE_DEVICE_PASSWORD,
) : DevicePasswordStore {

    internal var lastStatusForTest: Int = errSecSuccess
        private set

    override fun read(account: String): String? = memScoped {
        val result = alloc<CFTypeRefVar>()
        withCfDictionary(
            baseQuery(account) + listOf(
                cfEntry(kSecReturnData, kCFBooleanTrue),
                cfEntry(kSecMatchLimit, kSecMatchLimitOne),
            )
        ) { query ->
            val status = SecItemCopyMatching(query, result.ptr)
            lastStatusForTest = status
            if (status != errSecSuccess) return@memScoped null
            val rawRef = result.value ?: return@memScoped null
            // 2026-07-03 真机 K/N interop 坑:CFDataRef `as? NSData` 有时 cast 为 null
            // (toll-free bridging 在 kotlin native 上不稳定)。直接用 CoreFoundation API
            // 读字节,不走 Objective-C bridging。
            val cfData: CFDataRef = rawRef.reinterpret()
            val length = CFDataGetLength(cfData).toInt()
            if (length <= 0) return@memScoped null
            val bytesPtr = CFDataGetBytePtr(cfData) ?: return@memScoped null
            val bytes = bytesPtr.reinterpret<ByteVar>().readBytes(length)
            CFRelease(rawRef)
            result.value = null
            bytes.decodeToString()
        }
    }

    override fun save(account: String, password: String): Boolean {
        if (password.isEmpty()) return delete(account)
        val bytes = password.encodeToByteArray().toUByteArray()
        val data = bytes.usePinned {
            CFDataCreate(kCFAllocatorDefault, it.addressOf(0), bytes.size.convert())
        } ?: return false
        val attrs = listOf(cfEntry(kSecValueData, data, releaseValue = true))
        val updateStatus = withCfDictionary(baseQuery(account)) { query ->
            withCfDictionary(attrs) { attrDict ->
                SecItemUpdate(query, attrDict)
            }
        }
        lastStatusForTest = updateStatus
        if (updateStatus == errSecSuccess) return true
        if (updateStatus != errSecItemNotFound) return false
        val addData = bytes.usePinned {
            CFDataCreate(kCFAllocatorDefault, it.addressOf(0), bytes.size.convert())
        } ?: return false
        val addStatus = withCfDictionary(addQuery(account, addData)) { query ->
            SecItemAdd(query, null)
        }
        lastStatusForTest = addStatus
        return addStatus == errSecSuccess
    }

    override fun delete(account: String): Boolean {
        val status = withCfDictionary(baseQuery(account)) { query ->
            SecItemDelete(query)
        }
        lastStatusForTest = status
        return status == errSecSuccess || status == errSecItemNotFound
    }

    private fun baseQuery(account: String): List<CfEntry> {
        val serviceRef = cfString(service)
        val accountRef = cfString(account)
        return listOf(
            cfEntry(kSecClass, kSecClassGenericPassword),
            cfEntry(kSecAttrService, serviceRef, releaseValue = true),
            cfEntry(kSecAttrAccount, accountRef, releaseValue = true),
            cfEntry(kSecUseDataProtectionKeychain, kCFBooleanTrue),
        )
    }

    private fun addQuery(account: String, valueData: CFTypeRef?): List<CfEntry> =
        baseQuery(account) + listOf(
            cfEntry(kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly),
            cfEntry(kSecValueData, valueData, releaseValue = true),
        )

    private inline fun <T> withCfDictionary(values: List<CfEntry>, block: (CFDictionaryRef?) -> T): T {
        val dict = CFDictionaryCreateMutable(
            allocator = kCFAllocatorDefault,
            capacity = values.size.toLong(),
            keyCallBacks = kCFTypeDictionaryKeyCallBacks.ptr,
            valueCallBacks = kCFTypeDictionaryValueCallBacks.ptr,
        )
        if (dict == null) return block(null)
        try {
            values.forEach { entry ->
                if (entry.key != null && entry.value != null) {
                    CFDictionarySetValue(dict, entry.key, entry.value)
                }
            }
            return block(dict)
        } finally {
            CFRelease(dict)
            values.forEach { entry ->
                if (entry.releaseValue && entry.value != null) {
                    CFRelease(entry.value)
                }
            }
        }
    }

    private fun cfEntry(key: CFTypeRef?, value: CFTypeRef?, releaseValue: Boolean = false): CfEntry =
        CfEntry(key = key, value = value, releaseValue = releaseValue)

    private data class CfEntry(
        val key: CFTypeRef?,
        val value: CFTypeRef?,
        val releaseValue: Boolean,
    )

    private fun cfString(value: String): CFStringRef? =
        CFStringCreateWithCString(
            kCFAllocatorDefault,
            value,
            0x08000100u,
        )

    companion object {
        const val SERVICE_DEVICE_PASSWORD = "com.uvp.sim.device-password"
        const val DEFAULT_ACCOUNT = "default"

        fun accountForDeviceId(deviceId: String): String =
            deviceId.takeIf { it.isNotBlank() } ?: DEFAULT_ACCOUNT
    }
}
