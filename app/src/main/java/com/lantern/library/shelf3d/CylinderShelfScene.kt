package com.lantern.library.shelf3d

import android.graphics.Color
import android.util.Log
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.IndexBuffer
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * The bookcase scene: books standing around a drum, a wooden plinth, a sun and a soft ambient
 * fill, all driven by [CylinderRotationController].
 *
 * Lighting is deliberately two-part. A single directional light gives shape — the falloff
 * across a curved drum is what reads as curvature — and a first-order spherical-harmonic
 * indirect light stops the unlit side going to pure black, which is what makes a lit PBR scene
 * look like a flat silhouette in a dark room. Neither needs a cubemap, so there are no
 * .ktx assets to ship or generate.
 */
class CylinderShelfScene(
    private val bookCount: () -> Int,
    private val controller: CylinderRotationController,
    private val materialBytes: Map<String, ByteArray>,
    /** Colours for each book, supplied by the app so spines match the library's covers. */
    private val bookColors: List<Int>,
    /** Cover art for the spines, or null to draw plain bindings. */
    private val spineAtlas: SpineAtlas? = null,
    /** Tiers of the case. More tiers on a taller phone, so the case fills the screen. */
    private val rows: Int = 1,
    /**
     * Books picked for the reader, in the order they should stand in the bay.
     *
     * The bay is a reserved space at the front of the bottom tier: these books stand there, then
     * one empty slot, then the rest of the library carries on round the drum. Nothing is painted
     * on a spine to mark a recommendation: the recommendation has a shelf of its own.
     */
    private val picks: List<Int> = emptyList()
) : FilamentShelfEngine.ShelfContent {



    private var engine: Engine? = null
    private var resources: ShelfResources? = null

    private val bookEntities = ArrayList<Int>(64)
    private val bookInstances = ArrayList<MaterialInstance>(64)
    private val transforms = ArrayList<Int>(64)

    /** Reused every frame so the render loop allocates nothing. */
    private val matrix = FloatArray(16)

    /** Lighting constants, in Filament's physical units (lux). */
    private val sunDirection = floatArrayOf(-0.35f, -0.55f, -0.76f)
    private val sunColor = floatArrayOf(1.0f, 0.94f, 0.86f)
    private var frameNanos = 0L

    var onSceneError: ((String) -> Unit)? = null

    /**
     * Told which book has come to the front whenever that changes.
     *
     * Including while the drum is coasting and settling: a flick that lands on a different book
     * must update the caption, and the DragGestureHandler has no idea where the drum will stop.
     */
    var onFrontBookChanged: ((Int) -> Unit)? = null

    private var lastFrontIndex = -1

    /** Half the horizontal field of view, recomputed whenever the viewport changes shape. */
    private var halfFovH: Float = CylinderMath.halfFovH(aspect = 0.5f)

    /**
     * The drum this scene was built on.
     *
     * Grown from the size of the library so books never overlap, and remembered here because
     * every book position, the carcass, the drag gain and the hit test all have to agree on it.
     */
    private var radius: Float = CylinderMath.DRUM_RADIUS
    private var axis: Float = CylinderMath.AXIS_DISTANCE

    /** Books on each row, and therefore how many slots the drum has. */
    private var perRow: Int = 0

    /**
     * What stands at each position on the drum, in reading order: position `p` is slot
     * `p % slots` of row `p / slots`. A -1 is the empty gap that separates the bay from the rest
     * of the library, and is the whole point of the bay.
     */
    private var placement: IntArray = IntArray(0)

    /** Which position each book entity stands at. */
    private val entityPosition = ArrayList<Int>(64)

    /**
     * Each book's own proportions, as multipliers on the unit box.
     *
     * A real shelf is not a row of identical blocks: bindings differ in thickness by a few
     * millimetres and in height by a centimetre, and that variation is most of what makes a run of
     * spines look collected rather than manufactured. Derived from the book's id, so a book is
     * always the same book.
     */
    private val bookScale = ArrayList<FloatArray>(64)

    /** The recommendation alcove, which follows the drum. */
    private val alcoveParts = ArrayList<AlcovePart>(6)

    private companion object {
        const val TAG = "CylinderShelfScene"

        /** Just below the board the books stand on: the room's floor. */
        val FLOOR_Y = CylinderMath.BOOK_Y - CylinderMath.BOOK_HEIGHT / 2f - 0.035f

        /** Base colours for the room's woodwork, as linear values rather than sRGB. */
        // Linear tints, multiplied by the near-white grain. Dark enough to be walnut, light
        // enough that the sun and the ambient fill actually show shape and grain in them.
        val WOOD_WALL = floatArrayOf(0.34f, 0.21f, 0.13f)
        val WOOD_SHELF = floatArrayOf(0.46f, 0.30f, 0.19f)
        val WOOD_FLOOR = floatArrayOf(0.28f, 0.18f, 0.11f)

        /** Lux. A sunlit room is about a hundred thousand; Filament's camera is set for daylight. */
        const val SUN_LUX = 100_000f

        /** A quarter of the sun, from in front of the case: the shade side, made readable. */
        const val FILL_LUX = 26_000f

        /** The room itself. Low enough that the sun still shapes everything in the case. */
        const val AMBIENT_LUX = 32_000f
    }

    override fun onCreate(engine: Engine, scene: Scene) {
        this.engine = engine
        val res = ShelfResources(engine)
        resources = res

        // The drum's size comes first: everything below is built around it.
        val count = bookCount()
        // One extra position when there is a bay, for the niche between the picks and the rest of
        // the library.
        val bay = if (picks.isEmpty()) 0 else 1
        // Slots are a property of the drum, not of the library: enough of them to go round the
        // circumference at one book's width each, and never fewer than the books that need one.
        // Taking them from the book count instead is what collapsed the case into one block.
        val booksPerRow = CylinderMath.perRowFor(count + bay, rows).coerceAtLeast(1)
        radius = CylinderMath.radiusFor(booksPerRow)
        perRow = CylinderMath.slotsFor(booksPerRow, radius)
        // The drum is sized by the books on ONE row, not by the whole library: the tiers share
        // the same slots, so a tall case holds more books without widening.
        axis = CylinderMath.axisForCase(perRow, rows)
        placement = CylinderMath.placementFor(count, perRow, rows, picks)

        try {
            val boxMesh = ShelfGeometry.box()
            val vertices = vertexBuffer(engine, boxMesh)
            val indices = indexBuffer(engine, boxMesh)
            res.track(vertices)
            res.track(indices)

            val bookMaterial = buildMaterial(engine, ShelfMaterials.BOOK_MATERIAL)
            res.track(bookMaterial)
            val woodMaterial = buildMaterial(engine, ShelfMaterials.WOOD_MATERIAL)
            res.track(woodMaterial)
            val lampMaterial = buildMaterial(engine, ShelfMaterials.LAMP_MATERIAL)
            res.track(lampMaterial)

            // One grain texture for the whole carcass: boards, wall and floor differ only by the
            // colour and by how far the grain is stretched.
            val grain = uploadWoodGrain(engine)
            res.track(grain)

            // The carcass wall stands just behind the backs of the books.
            val wallRadius = radius - CylinderMath.BOOK_DEPTH / 2f - 0.008f

            // The board the books stand on. Its inner edge stops at the wall so the two meet
            // cleanly instead of intersecting.
            // One board per tier: the row's books stand on it.
            for (row in 0 until rows) {
                val boardY = CylinderMath.rowY(row, rows) - CylinderMath.BOOK_HEIGHT / 2f
                val plinthMesh = ShelfGeometry.plinth(
                    radius = radius + 0.006f,
                    topY = boardY,
                    thickness = radius + 0.006f - wallRadius,
                    axisDistance = axis
                )
                addStatic(
                    engine, scene, res, plinthMesh, woodMaterial,
                    center = floatArrayOf(0f, boardY, -axis),
                    halfExtent = floatArrayOf(radius + 0.1f, 0.1f, radius + 0.1f),
                    color = WOOD_SHELF,
                    grain = grain,
                    // Boards: grain running along the shelf, stretched around the ring.
                    grainRepeat = 3.5f
                )
            }

            // The wall behind the books.
            //
            // Two jobs. It hides the books that have turned past the drum's horizon, so the
            // shelf reads as solid instead of as a ring of floating boxes. And it is tall
            // enough to fill the frame: a room-scale backdrop, floor to well above the reader's
            // eye, is the difference between a shelf and a strip of furniture in a black void.
            // The wall spans the whole case and well beyond it, so the frame is filled above
            // and below the books however many tiers there are.
            val wallBottom = FLOOR_Y - 0.4f
            val wallTop = CylinderMath.rowY(rows - 1, rows) + CylinderMath.BOOK_HEIGHT / 2f + 0.55f
            val wallMesh = ShelfGeometry.backboard(
                radius = wallRadius,
                yBottom = wallBottom,
                yTop = wallTop,
                segments = 96,
                axisDistance = axis
            )
            addStatic(
                engine, scene, res, wallMesh, woodMaterial,
                center = floatArrayOf(0f, (wallTop + wallBottom) / 2f, -axis),
                halfExtent = floatArrayOf(wallRadius + 0.1f, (wallTop - wallBottom) / 2f, wallRadius + 0.1f),
                color = WOOD_WALL,
                grain = grain,
                // The wall is tall: stretch the grain vertically so it reads as panelling.
                grainRepeat = 1.6f
            )

            // A floor, so the bottom of the frame is a room rather than a void. Only the strip
            // between the reader and the drum is visible; the rest is behind the wall.
            // The floor reaches past the drum so that no edge of it is ever in shot, however
            // large the library forces the drum to be.
            val floorRadius = radius + 1.6f
            val floorMesh = ShelfGeometry.floor(
                centreZ = -axis,
                y = FLOOR_Y,
                radius = floorRadius
            )
            addStatic(
                engine, scene, res, floorMesh, woodMaterial,
                center = floatArrayOf(0f, FLOOR_Y, -axis),
                halfExtent = floatArrayOf(floorRadius, 0.05f, floorRadius),
                color = WOOD_FLOOR,
                grain = grain,
                grainRepeat = 4.0f
            )

            // Spine artwork. A single 1x1 texture stands in when there is no atlas, because
            // the material declares a sampler and every instance must bind something to it.
            val artwork = uploadAtlas(engine, spineAtlas)
            res.track(artwork)
            addBooks(engine, scene, res, vertices, indices, bookMaterial, artwork)
            // The recommendation alcove: a lit recess at the front of the drum, following it
            // round like a built-in. The picks stand in it.
            addAlcove(
                engine, scene, res, vertices, indices,
                woodMaterial, bookMaterial, lampMaterial, artwork, grain
            )
            addLights(engine, scene, res)
        } catch (t: Throwable) {
            Log.e(TAG, "could not build the scene", t)
            onSceneError?.invoke(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Keeps material payload and mesh buffers alive until the engine has copied them. */
    private val pendingBuffers = ArrayList<ByteBuffer>(8)

    /**
     * Builds a Material from a compiled package.
     *
     * The payload buffer must stay reachable until Filament has copied it, so it is held here;
     * letting it be collected immediately is a classic intermittent crash.
     */
    private fun buildMaterial(engine: Engine, name: String): Material {
        val bytes = materialBytes[name] ?: error("no compiled material called $name")
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.flip()
        pendingBuffers.add(buffer)
        return Material.Builder().payload(buffer, buffer.remaining()).build(engine)
    }

    /**
     * Adds a static, full-circle piece of the carcass (the board the books stand on, or the wall
     * behind them).
     *
     * [center] and [halfExtent] describe a box enclosing the whole piece. They are not optional:
     * frustum culling is on by default, and a wrong box makes the piece vanish.
     */
    private fun addStatic(
        engine: Engine,
        scene: Scene,
        res: ShelfResources,
        mesh: ShelfGeometry.Mesh,
        material: Material,
        center: FloatArray,
        halfExtent: FloatArray,
        color: FloatArray,
        grain: Texture,
        /** Grain repeat per metre of surface, so boards and wall do not share one giant knot. */
        grainRepeat: Float = 2.5f
    ) {
        val instance = material.createInstance().also {
            it.setParameter("baseColor", color[0], color[1], color[2])
            it.setParameter("roughness", 0.62f)
            it.setParameter("metallic", 0.0f)
            it.setParameter("uvScale", grainRepeat, grainRepeat)
            it.setParameter(
                "grainMap",
                grain,
                TextureSampler(TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.REPEAT)
            )
        }
        res.track(instance)
        val vb = res.track(vertexBuffer(engine, mesh))
        val ib = res.track(indexBuffer(engine, mesh))
        val entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib)
            .material(0, instance)
            .boundingBox(Box(center.copyOf(), halfExtent.copyOf()))
            .build(engine, entity)
        scene.addEntity(entity)
        staticEntities.add(entity)
    }

    private val staticEntities = ArrayList<Int>(4)

    /**
     * Uploads the spine atlas, or a single white pixel when there is no artwork.
     *
     * SRGB8_A8 rather than RGBA8 because the atlas holds sRGB cover art: the GPU then converts
     * to linear on sample, which is what keeps leather looking like leather under the sun
     * instead of washing out.
     */
    private fun uploadAtlas(engine: Engine, atlas: SpineAtlas?): Texture {
        val source: android.graphics.Bitmap = atlas?.bitmap ?: android.graphics.Bitmap
            .createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.WHITE) }

        val texture = Texture.Builder()
            .width(source.width)
            .height(source.height)
            // One level: no mipmaps means no need for the GEN_MIPMAPPABLE usage flag.
            .levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.SRGB8_A8)
            .build(engine)

        val bytes = ByteBuffer.allocateDirect(source.width * source.height * 4)
            .order(ByteOrder.nativeOrder())
        source.copyPixelsToBuffer(bytes)
        bytes.flip()
        pendingBuffers.add(bytes)
        texture.setImage(
            engine, 0,
            Texture.PixelBufferDescriptor(bytes, Texture.Format.RGBA, Texture.Type.UBYTE)
        )
        return texture
    }

    /**
     * Uploads the painted grain as a repeating texture.
     *
     * REPEAT rather than CLAMP_TO_EDGE: a board is metres long and the texture is not, so the only
     * way to avoid one enormous knot on every shelf is to tile it.
     */
    private fun uploadWoodGrain(engine: Engine): Texture {
        val source = ShelfWood.bitmap()
        val texture = Texture.Builder()
            .width(source.width)
            .height(source.height)
            .levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.SRGB8_A8)
            .build(engine)
        val bytes = ByteBuffer.allocateDirect(source.width * source.height * 4)
            .order(ByteOrder.nativeOrder())
        source.copyPixelsToBuffer(bytes)
        bytes.flip()
        pendingBuffers.add(bytes)
        texture.setImage(
            engine, 0,
            Texture.PixelBufferDescriptor(bytes, Texture.Format.RGBA, Texture.Type.UBYTE)
        )
        return texture
    }

    private fun addBooks(
        engine: Engine,
        scene: Scene,
        res: ShelfResources,
        vertices: VertexBuffer,
        indices: IndexBuffer,
        material: Material,
        artwork: Texture
    ) {
        // The niche stands in the gap between the picks and the rest of the library.
        val nookPosition = placement.indexOf(CylinderMath.GAP)
        val nookTile = spineAtlas?.nookTile

        for (position in placement.indices) {
            val book = placement[position]
            if (book < 0) {
                // An empty position. The gap is the one exception: a lit alcove is more inviting
                // than a blank slot, and it is what gives the case its character.
                if (book == CylinderMath.GAP && position == nookPosition && nookTile != null) {
                    addPanel(
                        engine, scene, res, vertices, indices, material, artwork,
                        tile = nookTile,
                        position = position,
                        colour = 0xFFFFFFFF.toInt(),
                        roughness = 0.85f
                    )
                }
                continue
            }
            addPanel(
                engine, scene, res, vertices, indices, material, artwork,
                tile = spineAtlas?.tiles?.getOrNull(book),
                position = position,
                colour = bookColors.getOrElse(book) { 0xFF6B4A32.toInt() },
                roughness = 0.55f + (book % 5) * 0.06f
            )
        }
        // Place them immediately, so the first frame is already a shelf and not a pile at the
        // origin.
        applyRotation()
    }

    /**
     * One standing panel on the drum: a book, or the niche, which is drawn with the same material
     * and differs only by its tile.
     */
    private fun addPanel(
        engine: Engine,
        scene: Scene,
        res: ShelfResources,
        vertices: VertexBuffer,
        indices: IndexBuffer,
        material: Material,
        artwork: Texture,
        tile: SpineAtlas.Tile?,
        position: Int,
        colour: Int,
        roughness: Float
    ) {
        // This book's own width, height and depth.
        val scale = proportionsOf(bookId = placement.getOrNull(position) ?: position)

        val instance = material.createInstance()
        // Linear-ish base colour; sRGB would need a colour space conversion, and a slightly
        // desaturated leather reads better under the sun anyway.
        instance.setParameter(
            "baseColor",
            Color.red(colour) / 255f,
            Color.green(colour) / 255f,
            Color.blue(colour) / 255f
        )
        instance.setParameter("roughness", roughness)
        instance.setParameter("metallic", 0f)

        // Bind the atlas on every panel, then point this one at its own tile.
        instance.setParameter(
            "spineMap",
            artwork,
            TextureSampler(TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
        )
        if (tile != null) {
            instance.setParameter("uvOffset", tile.u0, tile.v0)
            instance.setParameter("uvScale", tile.u1 - tile.u0, tile.v1 - tile.v0)
            instance.setParameter("useArtwork", 1f)
        } else {
            instance.setParameter("uvOffset", 0f, 0f)
            instance.setParameter("uvScale", 1f, 1f)
            instance.setParameter("useArtwork", 0f)
        }
        res.track(instance)
        bookInstances.add(instance)

        val entity = EntityManager.get().create()
        val half = floatArrayOf(
            CylinderMath.BOOK_WIDTH / 2f,
            CylinderMath.BOOK_HEIGHT / 2f,
            CylinderMath.BOOK_DEPTH / 2f
        )
        RenderableManager.Builder(1)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertices, indices)
            .material(0, instance)
            .boundingBox(Box(floatArrayOf(0f, 0f, 0f), half))
            // Small, always-on-screen objects: culling would cost more than it saves.
            .culling(false)
            .build(engine, entity)
        scene.addEntity(entity)
        bookEntities.add(entity)
        entityPosition.add(position)
        bookScale.add(scale)
        transforms.add(engine.transformManager.getInstance(entity))
    }

    /**
     * Builds the recommendation alcove: a shallow recess at the front of the drum.
     *
     * It is real geometry, not a picture of a nook. A back panel carrying the painted alcove art, two
     * wooden side walls that give it depth, a top board, and a warm unlit lamp panel inside. It
     * follows the drum, so it behaves like a bay built into the case rather than a panel laid over
     * it, and the books picked for the reader stand in it.
     */
    private fun addAlcove(
        engine: Engine,
        scene: Scene,
        res: ShelfResources,
        vertices: VertexBuffer,
        indices: IndexBuffer,
        woodMaterial: Material,
        bookMaterial: Material,
        lampMaterial: Material,
        artwork: Texture,
        grain: Texture
    ) {
        val slots = perRow.coerceAtLeast(1)
        // The alcove sits at the front of the drum, where the picks are placed.
        alcoveSlot = 0
        alcoveWidth = minOf(3f * CylinderMath.BOOK_WIDTH, CylinderMath.angleStep(slots) * radius * slots)
        val height = CylinderMath.BOOK_HEIGHT * 1.20f
        val depth = 0.075f

        // Back panel: the painted alcove, so the recess reads as somewhere with a garden in it.
        val back = bookMaterial.createInstance().apply {
            setParameter("baseColor", 1f, 1f, 1f)
            setParameter("roughness", 0.9f)
            setParameter("metallic", 0f)
            setParameter(
                "spineMap", artwork,
                TextureSampler(TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
            )
            val tile = spineAtlas?.nookTile
            if (tile != null) {
                setParameter("uvOffset", tile.u0, tile.v0)
                setParameter("uvScale", tile.u1 - tile.u0, tile.v1 - tile.v0)
                setParameter("useArtwork", 1f)
            } else {
                setParameter("uvOffset", 0f, 0f)
                setParameter("uvScale", 1f, 1f)
                setParameter("useArtwork", 0f)
            }
        }
        res.track(back)
        addAlcovePart(
            engine, scene, res, vertices, indices, back,
            dx = 0f, dy = 0f, dz = -depth,
            width = alcoveWidth, height = height, depth = 0.012f
        )

        // Side walls, top board: the wooden casing that makes it a recess rather than a poster.
        listOf(-1f, 1f).forEach { side ->
            addAlcovePart(
                engine, scene, res, vertices, indices,
                woodInstance(woodMaterial, res, grain),
                dx = side * alcoveWidth / 2f, dy = 0f, dz = -depth / 2f,
                width = 0.016f, height = height, depth = depth
            )
        }
        addAlcovePart(
            engine, scene, res, vertices, indices, woodInstance(woodMaterial, res, grain),
            dx = 0f, dy = height / 2f, dz = -depth / 2f,
            width = alcoveWidth, height = 0.018f, depth = depth
        )

        // The lamp: an unlit warm panel under the top board, which is what lights the nook.
        val lamp = lampMaterial.createInstance().apply {
            setParameter("color", 1.0f, 0.74f, 0.42f)
        }
        res.track(lamp)
        addAlcovePart(
            engine, scene, res, vertices, indices, lamp,
            dx = 0f, dy = height / 2f - 0.045f, dz = -0.012f,
            width = alcoveWidth * 0.62f, height = 0.012f, depth = 0.03f
        )
    }

    /**
     * A wooden material instance for the alcove's casing.
     *
     * It binds the grain every bit as much as the carcass does. A material that declares a
     * sampler and is handed an instance with nothing bound to it has no defined colour, and
     * "undefined" on a driver means black as often as anything else.
     */
    private fun woodInstance(material: Material, res: ShelfResources, grain: Texture): MaterialInstance =
        material.createInstance().apply {
            setParameter("baseColor", WOOD_SHELF[0], WOOD_SHELF[1], WOOD_SHELF[2])
            setParameter("roughness", 0.62f)
            setParameter("metallic", 0f)
            setParameter("uvScale", 6f, 6f)
            setParameter(
                "grainMap",
                grain,
                TextureSampler(TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.REPEAT)
            )
            res.track(this)
        }

    /** Adds one box of the alcove, positioned in the alcove's own frame. */
    private fun addAlcovePart(
        engine: Engine,
        scene: Scene,
        res: ShelfResources,
        vertices: VertexBuffer,
        indices: IndexBuffer,
        instance: MaterialInstance,
        dx: Float,
        dy: Float,
        dz: Float,
        width: Float,
        height: Float,
        depth: Float
    ) {
        val entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertices, indices)
            .material(0, instance)
            .boundingBox(
                Box(floatArrayOf(0f, 0f, 0f), floatArrayOf(width, height, depth))
            )
            .culling(false)
            .build(engine, entity)
        scene.addEntity(entity)
        val transform = engine.transformManager.getInstance(entity)
        alcoveParts.add(AlcovePart(entity, transform, dx, dy, dz, width, height, depth))
        applyAlcove()
    }

    /** Which slot the alcove is built into, and how wide it is. */
    private var alcoveSlot = 0
    private var alcoveWidth = 0.3f

    /**
     * Places the alcove for the drum's current rotation.
     *
     * It is part of the case, so it turns with the books: each part is offset in the alcove's own
     * frame and then rotated into the drum's.
     */
    private fun applyAlcove() {
        val e = engine ?: return
        if (alcoveParts.isEmpty()) return
        val slots = perRow.coerceAtLeast(1)
        val angle = CylinderMath.angleOf(alcoveSlot, slots, controller.rotation)
        val degrees = CylinderMath.facingDegrees(angle)
        val a = Math.toRadians(degrees.toDouble())
        val c = kotlin.math.cos(a).toFloat()
        val s2 = kotlin.math.sin(a).toFloat()
        // The alcove's back sits behind the books' spines, in the drum's shell.
        val baseX = CylinderMath.positionX(angle, radius)
        val baseZ = CylinderMath.positionZ(angle, radius, axis)
        val baseY = CylinderMath.rowY(0, rows) - CylinderMath.BOOK_HEIGHT / 2f + CylinderMath.BOOK_HEIGHT / 2f
        val tm = e.transformManager
        for (part in alcoveParts) {
            val wx = baseX + c * part.dx + s2 * part.dz
            val wz = baseZ - s2 * part.dx + c * part.dz
            boxTransform(wx, baseY + part.dy, wz, degrees, part.width, part.height, part.depth)
            if (part.transform != 0) tm.setTransform(part.transform, matrix)
        }
    }

    private fun addLights(engine: Engine, scene: Scene, res: ShelfResources) {
        // The sun: warm, high, and to one side, so every board has a lit top edge and a shadowed
        // underside. That contrast is what a solid object looks like; a single flat wash makes
        // the case read as a drawing however correct its geometry is.
        val sun = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .direction(sunDirection[0], sunDirection[1], sunDirection[2])
            .color(sunColor[0], sunColor[1], sunColor[2])
            .intensity(SUN_LUX)
            // Shadows are Step 4; a shadow map over a spinning drum needs its own tuning.
            .castShadows(false)
            .build(engine, sun)
        scene.addEntity(sun)
        sunEntity = sun

        // A fill straight down the camera's line of sight. Without it every surface turned away
        // from the sun is lit by the ambient term alone, and the inside of the case — which is
        // most of what the reader is looking at — goes to near black.
        val fill = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .direction(0.10f, -0.12f, -0.99f)
            .color(1.0f, 0.90f, 0.80f)
            .intensity(FILL_LUX)
            .castShadows(false)
            .build(engine, fill)
        scene.addEntity(fill)
        fillEntity = fill

        // Ambient fill from spherical harmonics. One band is the average radiance of the room
        // and needs exactly three floats; asking for three bands needs twenty-seven, and
        // passing nine throws ArrayIndexOutOfBoundsException at build time.
        //
        // These are radiance values, not a tint: multiplied by the intensity they come to a
        // fifth of the sun, which is what a room with a window in it looks like, and what keeps
        // the boards and the wall readable where the sun does not reach.
        val ambient = floatArrayOf(0.62f, 0.56f, 0.48f)
        val indirect = IndirectLight.Builder()
            .irradiance(1, ambient)
            .intensity(AMBIENT_LUX)
            .build(engine)
        scene.indirectLight = indirect
        res.track(indirect)
    }

    private var sunEntity = 0
    private var fillEntity = 0

    // ---------------------------------------------------------------- frame

    /**
     * Tells the drag controller how pixels map to the drum, and remembers the lens for hit
     * testing.
     *
     * Called from the render thread on every surface change, so it must stay trivial.
     */
    /** The front rank of books stands this far in front of the camera. */
    override fun cameraDistance(): Float = (axis - radius).coerceAtLeast(0.2f)

    override fun onResize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        halfFovH = CylinderMath.halfFovH(
            verticalFovDegrees = CylinderMath.FOV_VERTICAL_DEGREES,
            aspect = width.toFloat() / height.toFloat()
        )
        // The gain follows the drum: a bigger drum moves its surface further per radian.
        controller.trackViewport(width, halfFovH, CylinderMath.trackingGain(radius, axis))
    }

    override fun onFrame(frameTimeNanos: Long): Boolean {
        if (frameNanos == 0L) {
            frameNanos = frameTimeNanos
            return true
        }
        val dt = ((frameTimeNanos - frameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        frameNanos = frameTimeNanos
        val moving = controller.tick(dt)
        if (moving) applyRotation()
        return moving
    }

    /**
     * Writes every book's transform for the current drum rotation.
     *
     * One 16-float matrix per book per frame, reused rather than allocated: at 60 fps with
     * dozens of books, allocating here is the difference between a smooth sweep and a stutter
     * every few seconds from garbage collection.
     */
    private fun applyRotation() {
        val e = engine ?: return
        val tm = e.transformManager
        val count = bookEntities.size
        if (count == 0) return
        val rotation = controller.rotation
        val slots = perRow.coerceAtLeast(1)
        for (i in 0 until count) {
            val position = entityPosition.getOrNull(i) ?: continue
            val row = position / slots
            val slot = position % slots
            val angle = CylinderMath.angleOf(slot, slots, rotation)
            val x = CylinderMath.positionX(angle, radius)
            val z = CylinderMath.positionZ(angle, radius, axis)
            val degrees = CylinderMath.facingDegrees(angle)
            val scale = bookScale.getOrNull(i)
            // A book stands on its board: a taller one grows upwards, not through the shelf.
            val shrink = (scale?.get(1) ?: 1f) - 1f
            val standing = CylinderMath.rowY(row, rows) + CylinderMath.BOOK_HEIGHT * shrink / 2f
            buildTransform(x, standing, z, degrees, scale)
            val instance = transforms[i]
            if (instance != 0) tm.setTransform(instance, matrix)
        }
        applyAlcove()
        val front = frontPosition()
        if (front != null && front != lastFrontIndex) {
            lastFrontIndex = front
            placement.getOrNull(front)?.takeIf { it >= 0 }?.let { onFrontBookChanged?.invoke(it) }
        }
    }

    /** The same matrix for an arbitrary box, used by the alcove's casing. */
    private fun boxTransform(x: Float, y: Float, z: Float, yawDegrees: Float, sx: Float, sy: Float, sz: Float) {
        val a = Math.toRadians(yawDegrees.toDouble())
        val c = kotlin.math.cos(a).toFloat()
        val s = kotlin.math.sin(a).toFloat()
        java.util.Arrays.fill(matrix, 0f)
        matrix[0] = c * sx; matrix[1] = 0f; matrix[2] = -s * sx; matrix[3] = 0f
        matrix[4] = 0f; matrix[5] = sy; matrix[6] = 0f; matrix[7] = 0f
        matrix[8] = s * sz; matrix[9] = 0f; matrix[10] = c * sz; matrix[11] = 0f
        matrix[12] = x; matrix[13] = y; matrix[14] = z; matrix[15] = 1f
    }

    /**
     * Translation then yaw then scale, in column-major order (Filament takes the same layout
     * as OpenGL). Written out rather than calling android.opengl.Matrix so nothing allocates.
     */
    private fun buildTransform(x: Float, y: Float, z: Float, yawDegrees: Float, scale: FloatArray?) {
        val a = Math.toRadians(yawDegrees.toDouble())
        val c = kotlin.math.cos(a).toFloat()
        val s = kotlin.math.sin(a).toFloat()
        val sx = CylinderMath.BOOK_WIDTH * (scale?.get(0) ?: 1f)
        val sy = CylinderMath.BOOK_HEIGHT * (scale?.get(1) ?: 1f)
        val sz = CylinderMath.BOOK_DEPTH * (scale?.get(2) ?: 1f)
        java.util.Arrays.fill(matrix, 0f)
        // R_y(yaw) * S
        matrix[0] = c * sx;  matrix[1] = 0f; matrix[2] = -s * sx;  matrix[3] = 0f
        matrix[4] = 0f;      matrix[5] = sy; matrix[6] = 0f;       matrix[7] = 0f
        matrix[8] = s * sz;  matrix[9] = 0f; matrix[10] = c * sz;  matrix[11] = 0f
        matrix[12] = x;      matrix[13] = y; matrix[14] = z;       matrix[15] = 1f
    }

    // ---------------------------------------------------------------- buffers

    private fun vertexBuffer(engine: Engine, mesh: ShelfGeometry.Mesh): VertexBuffer {
        val vb = VertexBuffer.Builder()
            .vertexCount(mesh.vertexCount)
            .bufferCount(1)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION, 0,
                VertexBuffer.AttributeType.FLOAT3, ShelfGeometry.POSITION_OFFSET, ShelfGeometry.STRIDE_BYTES
            )
            // No NORMAL attribute exists: the tangent frame carries the normal.
            .attribute(
                VertexBuffer.VertexAttribute.TANGENTS, 0,
                VertexBuffer.AttributeType.FLOAT4, ShelfGeometry.TANGENT_OFFSET, ShelfGeometry.STRIDE_BYTES
            )
            .attribute(
                VertexBuffer.VertexAttribute.UV0, 0,
                VertexBuffer.AttributeType.FLOAT2, ShelfGeometry.UV_OFFSET, ShelfGeometry.STRIDE_BYTES
            )
            .build(engine)
        val data = ByteBuffer.allocateDirect(mesh.vertices.size * 4).order(ByteOrder.nativeOrder())
        data.asFloatBuffer().put(mesh.vertices)
        vb.setBufferAt(engine, 0, data)
        pendingBuffers.add(data)
        return vb
    }

    private fun indexBuffer(engine: Engine, mesh: ShelfGeometry.Mesh): IndexBuffer {
        val ib = IndexBuffer.Builder()
            .indexCount(mesh.indexCount)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        val data = ByteBuffer.allocateDirect(mesh.indices.size * 2).order(ByteOrder.nativeOrder())
        data.asShortBuffer().put(mesh.indices)
        ib.setBuffer(engine, data)
        pendingBuffers.add(data)
        return ib
    }

    // ---------------------------------------------------------------- queries

    /**
     * The book nearest the front of the drum, for the caption under the shelf.
     *
     * Every tier has a book at the front slot, so this names the one on the middle tier: the
     * caption is then about a book the reader is actually looking at, rather than about
     * whichever tier happened to be built first.
     */
    fun frontBook(): Int {
        val position = frontPosition() ?: return -1
        return placement.getOrNull(position) ?: -1
    }

    /** The position on the middle tier that is at the front of the drum, if any book is there. */
    private fun frontPosition(): Int? {
        val slots = perRow.coerceAtLeast(1)
        val slot = CylinderMath.frontIndex(slots, controller.rotation)
        val position = (rows - 1) / 2 * slots + slot
        val book = placement.getOrNull(position) ?: return null
        return if (book >= 0) position else null
    }

    /** Which book a tap hit, or -1. */
    fun bookAt(screenX: Float, screenY: Float, viewWidth: Float, viewHeight: Float): Int {
        val count = bookEntities.size
        if (count == 0) return -1
        val slots = perRow.coerceAtLeast(1)
        // The tiers share their slots, so the tap's height picks the row and its width picks
        // the slot.
        val row = CylinderMath.rowAtScreenY(screenY, viewHeight, rows, axis - radius)
        return bookAtSlot(screenX, viewWidth, row * slots, slots, count)
    }

    private fun bookAtSlot(
        screenX: Float,
        viewWidth: Float,
        base: Int,
        slots: Int,
        count: Int
    ): Int {
        val angle = CylinderMath.angleForScreenX(screenX, viewWidth, halfFovH, radius, axis)
            ?: return -1
        val slot = CylinderMath.bookNearAngle(slots, controller.rotation, angle)
        if (!CylinderMath.isVisible(
                CylinderMath.angleOf(slot, slots, controller.rotation),
                CylinderMath.visibleArcDegrees(radius, axis)
            )
        ) return -1
        // A tap on the bare shelf between two books opens nothing, rather than opening whichever
        // book happens to be nearest.
        if (!CylinderMath.isTapOnBook(angle, slot, slots, controller.rotation, radius = radius)) {
            return -1
        }
        // The slot the tap landed on, as a book: the bay means slot and book no longer match.
        val position = base + slot
        return placement.getOrNull(position)?.takeIf { it >= 0 } ?: -1
    }

    override fun onDestroy(engine: Engine) {
        if (sunEntity != 0) {
            EntityManager.get().destroy(sunEntity)
            sunEntity = 0
        }
        if (fillEntity != 0) {
            EntityManager.get().destroy(fillEntity)
            fillEntity = 0
        }
        staticEntities.forEach { EntityManager.get().destroy(it) }
        staticEntities.clear()
        bookEntities.forEach { EntityManager.get().destroy(it) }
        bookEntities.clear()
        bookInstances.clear()
        transforms.clear()
        resources?.release()
        resources = null
        pendingBuffers.clear()
        engine.flushAndWait()
        this.engine = null
    }

/**
 * One piece of the recommendation alcove: a wall, a top board or the lamp, plus where it sits in
 * the alcove's own frame. The whole alcove is carried round the drum with the books.
 */
private class AlcovePart(
    val entity: Int,
    val transform: Int,
    /** Lateral offset along the shelf, then height, then how far in front of the back wall. */
    val dx: Float,
    val dy: Float,
    val dz: Float,
    val width: Float,
    val height: Float,
    val depth: Float
)

/**
 * A book's own width, height and depth multipliers.
 *
 * Deterministic in the book's index, so a book looks the same on every launch, and narrow enough
 * that neighbouring spines still touch: a shelf is books of different sizes pressed together, not
 * books adrift from each other.
 */
private fun proportionsOf(bookId: Int): FloatArray {
    var h = bookId * 2654435761L
    h = h xor (h ushr 13)
    val a = abs((h % 1000L).toInt()) / 1000f
    val b = abs((h / 1000L % 1000L).toInt()) / 1000f
    val c = abs((h / 1_000_000L % 1000L).toInt()) / 1000f
    return floatArrayOf(
        0.88f + a * 0.26f, // thickness
        0.94f + b * 0.10f, // height
        0.92f + c * 0.16f // depth
    )
}
}
