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

const val CUBE_SIZE = 0.94f
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
        val base = builder.part("base", GL20.GL_TRIANGLES, ATTRS, Material(ColorAttribute.createDiffuse(Color.valueOf("1B2A4A"))))
        BoxShapeBuilder.build(base, 0f, -0.31f, 0f, 3.36f, 0.5f, 3.36f)
        val tiles = builder.part("tiles", GL20.GL_TRIANGLES, ATTRS, Material(ColorAttribute.createDiffuse(Color.valueOf("E9F4FF"))))
        for (x in 0..2) for (z in 0..2) BoxShapeBuilder.build(tiles, x - 1f, -0.04f, z - 1f, 0.92f, 0.08f, 0.92f)
        // Raised frame along the slab's top edge, just outside the tiles.
        val rim = builder.part("rim", GL20.GL_TRIANGLES, ATTRS, Material(ColorAttribute.createDiffuse(Color.valueOf("FFD23F"))))
        BoxShapeBuilder.build(rim, 0f, -0.01f, 1.6f, 3.36f, 0.1f, 0.16f)
        BoxShapeBuilder.build(rim, 0f, -0.01f, -1.6f, 3.36f, 0.1f, 0.16f)
        BoxShapeBuilder.build(rim, 1.6f, -0.01f, 0f, 0.16f, 0.1f, 3.04f)
        BoxShapeBuilder.build(rim, -1.6f, -0.01f, 0f, 0.16f, 0.1f, 3.04f)
        builder.end()
    }

    /** Paints a [board] instance (materials: base, tiles, rim) in the equipped skin's board colors. */
    fun paintBoard(instance: ModelInstance) {
        val style = skin.board
        listOf(style.base, style.tiles, style.rim).forEachIndexed { i, rgb ->
            (instance.materials[i].get(ColorAttribute.Diffuse) as ColorAttribute).color.set(rgb shl 8 or 0xFF)
        }
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
        PieceType.Kind.NORMAL -> plainModels[Math.floorMod(mix(look), plainModels.size)]
    }

    /** A new cube instance for [type], tinted with its color if it's a plain cube. [look] seeds skin variety. */
    fun instance(type: PieceType, look: Int = 0): ModelInstance = ModelInstance(modelFor(type, look)).also {
        if (type.kind == PieceType.Kind.NORMAL) it.diffuse().set(colorOf(type, look))
    }

    override fun dispose() {
        skinModels.dispose()
        listOf(cube, bomb, xBlock, heartBlock, goldBlock, multiplierBlock, crackedBlock, mysteryBlock, ghost, pillar, board)
            .forEach { it.dispose() }
        textures.forEach { it.dispose() }
    }
}
