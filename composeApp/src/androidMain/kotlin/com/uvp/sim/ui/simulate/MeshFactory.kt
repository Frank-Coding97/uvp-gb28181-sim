package com.uvp.sim.ui.simulate

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 程序化几何体工厂.为 Filament 场景生成 cylinder/box/sphere/cone 实体,
 * 不依赖外部 .filamesh / .glb 资源.
 *
 * 顶点格式: position(float3) + normal(float3),共 24 字节 stride.
 * 索引格式: ushort.
 *
 * 调用方负责保管 [Mesh] 的引用,在 onDispose 时调 [Mesh.destroy].
 */
class MeshFactory(
    private val engine: Engine,
    private val defaultMaterial: Material,
) {

    /** 生成的网格资源,统一管理生命周期. */
    data class Mesh(
        val entity: Int,
        val vertexBuffer: VertexBuffer,
        val indexBuffer: IndexBuffer,
        val materialInstance: MaterialInstance,
    ) {
        fun destroy(engine: Engine) {
            engine.destroyEntity(entity)
            engine.destroyVertexBuffer(vertexBuffer)
            engine.destroyIndexBuffer(indexBuffer)
            engine.destroyMaterialInstance(materialInstance)
        }
    }

    /**
     * 圆柱体(顶面 + 底面 + 侧面).
     * @param radius 半径
     * @param height 高度,中心在原点(y 范围 = ±height/2)
     * @param segments 圆周段数,越大越圆
     */
    fun createCylinder(radius: Float, height: Float, segments: Int = 32): Mesh {
        val verts = mutableListOf<Float>()
        val indices = mutableListOf<Int>()
        val halfH = height / 2f

        for (i in 0..segments) {
            val theta = 2f * PI.toFloat() * i / segments
            val nx = cos(theta)
            val nz = sin(theta)
            val x = radius * nx
            val z = radius * nz
            verts += listOf(x, halfH, z, nx, 0f, nz)
            verts += listOf(x, -halfH, z, nx, 0f, nz)
        }
        for (i in 0 until segments) {
            val a = i * 2
            indices += listOf(a, a + 1, a + 2, a + 2, a + 1, a + 3)
        }

        val topStart = verts.size / 6
        verts += listOf(0f, halfH, 0f, 0f, 1f, 0f)
        for (i in 0..segments) {
            val theta = 2f * PI.toFloat() * i / segments
            verts += listOf(radius * cos(theta), halfH, radius * sin(theta), 0f, 1f, 0f)
        }
        for (i in 0 until segments) {
            indices += listOf(topStart, topStart + i + 2, topStart + i + 1)
        }

        val botStart = verts.size / 6
        verts += listOf(0f, -halfH, 0f, 0f, -1f, 0f)
        for (i in 0..segments) {
            val theta = 2f * PI.toFloat() * i / segments
            verts += listOf(radius * cos(theta), -halfH, radius * sin(theta), 0f, -1f, 0f)
        }
        for (i in 0 until segments) {
            indices += listOf(botStart, botStart + i + 1, botStart + i + 2)
        }

        return buildMesh(verts.toFloatArray(), indices.toIntArray())
    }

    /** 长方体,中心在原点.6 面独立法线. */
    fun createBox(width: Float, height: Float, depth: Float): Mesh {
        val w = width / 2f
        val h = height / 2f
        val d = depth / 2f

        val verts = floatArrayOf(
            // +X
            w, -h, -d, 1f, 0f, 0f,  w, h, -d, 1f, 0f, 0f,  w, h, d, 1f, 0f, 0f,  w, -h, d, 1f, 0f, 0f,
            // -X
            -w, -h, d, -1f, 0f, 0f,  -w, h, d, -1f, 0f, 0f,  -w, h, -d, -1f, 0f, 0f,  -w, -h, -d, -1f, 0f, 0f,
            // +Y
            -w, h, -d, 0f, 1f, 0f,  -w, h, d, 0f, 1f, 0f,  w, h, d, 0f, 1f, 0f,  w, h, -d, 0f, 1f, 0f,
            // -Y
            -w, -h, d, 0f, -1f, 0f,  -w, -h, -d, 0f, -1f, 0f,  w, -h, -d, 0f, -1f, 0f,  w, -h, d, 0f, -1f, 0f,
            // +Z
            -w, -h, d, 0f, 0f, 1f,  w, -h, d, 0f, 0f, 1f,  w, h, d, 0f, 0f, 1f,  -w, h, d, 0f, 0f, 1f,
            // -Z
            w, -h, -d, 0f, 0f, -1f,  -w, -h, -d, 0f, 0f, -1f,  -w, h, -d, 0f, 0f, -1f,  w, h, -d, 0f, 0f, -1f,
        )
        val idx = mutableListOf<Int>()
        for (face in 0 until 6) {
            val base = face * 4
            idx += listOf(base, base + 1, base + 2, base, base + 2, base + 3)
        }
        return buildMesh(verts, idx.toIntArray())
    }

    /** UV 球体. */
    fun createSphere(radius: Float, segments: Int = 16, rings: Int = 16): Mesh {
        val verts = mutableListOf<Float>()
        for (r in 0..rings) {
            val phi = PI.toFloat() * r / rings
            val sinPhi = sin(phi)
            val cosPhi = cos(phi)
            for (s in 0..segments) {
                val theta = 2f * PI.toFloat() * s / segments
                val x = sinPhi * cos(theta)
                val y = cosPhi
                val z = sinPhi * sin(theta)
                verts += listOf(radius * x, radius * y, radius * z, x, y, z)
            }
        }
        val idx = mutableListOf<Int>()
        val cols = segments + 1
        for (r in 0 until rings) {
            for (s in 0 until segments) {
                val a = r * cols + s
                val b = a + cols
                idx += listOf(a, b, a + 1, a + 1, b, b + 1)
            }
        }
        return buildMesh(verts.toFloatArray(), idx.toIntArray())
    }

    /** 圆锥/截锥(topRadius=0 即标准圆锥). */
    fun createCone(topRadius: Float, bottomRadius: Float, height: Float, segments: Int = 32): Mesh {
        val verts = mutableListOf<Float>()
        val idx = mutableListOf<Int>()
        val halfH = height / 2f

        for (i in 0..segments) {
            val theta = 2f * PI.toFloat() * i / segments
            val ct = cos(theta)
            val st = sin(theta)
            verts += listOf(topRadius * ct, halfH, topRadius * st, ct, 0.5f, st)
            verts += listOf(bottomRadius * ct, -halfH, bottomRadius * st, ct, 0.5f, st)
        }
        for (i in 0 until segments) {
            val a = i * 2
            idx += listOf(a, a + 1, a + 2, a + 2, a + 1, a + 3)
        }

        val botStart = verts.size / 6
        verts += listOf(0f, -halfH, 0f, 0f, -1f, 0f)
        for (i in 0..segments) {
            val theta = 2f * PI.toFloat() * i / segments
            verts += listOf(bottomRadius * cos(theta), -halfH, bottomRadius * sin(theta), 0f, -1f, 0f)
        }
        for (i in 0 until segments) {
            idx += listOf(botStart, botStart + i + 1, botStart + i + 2)
        }
        return buildMesh(verts.toFloatArray(), idx.toIntArray())
    }

    /** 把顶点+索引写入 Filament VertexBuffer / IndexBuffer 并构造 renderable entity. */
    private fun buildMesh(verts: FloatArray, idx: IntArray): Mesh {
        val vb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
        for (v in verts) vb.putFloat(v)
        vb.flip()

        val ib = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder())
        for (i in idx) ib.putShort(i.toShort())
        ib.flip()

        val vertexBuffer = VertexBuffer.Builder()
            .vertexCount(verts.size / 6)
            .bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0,
                VertexBuffer.AttributeType.FLOAT3, 0, 24)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 0,
                VertexBuffer.AttributeType.FLOAT3, 12, 24)
            .build(engine)
        vertexBuffer.setBufferAt(engine, 0, vb)

        val indexBuffer = IndexBuffer.Builder()
            .indexCount(idx.size)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        indexBuffer.setBuffer(engine, ib)

        val materialInstance = defaultMaterial.createInstance()
        val entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, 0f, 1f, 1f, 1f))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer)
            .material(0, materialInstance)
            .build(engine, entity)

        return Mesh(entity, vertexBuffer, indexBuffer, materialInstance)
    }
}
