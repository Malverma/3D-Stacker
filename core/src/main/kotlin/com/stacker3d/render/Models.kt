package com.stacker3d.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttributes.Usage
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.graphics.g3d.utils.shapebuilders.BoxShapeBuilder
import com.badlogic.gdx.utils.Disposable
import com.stacker3d.logic.PieceType
import com.stacker3d.shop.BlockSkin
import com.stacker3d.shop.Catalog

private val pieceColors = PieceType.entries.map { Color(it.rgb shl 8 or 0xFF) }
val PieceType.color: Color get() = pieceColors[ordinal]

const val CUBE_SIZE = 0.97f
private const val ATTRS = (Usage.Position or Usage.Normal).toLong()

fun lightEnvironment() = Environment().apply {
    set(ColorAttribute(ColorAttribute.AmbientLight, 0.5f, 0.52f, 0.58f, 1f))
    add(DirectionalLight().set(0.8f, 0.78f, 0.72f, -0.3f, -1f, -0.62f))
}

fun ModelInstance.diffuse(): Color = (materials[0].get(ColorAttribute.Diffuse) as ColorAttribute).color

fun ModelInstance.blending(): BlendingAttribute = materials[0].get(BlendingAttribute.Type) as BlendingAttribute

/** Every 3D model in the game, built once and shared by all screens. */
class Models : Disposable {
    private val builder = ModelBuilder()
    private val textures = ArrayList<Texture>()

    val cube: Model = builder.createBox(CUBE_SIZE, CUBE_SIZE, CUBE_SIZE, Material(ColorAttribute.createDiffuse(Color.WHITE)), ATTRS)
    val bomb = texturedCube("textures/cube_bomb.png")
    val xBlock = texturedCube("textures/cube_x.png")
    val heartBlock = texturedCube("textures/cube_heart.png")
    val goldBlock = texturedCube("textures/cube_gold.png")
    val multiplierBlock = texturedCube("textures/cube_multiplier.png")
    val crackedBlock = texturedCube("textures/cube_cracked.png")
    val mysteryBlock = texturedCube("textures/cube_mystery.png")
    val dynamiteBlock = texturedCube("textures/cube_dynamite.png")
    val glassBlock = texturedCube("textures/cube_glass.png")
    val freezeBlock = texturedCube("textures/cube_freeze.png")

    /** Jelly, bubble and gum cubes for the fancier skins. */
    private val skinModels = SkinModels()

    /** Plain-cube models for the equipped skin; cubes pick one by their look seed. */
    private var plainModels: List<Model> = listOf(cube)

    /** Equipped block look (shop "Blocks" tab). Applies to instances created after it changes. */
    var skin: BlockSkin = Catalog.skins.first()
        set(value) {
            field = value
            skinColor = value.rgb?.let { Color(it shl 8 or 0xFF) }
            plainModels = skinModels.models(value) ?: listOf(cube)
        }
    private var skinColor: Color? = null
    private val tint = Color()

    /**
     * Color of a plain [type] cube; skins with variety nudge each cube's shade by its [look] seed. Styled and
     * melting skins bake their shades into their models, so this is then white. Shared; copy it.
     */
    fun colorOf(type: PieceType, look: Int = 0): Color {
        if (skin.styles.isNotEmpty() || skin.melts) return tint.set(Color.WHITE)
        return SkinModels.shade(skinColor ?: type.color, skin.variety, look, tint)
    }

    /** Face texture of a styled skin's [i]th block look (the shop swatch draws these). */
    fun faceTexture(skin: BlockSkin, i: Int): Texture? = skinModels.texture(skin, i)

    /** Translucent cube used for the landing ghost and the explosion flash. */
    val ghost: Model = builder.createBox(
        CUBE_SIZE, CUBE_SIZE, CUBE_SIZE,
        Material(ColorAttribute.createDiffuse(Color.WHITE), BlendingAttribute(true, 0.35f)), ATTRS,
    )

    /** Unit translucent box, scaled to mark columns that are falling behind. */
    val pillar: Model = builder.createBox(
        1f, 1f, 1f,
        Material(ColorAttribute.createDiffuse(Color(1f, 0.2f, 0.25f, 1f)), BlendingAttribute(true, 0.3f)), ATTRS,
    )

