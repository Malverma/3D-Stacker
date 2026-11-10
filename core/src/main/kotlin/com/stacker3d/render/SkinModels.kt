package com.stacker3d.render

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttributes.Usage
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.graphics.g3d.utils.shapebuilders.BoxShapeBuilder
import com.badlogic.gdx.graphics.g3d.utils.shapebuilders.SphereShapeBuilder
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable
import com.stacker3d.shop.BlockSkin
import com.stacker3d.shop.BlockStyle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Id of the node holding a melting cube's drips; scale it on y to grow them (see [setDrips]). */
private const val DRIPS = "drips"

/** Grows a melting cube's drips: 0 hides them inside the cube, 1 is full length. No-op for other cubes. */
fun ModelInstance.setDrips(length: Float) {
    val node = getNode(DRIPS, false) ?: return
    node.scale.set(1f, max(length, 0.01f), 1f)
    calculateTransforms()
}

/**
 * Plain-cube models for the fancier block skins, built the first time a skin is equipped. Each skin gets a few
 * variants per [BlockStyle] in different shades; melting skins use a sagging gum shape with drips.
 */
class SkinModels : Disposable {
    private val builder = ModelBuilder()
    private val sets = HashMap<String, List<Variant>>()

    private class Variant(val model: Model, val texture: Texture?)

    /** Models a [skin]'s plain cubes pick from, or null when it uses the plain colored cube. */
    fun models(skin: BlockSkin): List<Model>? = variants(skin)?.map { it.model }

    /**
     * Face texture of variant [i], already in its shade. Variants cycle through the skin's styles, so the first
     * few show one of each (the shop swatch draws these).
     */
    fun texture(skin: BlockSkin, i: Int): Texture? = variants(skin)?.let { it[i % it.size].texture }

    private fun variants(skin: BlockSkin): List<Variant>? {
        if (skin.styles.isEmpty() && !skin.melts && skin.opacity >= 1f) return null
        val rgb = skin.rgb ?: return null
        return sets.getOrPut(skin.id) {
            val base = Color(rgb shl 8 or 0xFF)
            val styles: List<BlockStyle?> = skin.styles.ifEmpty { listOf(null) }
            val count = if (skin.styles.isEmpty() && !skin.melts) 1 else styles.size * SHADES_PER_STYLE
            List(count) { i ->
                val style = styles[i % styles.size]
                val seed = 1000 + i * 7919
                val color = styleColor(style, if (count > 1) shade(base, skin.variety, seed, Color()) else Color(base))
                val texture = if (style != null) faceTexture(style, color, seed) else null
                Variant(cubeModel(skin, style, color, texture, seed), texture)
            }
        }
    }

    private fun styleColor(style: BlockStyle?, c: Color): Color {
        if (style != BlockStyle.HOT) return c
        // A little deeper and more saturated, leaning slightly toward magenta.
        val hsv = c.toHsv(FloatArray(3))
        return c.fromHsv((hsv[0] - 4f + 360f) % 360f, min(1f, hsv[1] * 1.12f), hsv[2] * 0.93f)
    }

    private fun opacity(skin: BlockSkin, style: BlockStyle?): Float = when (style) {
        BlockStyle.CLEAR -> skin.opacity * 0.93f
        BlockStyle.CORE -> skin.opacity * 0.96f
        BlockStyle.CLOUDY -> min(0.95f, skin.opacity * 1.04f)
        else -> skin.opacity
    }

    private fun material(color: Color, texture: Texture?, opacity: Float, shininess: Float) = Material(
        ColorAttribute.createDiffuse(if (texture != null) Color.WHITE else color),
        ColorAttribute.createSpecular(0.7f, 0.72f, 0.7f, 1f),
        FloatAttribute.createShininess(shininess),
    ).apply {
        if (texture != null) set(TextureAttribute.createDiffuse(texture))
        if (opacity < 1f) set(BlendingAttribute(true, opacity))
    }

