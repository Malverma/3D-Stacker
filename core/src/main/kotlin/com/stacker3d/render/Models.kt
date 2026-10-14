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

    /** Equipped block look (shop "Blocks" tab). Applies to instances created after it changes. */
    var skin: BlockSkin = Catalog.skins.first()
        set(value) {
            field = value
            skinColor = value.rgb?.let { Color(it shl 8 or 0xFF) }
        }
    private var skinColor: Color? = null

    fun colorOf(type: PieceType): Color = skinColor ?: type.color

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
        builder.end()
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

    fun modelFor(type: PieceType): Model = when (type.kind) {
        PieceType.Kind.BOMB -> bomb
        PieceType.Kind.X -> xBlock
        PieceType.Kind.HEART -> heartBlock
        PieceType.Kind.NORMAL -> cube
    }

    /** A new cube instance for [type], tinted with its color if it's a plain cube. */
    fun instance(type: PieceType): ModelInstance = ModelInstance(modelFor(type)).also {
        if (type.kind == PieceType.Kind.NORMAL) it.diffuse().set(colorOf(type))
    }

    /** Model for cell [i] of a falling piece: X pieces have one X cube, the rest look like their base shape. */
    fun cellModel(type: PieceType, i: Int): Model = if (type.isX(i)) xBlock else modelFor(type.stackType)

    fun cellInstance(type: PieceType, i: Int): ModelInstance =
        if (type.isX(i)) ModelInstance(xBlock) else instance(type.stackType)

    override fun dispose() {
        listOf(cube, bomb, xBlock, heartBlock, ghost, pillar, board).forEach { it.dispose() }
        textures.forEach { it.dispose() }
    }
}