    val board: Model = run {
        builder.begin()
        val base = builder.part("base", GL20.GL_TRIANGLES, ATTRS, Material(ColorAttribute.createDiffuse(Color.valueOf("2B3A67"))))
        BoxShapeBuilder.build(base, 0f, -0.27f, 0f, 3.36f, 0.42f, 3.36f)
        val tiles = builder.part("tiles", GL20.GL_TRIANGLES, ATTRS, Material(ColorAttribute.createDiffuse(Color.valueOf("F2F6FC"))))
        val tilesAlt = builder.part("tilesAlt", GL20.GL_TRIANGLES, ATTRS, Material(ColorAttribute.createDiffuse(Color.valueOf("DCE5F2"))))
        for (x in 0..2) for (z in 0..2) {
            BoxShapeBuilder.build(if ((x + z) % 2 == 0) tiles else tilesAlt, x - 1f, -0.04f, z - 1f, 0.96f, 0.08f, 0.96f)
        }
        // Raised frame along the slab's top edge, just outside the tiles.
        val rim = builder.part("rim", GL20.GL_TRIANGLES, ATTRS, Material(ColorAttribute.createDiffuse(Color.valueOf("5B7CFA"))))
        BoxShapeBuilder.build(rim, 0f, -0.01f, 1.6f, 3.36f, 0.1f, 0.16f)
        BoxShapeBuilder.build(rim, 0f, -0.01f, -1.6f, 3.36f, 0.1f, 0.16f)
        BoxShapeBuilder.build(rim, 1.6f, -0.01f, 0f, 0.16f, 0.1f, 3.04f)
        BoxShapeBuilder.build(rim, -1.6f, -0.01f, 0f, 0.16f, 0.1f, 3.04f)
        // Wider, darker foot under the slab so the board reads as a solid plinth.
        val foot = builder.part("foot", GL20.GL_TRIANGLES, ATTRS, Material(ColorAttribute.createDiffuse(Color.valueOf("1A2442"))))
        BoxShapeBuilder.build(foot, 0f, -0.62f, 0f, 3.56f, 0.28f, 3.56f)
        builder.end()
    }

    /** One cell of a grown board (No Fall mode), centered on its cube: materials base, tile, foot. */
    val boardTile: Model = run {
        builder.begin()
        val base = builder.part("base", GL20.GL_TRIANGLES, ATTRS, Material(ColorAttribute.createDiffuse(Color.valueOf("2B3A67"))))
        BoxShapeBuilder.build(base, 0f, -0.27f, 0f, 1f, 0.42f, 1f)
        val tile = builder.part("tile", GL20.GL_TRIANGLES, ATTRS, Material(ColorAttribute.createDiffuse(Color.valueOf("F2F6FC"))))
        BoxShapeBuilder.build(tile, 0f, -0.04f, 0f, 0.96f, 0.08f, 0.96f)
        val foot = builder.part("foot", GL20.GL_TRIANGLES, ATTRS, Material(ColorAttribute.createDiffuse(Color.valueOf("1A2442"))))
        BoxShapeBuilder.build(foot, 0f, -0.62f, 0f, 1.06f, 0.28f, 1.06f)
        builder.end()
    }

    /**
     * Rim along one open side of a grown board's tile (No Fall mode), placed on the tile's +Z side: the slab lip,
     * the raised colored rim and the foot, matching the 3×3 board's frame. Materials: base, rim, foot.
     */
    val boardEdge: Model = rimPiece(1f)

    /** Fills the outer corner between two [boardEdge]s, on the tile's +X/+Z corner. Same materials. */
    val boardCorner: Model = rimPiece(null)

    /** [length] along X for an edge piece, or null for a square corner piece offset in X too. */
    private fun rimPiece(length: Float?): Model {
        builder.begin()
        fun box(part: com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder, y: Float, h: Float, from: Float, to: Float, overhang: Float) {
            val c = (from + to) / 2f
            val w = to - from
            if (length != null) BoxShapeBuilder.build(part, 0f, y, c, length + overhang, h, w)
            else BoxShapeBuilder.build(part, c, y, c, w, h, w)
        }
        val base = builder.part("base", GL20.GL_TRIANGLES, ATTRS, Material(ColorAttribute.createDiffuse(Color.valueOf("2B3A67"))))
        box(base, -0.27f, 0.42f, 0.5f, 0.68f, 0f)
        val rim = builder.part("rim", GL20.GL_TRIANGLES, ATTRS, Material(ColorAttribute.createDiffuse(Color.valueOf("5B7CFA"))))
        box(rim, -0.01f, 0.1f, 0.52f, 0.68f, 0f)
        val foot = builder.part("foot", GL20.GL_TRIANGLES, ATTRS, Material(ColorAttribute.createDiffuse(Color.valueOf("1A2442"))))
        box(foot, -0.62f, 0.28f, 0.53f, 0.78f, 0.06f)
        return builder.end()
    }