    private fun cubeModel(skin: BlockSkin, style: BlockStyle?, color: Color, texture: Texture?, seed: Int): Model {
        val random = Random(seed)
        val attrs = (Usage.Position or Usage.Normal or (if (texture != null) Usage.TextureCoordinates else 0)).toLong()
        builder.begin()
        builder.node().id = "body"
        // The body is the first part, so its material is the one instances tint.
        val bodyMat = material(color, texture, opacity(skin, style), if (skin.melts) 16f else 24f)
        val body = builder.part("body", GL20.GL_TRIANGLES, attrs, bodyMat)
        if (skin.melts) meltedBox(body) else BoxShapeBuilder.build(body, CUBE_SIZE, CUBE_SIZE, CUBE_SIZE)
        val plain = (Usage.Position or Usage.Normal).toLong()
        val m = Matrix4()
        when (style) {
            BlockStyle.CORE -> {
                // A darker, lumpy blob floating inside the jelly: two overlapping spheres.
                val core = builder.part("core", GL20.GL_TRIANGLES, plain, material(Color(color).mul(0.62f, 0.66f, 0.62f, 1f), null, 1f, 30f))
                val d = 0.34f + random.nextFloat() * 0.1f
                m.setToTranslation(random.nextFloat() * 0.08f - 0.04f, random.nextFloat() * 0.08f - 0.04f, random.nextFloat() * 0.08f - 0.04f)
                SphereShapeBuilder.build(core, m, d, d * 0.9f, d, 14, 10)
                m.translate(0.09f, 0.07f, -0.06f)
                SphereShapeBuilder.build(core, m, d * 0.7f, d * 0.7f, d * 0.7f, 12, 8)
            }
            else -> {}
        }
        if (skin.melts) addDrips(color, random)
        return builder.end()
    }

    /**
     * Drips hang off the sides near the bottom edge and run a little way down over the cube below. Their node sits
     * just above the bottom edge, so scaling it on y pulls them up into the cube.
     */
    private fun addDrips(color: Color, random: Random) {
        val count = listOf(0, 1, 1, 2, 2, 3)[random.nextInt(6)]
        if (count == 0) return
        val node = builder.node()
        node.id = DRIPS
        node.translation.set(0f, -H * 0.45f, 0f)
        // A touch darker than the base, matching the shaded faces near the bottom edge.
        val mat = material(Color(color).mul(0.85f, 0.85f, 0.85f, 1f), null, 1f, 16f)
        val part = builder.part(DRIPS, GL20.GL_TRIANGLES, (Usage.Position or Usage.Normal).toLong(), mat)
        val m = Matrix4()
        val sides = listOf(0, 1, 2, 3).shuffled(random)
        for (k in 0 until count) {
            val side = sides[k]
            val along = random.nextFloat() * 0.5f - 0.25f
            val length = 0.34f + random.nextFloat() * 0.16f
            // Out from the face, which bulges near the bottom.
            val out = H * (1f + BULGE * 0.8f) + 0.006f
            val (x, z) = when (side) {
                0 -> out to along
                1 -> -out to along
                2 -> along to out
                else -> along to -out
            }
            val onX = side < 2
            m.setToTranslation(x, -length / 2f + 0.04f, z)
            SphereShapeBuilder.build(part, m, if (onX) 0.09f else 0.15f, length, if (onX) 0.15f else 0.09f, 12, 10)
            m.setToTranslation(x * 1.02f, -length + 0.08f, z * 1.02f)
            SphereShapeBuilder.build(part, m, 0.17f, 0.19f, 0.17f, 12, 10)
        }
    }

