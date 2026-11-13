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
import com.stacker3d.logic.PieceSpec
import kotlin.math.cos

/** Renders the "next piece" into an offscreen texture for the HUD. */
class PiecePreview(private val models: Models, private val size: Int = 256) : Disposable {
    private val fbo = FrameBuffer(Pixmap.Format.RGBA8888, size, size, true)
    private val cam = PerspectiveCamera(30f, size.toFloat(), size.toFloat())
    private val batch = ModelBatch()
    private val env = lightEnvironment()
    private val instances = HashMap<PieceSpec, List<ModelInstance>>()
    val region = TextureRegion(fbo.colorBufferTexture).apply { flip(false, true) }

    fun render(spec: PieceSpec) {
        val cells = spec.cells
        val cx = (Orientation.extent(cells) { it.x } - 1) / 2f
        val cz = (Orientation.extent(cells) { it.z } - 1) / 2f
        val p = 30f * MathUtils.degreesToRadians
        val yaw = 45f * MathUtils.degreesToRadians
        // Pull back for No Fall's huge pieces so they still fit the box.
        val longest = maxOf(Orientation.extent(cells) { it.x }, Orientation.extent(cells) { it.y }, Orientation.extent(cells) { it.z })
        val d = 7.5f * maxOf(1f, longest / 3f)
        val cy = (Orientation.extent(cells) { it.y } - 1) / 2f + 0.5f
        cam.position.set(cx + MathUtils.sin(yaw) * cos(p) * d, cy + MathUtils.sin(p) * d, cz + MathUtils.cos(yaw) * cos(p) * d)
        cam.up.set(0f, 1f, 0f)
        cam.lookAt(cx, cy, cz)
        cam.update()

        // Attached specials make many distinct pieces; keep only a handful around.
        if (spec !in instances && instances.size > 24) instances.clear()
        val list = instances.getOrPut(spec) { cells.indices.map { models.instance(spec.cellType(it)) } }
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