    /** Paints a [boardEdge] or [boardCorner] instance in the equipped skin's board colors. */
    fun paintRim(instance: ModelInstance) {
        val style = skin.board
        listOf(style.base, style.rim, shade(style.base, 0.65f)).forEachIndexed { i, rgb ->
            (instance.materials[i].get(ColorAttribute.Diffuse) as ColorAttribute).color.set(rgb shl 8 or 0xFF)
        }
    }

    /** Paints a [boardTile] instance in the equipped skin's board colors; [alt] is the darker checker tile. */
    fun paintTile(instance: ModelInstance, alt: Boolean) {
        val style = skin.board
        val tile = if (alt) shade(style.tiles, 0.92f) else style.tiles
        listOf(style.base, tile, shade(style.base, 0.65f)).forEachIndexed { i, rgb ->
            (instance.materials[i].get(ColorAttribute.Diffuse) as ColorAttribute).color.set(rgb shl 8 or 0xFF)
        }
    }

    /** Paints a [board] instance (materials: base, tiles, tilesAlt, rim, foot) in the equipped skin's board colors. */
    fun paintBoard(instance: ModelInstance) {
        val style = skin.board
        listOf(style.base, style.tiles, shade(style.tiles, 0.92f), style.rim, shade(style.base, 0.65f)).forEachIndexed { i, rgb ->
            (instance.materials[i].get(ColorAttribute.Diffuse) as ColorAttribute).color.set(rgb shl 8 or 0xFF)
        }
    }

    /** Scales each channel of 0xRRGGBB by [f]. */
    private fun shade(rgb: Int, f: Float): Int {
        fun ch(shift: Int) = ((rgb shr shift and 0xFF) * f).toInt().coerceIn(0, 255) shl shift
        return ch(16) or ch(8) or ch(0)
    }

    private fun texturedCube(path: String): Model {
        val tex = Texture(Gdx.files.internal(path), true).apply {
            setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear)
        }
        textures += tex
        return builder.createBox(
            CUBE_SIZE, CUBE_SIZE, CUBE_SIZE,
            Material(ColorAttribute.createDiffuse(Color.WHITE), TextureAttribute.createDiffuse(tex)),
            ATTRS or Usage.TextureCoordinates.toLong(),
        )
    }

    /** Model for a [type] cube; plain cubes of a bubbly skin use the bubble layout picked by [look]. */
    fun modelFor(type: PieceType, look: Int = 0): Model = when (type.kind) {
        PieceType.Kind.BOMB -> bomb
        PieceType.Kind.X -> xBlock
        PieceType.Kind.HEART -> heartBlock
        PieceType.Kind.GOLD -> goldBlock
        PieceType.Kind.MULTIPLIER -> multiplierBlock
        PieceType.Kind.CRACKED -> crackedBlock
        PieceType.Kind.MYSTERY -> mysteryBlock
        PieceType.Kind.DYNAMITE -> dynamiteBlock
        PieceType.Kind.GLASS -> glassBlock
        PieceType.Kind.FREEZE -> freezeBlock
        PieceType.Kind.NORMAL -> plainModels[Math.floorMod(mix(look), plainModels.size)]
    }

    /** A new cube instance for [type], tinted with its color if it's a plain cube. [look] seeds skin variety. */
    fun instance(type: PieceType, look: Int = 0): ModelInstance = ModelInstance(modelFor(type, look)).also {
        if (type.kind == PieceType.Kind.NORMAL) it.diffuse().set(colorOf(type, look))
    }

    override fun dispose() {
        skinModels.dispose()
        listOf(cube, bomb, xBlock, heartBlock, goldBlock, multiplierBlock, crackedBlock, mysteryBlock, dynamiteBlock, glassBlock, freezeBlock, ghost, pillar, board, boardTile,
            boardEdge, boardCorner)
            .forEach { it.dispose() }
        textures.forEach { it.dispose() }
    }
}
