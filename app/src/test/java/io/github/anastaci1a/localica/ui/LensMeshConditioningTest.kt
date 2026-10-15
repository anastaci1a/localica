package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class LensMeshConditioningTest {
    @Test fun failedNativeTriangleGetsUsableBasisWithoutChangingItsMap() {
        val vertices = floatArrayOf(103.09f, 212.4068f, 99.77476f, 216.02475f, 102.32695f, 210.77043f)
        val texture = floatArrayOf(91.87592f, 204.3313f, 89.91837f, 207.00763f, 92.26692f, 203.797f)
        for (mirrored in listOf(false, true)) {
            val order = if (mirrored) shortArrayOf(0, 2, 1) else shortArrayOf(0, 1, 2)
            for (start in 0..2) {
                val indices = ShortArray(3) { order[(it + start) % 3] }
                val result = ShortArray(3)
                val beforeVertices = vertices.copyOf(); val beforeTexture = texture.copyOf()
                conditionLensTextureTriangles(vertices, texture, indices, result)
                assertTrue(textureBasisQuality(texture, result[0].toInt(), result[1].toInt(), result[2].toInt()) > 1f / 4096f)
                assertArrayEquals(beforeVertices, vertices, 0f)
                assertArrayEquals(beforeTexture, texture, 0f)
                assertTrue((0..2).any { shift -> (0..2).all { result[it] == indices[(it + shift) % 3] } })
                assertEquals(area(vertices, indices), area(vertices, result), 1e-10)
                assertEquals(area(texture, indices), area(texture, result), 1e-10)
                for ((x, y) in listOf(101.5 to 213.5, 101.0 to 214.0, 102.0 to 212.0)) {
                    val before = lookup(vertices, texture, indices, x, y)
                    val after = lookup(vertices, texture, result, x, y)
                    assertEquals(before.first, after.first, 1e-9)
                    assertEquals(before.second, after.second, 1e-9)
                }
            }
        }
    }

    @Test fun ordinaryAndTrulyDegenerateTrianglesStayUnchanged() {
        val vertices = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f)
        val indices = shortArrayOf(0, 1, 2)
        for (texture in listOf(vertices, floatArrayOf(0f, 0f, 1f, 1f, 2f, 2f), FloatArray(6))) {
            val result = ShortArray(3)
            conditionLensTextureTriangles(vertices, texture, indices, result)
            assertArrayEquals(indices, result)
        }
    }

    @Test fun nearSingularMapCanRemainBelowLegacyThresholdAfterConditioning() {
        val vertices = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f)
        val texture = floatArrayOf(0f, 0f, 10f, 0f, 1f, .00001f)
        val before = texture.copyOf()
        val indices = shortArrayOf(0, 1, 2)
        val result = ShortArray(3)
        conditionLensTextureTriangles(vertices, texture, indices, result)
        val quality = textureBasisQuality(texture, result[0].toInt(), result[1].toInt(), result[2].toInt())
        assertTrue(quality > textureBasisQuality(texture, 0, 1, 2))
        assertTrue(quality <= 1f / 4096f)
        assertArrayEquals(before, texture, 0f)
        assertTrue(texture.all { it.isFinite() })
        assertEquals(area(vertices, indices), area(vertices, result), 0.0)
        assertEquals(area(texture, indices), area(texture, result), 1e-12)
    }

    @Test fun multipleFacesKeepTheirOrderCoverageAndIndependentWinding() {
        val vertices = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f, 10f, 10f)
        val texture = floatArrayOf(0f, 0f, 10f, 0f, 1f, .0001f, 11f, .0001f)
        val indices = shortArrayOf(0, 1, 2, 1, 3, 2, 2, 1, 0)
        val result = ShortArray(indices.size)
        conditionLensTextureTriangles(vertices, texture, indices, result)
        for (i in indices.indices step 3) {
            assertTrue((0..2).any { shift -> (0..2).all { result[i + it] == indices[i + (it + shift) % 3] } })
            assertEquals(area(vertices, indices.copyOfRange(i, i + 3)), area(vertices, result.copyOfRange(i, i + 3)), 0.0)
        }
    }

    private fun area(points: FloatArray, order: ShortArray): Double {
        val a = order[0].toInt(); val b = order[1].toInt(); val c = order[2].toInt()
        return (points[2*b].toDouble()-points[2*a])*(points[2*c+1].toDouble()-points[2*a+1]) -
            (points[2*b+1].toDouble()-points[2*a+1])*(points[2*c].toDouble()-points[2*a])
    }

    private fun lookup(vertices: FloatArray, texture: FloatArray, order: ShortArray,
        x: Double, y: Double): Pair<Double, Double> {
        val a = order[0].toInt(); val b = order[1].toInt(); val c = order[2].toInt()
        val ax = vertices[2*a].toDouble(); val ay = vertices[2*a+1].toDouble()
        val bx = vertices[2*b].toDouble(); val by = vertices[2*b+1].toDouble()
        val cx = vertices[2*c].toDouble(); val cy = vertices[2*c+1].toDouble()
        val determinant = (bx-ax)*(cy-ay)-(by-ay)*(cx-ax)
        val v = ((x-ax)*(cy-ay)-(y-ay)*(cx-ax))/determinant
        val w = ((bx-ax)*(y-ay)-(by-ay)*(x-ax))/determinant
        return Pair((1-v-w)*texture[2*a]+v*texture[2*b]+w*texture[2*c],
            (1-v-w)*texture[2*a+1]+v*texture[2*b+1]+w*texture[2*c+1])
    }
}