    /** A gum cube: corners of the top droop, the sides bulge out low down, and the vertical edges round off. */
    private fun meltedBox(part: MeshPartBuilder) {
        val faces = listOf(
            Triple(Vector3.Y, Vector3.X, Vector3(0f, 0f, -1f)),
            Triple(Vector3(0f, -1f, 0f), Vector3.X, Vector3.Z),
            Triple(Vector3.X, Vector3(0f, 0f, -1f), Vector3.Y),
            Triple(Vector3(-1f, 0f, 0f), Vector3.Z, Vector3.Y),
            Triple(Vector3.Z, Vector3.X, Vector3.Y),
            Triple(Vector3(0f, 0f, -1f), Vector3(-1f, 0f, 0f), Vector3.Y),
        )
        val n = 8
        val p = Vector3()
        val du = Vector3()
        val dv = Vector3()
        val nor = Vector3()
        val info = MeshPartBuilder.VertexInfo()
        val e = 0.01f
        for ((normal, u, v) in faces) {
            val idx = Array(n + 1) { ShortArray(n + 1) }
            for (j in 0..n) for (i in 0..n) {
                val s = i * 2f / n - 1f
                val t = j * 2f / n - 1f
                facePoint(normal, u, v, s, t, p)
                facePoint(normal, u, v, s + e, t, du).sub(facePoint(normal, u, v, s - e, t, nor))
                facePoint(normal, u, v, s, t + e, dv).sub(facePoint(normal, u, v, s, t - e, nor))
                nor.set(du).crs(dv).nor()
                info.setPos(p).setNor(nor).setUV((s + 1f) / 2f, 1f - (t + 1f) / 2f)
                idx[j][i] = part.vertex(info)
            }
            for (j in 0 until n) for (i in 0 until n) {
                part.triangle(idx[j][i], idx[j][i + 1], idx[j + 1][i + 1])
                part.triangle(idx[j][i], idx[j + 1][i + 1], idx[j + 1][i])
            }
        }
    }

    /** Point (s, t) in [-1, 1]² on the box face facing [normal], pushed into the melted shape. */
    private fun facePoint(normal: Vector3, u: Vector3, v: Vector3, s: Float, t: Float, out: Vector3): Vector3 {
        out.set(normal).scl(H).mulAdd(u, s * H).mulAdd(v, t * H)
        val ty = (out.y / H + 1f) / 2f
        val ax = abs(out.x) / H
        val az = abs(out.z) / H
        val corner = max(0f, minOf(ax, az) - 0.6f) / 0.4f
        // Widest a little above the base, like something soft pressed from above.
        val low = 1f - ty
        val spread = (1f + BULGE * low * (2f - low)) * (1f - ROUND * corner * corner)
        val droop = SAG * H * (ax * ax + az * az) / 2f * max(0f, ty * 2f - 1f).let { it * it }
        out.x *= spread
        out.z *= spread
        out.y -= droop
        return out
    }

