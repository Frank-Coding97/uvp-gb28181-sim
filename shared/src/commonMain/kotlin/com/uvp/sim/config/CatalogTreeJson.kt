package com.uvp.sim.config

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 目录树 JSON 编解码工具(放在 shared 因为 kotlinx.serialization 在这里有依赖,
 * composeApp / androidApp 通过 transitive 调用即可)。
 */
object CatalogTreeJson {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    private val listSerializer = ListSerializer(CatalogNode.serializer())

    fun encode(tree: List<CatalogNode>): String =
        json.encodeToString(listSerializer, tree)

    fun decode(text: String): List<CatalogNode> =
        json.decodeFromString(listSerializer, text)
}
