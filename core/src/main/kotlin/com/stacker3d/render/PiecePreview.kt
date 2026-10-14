package com.stacker3d.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.utils.Disposable
import com.stacker3d.logic.Orientation
import com.stacker3d.logic.PieceType
import kotlin.math.cos

/** Renders the "next piece" into an offscreen texture for the HUD. */
class PiecePreview(private val models: Models, private val size: Int = 256) : Disposable {
    private val fbo = FrameBuffer(Pixmap.Format.RGBA8888, size, size, true)
    private val cam = PerspectiveCamera(30f, size.toFloat(), size.toFloat())
    private val batch = ModelBatch()
    private val env = lightEnvironment()
    private val instances = HashMap<PieceType, List<ModelInstance>>()
    val region = TextureRegion(fbo.colorBufferTexture).apply { flip(false, true) }

    fun render(type: PieceType) {
        val cells = type.cells
        val cx = (Orientation.extent(cells) { it.x } - 1) / 2f
        val cz = (Orientation.extent(cells) { it.z } - 1) / 2f
        val p = 30f * MathUtils.degreesToRadians
        val yaw = 45f * MathUtils.degreesToRadians
        val d = 7.5f
        cam.position.set(cx + MathUtils.sin(yaw) * cos(p) * d, 0.5f + MathUtils.sin(p) * d, cz + MathUtils.cos(yaw) * cos(p) * d)
        cam.up.set(0f, 1f, 0f)
        cam.lookAt(cx, 0.5f, cz)
        cam.update()

        val list = instances.getOrPut(type) { cells.indices.map { models.cellInstance(type, it) } }
        fbo.begin()
        Gdx.gl.glClearColor(0f, 0f, 0f, 0f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
        batch.begin(cam)
        for ((i, c) in cells.withIndex()) {
            val inst = list[i]
            inst.transform.setToTranslation(c.x.toFloat(), c.y + 0.5f, c.z.toFloat())
            batch.render(inst, env)
        }
        batch.end()
        fbo.end()
    }

    override fun dispose() {
        fbo.dispose()
        batch.dispose()
    }
}