    /** A face in [color], a bit darker toward the edges, decorated for [style]. [seed] picks the layout. */
    private fun faceTexture(style: BlockStyle, color: Color, seed: Int): Texture {
        val n = 128
        val random = Random(seed)
        val pm = Pixmap(n, n, Pixmap.Format.RGBA8888).apply { blending = Pixmap.Blending.None }
        val c = Color()
        val half = (n - 1) / 2f
        val deep = Color(color).mul(0.7f, 0.62f, 0.7f, 1f)
        val p1 = random.nextFloat() * 6f
        val p2 = random.nextFloat() * 6f
        val p3 = random.nextFloat() * 6f
        for (y in 0 until n) for (x in 0 until n) {
            val e = max(abs(x - half), abs(y - half)) / half
            val v = 0.95f - 0.3f * e * e * e
            c.set(color.r * v, color.g * v, color.b * v, 1f)
            if (style == BlockStyle.CLOUDY || style == BlockStyle.SWIRL) {
                val f = MathUtils.sin(x * 0.07f + 2.2f * MathUtils.sin(y * 0.045f + p1) + p2) *
                    MathUtils.cos(y * 0.06f + 1.5f * MathUtils.sin(x * 0.05f + p3))
                val band = MathUtils.clamp((f - 0.3f) / 0.45f, 0f, 1f)
                if (style == BlockStyle.CLOUDY) {
                    c.lerp(Color.WHITE, 0.4f * band)
                } else {
                    c.lerp(Color.WHITE, 0.5f * band)
                    c.lerp(deep, 0.35f * MathUtils.clamp((-f - 0.45f) / 0.35f, 0f, 1f))
                }
                c.a = 1f
            }
            pm.drawPixel(x, y, Color.rgba8888(c))
        }
        val inside = Color(color).lerp(Color.WHITE, 0.85f).also { it.a = 0.75f }
        fun bubble(bx: Int, by: Int, r: Int) {
            pm.setColor(Color.WHITE)
            pm.fillCircle(bx, by, r)
            if (r >= 3) {
                pm.setColor(inside)
                pm.fillCircle(bx, by, r - max(1, r / 4))
                pm.setColor(Color.WHITE)
                pm.fillCircle(bx - r * 2 / 5, by - r * 2 / 5, max(1, r / 4))
            }
        }
        fun anywhere(r: Int) = random.nextInt(r + 10, n - r - 10)
        fun scatter(count: Int, minR: Int, maxR: Int) = repeat(count) {
            val r = random.nextInt(minR, maxR + 1)
            bubble(anywhere(r), anywhere(r), r)
        }
        when (style) {
            BlockStyle.BUBBLY -> {
                scatter(random.nextInt(1, 3), 6, 9)
                scatter(random.nextInt(2, 4), 3, 5)
                // A short trail of tiny ones.
                var bx = anywhere(3).toFloat()
                var by = anywhere(3).toFloat()
                val angle = random.nextFloat() * MathUtils.PI2
                repeat(3) {
                    bubble(bx.toInt(), by.toInt(), random.nextInt(1, 3))
                    bx = (bx + MathUtils.cos(angle) * 7f).coerceIn(10f, n - 10f)
                    by = (by + MathUtils.sin(angle) * 7f).coerceIn(10f, n - 10f)
                }
            }
            BlockStyle.CLEAR -> scatter(random.nextInt(0, 3), 2, 3)
            BlockStyle.CORE -> scatter(random.nextInt(1, 3), 2, 4)
            BlockStyle.CLOUDY -> scatter(random.nextInt(1, 3), 3, 5)
            BlockStyle.SWIRL -> scatter(random.nextInt(0, 2), 3, 5)
            BlockStyle.HOT -> scatter(random.nextInt(6, 11), 1, 2)
        }
        return Texture(pm, true).apply {
            setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear)
            pm.dispose()
        }
    }

    override fun dispose() {
        for (set in sets.values) for (v in set) {
            v.model.dispose()
            v.texture?.dispose()
        }
        sets.clear()
    }

    companion object {
        /** Variants per style, each in its own shade. */
        const val SHADES_PER_STYLE = 2
        private const val H = CUBE_SIZE / 2f
        /** How far the top corners droop, as a fraction of half the cube. */
        private const val SAG = 0.2f
        /** How much wider the belly of a gum cube is. */
        private const val BULGE = 0.09f
        private const val ROUND = 0.1f

        private val hsv = FloatArray(3)

        /** [base] with its hue and brightness nudged by [seed], [variety] (0..1) saying how far. */
        fun shade(base: Color, variety: Float, seed: Int, out: Color): Color {
            out.set(base)
            if (variety <= 0f) return out
            val h = mix(seed)
            val shift = ((h and 0xFF) / 255f - 0.5f) * 2f
            val bright = ((h ushr 8 and 0xFF) / 255f - 0.5f) * 2f
            out.toHsv(hsv)
            out.fromHsv((hsv[0] + 16f * variety * shift + 360f) % 360f, hsv[1], hsv[2])
            val v = 1f - 0.1f * variety + 0.12f * variety * bright
            out.r *= v; out.g *= v; out.b *= v
            return out
        }
    }
}

/** Scrambles a look seed so neighboring seeds pick unrelated layouts and shades. */
fun mix(seed: Int): Int {
    var h = seed * -0x61c88647
    h = h xor (h ushr 15)
    h *= 0x2c1b3c6d
    return h xor (h ushr 12)
}
