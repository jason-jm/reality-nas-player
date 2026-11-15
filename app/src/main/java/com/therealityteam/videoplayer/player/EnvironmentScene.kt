package com.therealityteam.videoplayer.player

import android.util.Log
import androidx.annotation.MainThread
import com.pico.spatial.core.ecs.DynamicShadowComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.GaussianSplattingComponent
import com.pico.spatial.core.ecs.ImageBasedLightSource
import com.pico.spatial.core.ecs.LoadType
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.PointLightComponent
import com.pico.spatial.core.ecs.SpotLightComponent
import com.pico.spatial.core.ecs.StageEnvironmentLightingComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.GaussianSplattingResource
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.pow
import java.util.Locale

/**
 * The environment drawn around the video window on the Full-Space stage.
 *
 * [root] is hung on the stage once and carries two children: a pose holder, under which the model of
 * the environment being shown sits untouched (its own transform may be the import's axis
 * correction), and the stage's environment light — a Full stage with no light of its own draws its
 * PBR materials black. The model is loaded when first shown and destroyed when another replaces it or
 * the stage closes, so one environment's textures are resident at a time — two for the moment of a
 * swap, the new one read while the old stays up. Main thread throughout, as the SDK asks; only the
 * light's texture is read on the IO dispatcher.
 */
class EnvironmentScene {

    val root: Entity = Entity().apply { setName("aura_environment") }
    private val holder = Entity().apply { setName("aura_environment_pose") }
    private val light = Entity().apply { setName("aura_environment_light") }

    init {
        root.addChild(holder)
        root.addChild(light)
        root.enabled = false
    }

    private var shown: Environment? = null
    private val activeSeat = MutableStateFlow(ViewingSeat.CENTER)
    val viewingSeat = activeSeat.asStateFlow()
    private var model: Entity? = null
    private var ibl: TextureResource? = null
    private var lightLevel = Float.NaN


    /**
     * The shown model's materials with the colours they came with, for [setLight]: the base colour —
     * which multiplies the texture, and a baked room's light is in its textures — and, for a PBR one,
     * its glow. A shader graph has no colour every graph shares, and keeps its own.
     */
    private class Tint(val material: Material, val base: Color4, val emissive: Color4?)
    private val tints = mutableListOf<Tint>()
    private var roomLight = 1f

    /**
     * A place baked in light layers (tools/envbake/bake_layers.py): each part drawn twice, "base_…" — the light
     * that stays with the room's lights off, the screen's — and "house_…" — the room's own lights alone, drawn
     * added on top, scaled by their level ([setHouseLights]). Both are shown as baked, their unlit tone mapping
     * off: the textures already hold the picture Blender made, and a second curve darkened and greyed it.
     * A hybrid place (tools/envbake/export_hybrid.py) has a PBR house layer instead — the surfaces' own colour, for
     * the headset's real-time lights ([setRealtimeLights]) to light — and its LED strips as a glow layer.
     */
    private class Layer(val entity: Entity, val material: Material)
    private val houseLayers = mutableListOf<Layer>()
    private val baseLayers = mutableListOf<UnlitMaterial>()
    private var houseLight = 1f

    /** A hybrid place's room lights, made by the headset: the lights.json they come from and their lumens per watt. */
    private var realtimeSpec: Pair<String, Float>? = null
    private val realtimeLights = mutableListOf<Entity>()
    private var loungeSpot: Entity? = null
    private var loungeReceiver: Entity? = null

    /** Whether the place on show has room lights of its own to switch (a layered bake). */
    val hasLights = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * 动态氛围光: a place whose screen's light is baked in zones ("ambl_"/"ambc_"/"ambr_", or one "amb_" for the whole
     * screen) draws each added on top, tinted with what the film shows there ([setAmbience]). [zone] is the zone's index,
     * or [ALL_ZONES].
     */
    private class AmbLayer(val entity: Entity, val material: UnlitMaterial, val zone: Int) {
        /** As last set — the entity and the material are written only when these change (see [setAmbience]). */
        var shown: Boolean? = null
        var r = -1f; var g = -1f; var b = -1f
    }
    private val ambLayers = mutableListOf<AmbLayer>()
    private var effectsOn = true
    private var ambience: FloatArray? = null

    /** Room lights above this: the film's light is not drawn (debug: -e aura.env_amb cutoff:<level>). */
    var ambHouseCutoff = AMB_HOUSE_CUTOFF
        set(value) {
            field = value
            updateAmbientLayers()
        }

    /** Only request film frames when the current lighting actually draws the ambient layers. */
    val ambienceVisible = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** The environment on show, or null. */
    val current: Environment? get() = shown

    private val iblLock = Mutex()
    private var lightGeneration = 0

    /**
     * Reads run here, apart from whoever waits for them: a caller may stop waiting — a later choice,
     * a time limit — and the read goes on to its end, its model freed if nobody wants it then. A read
     * cut short would leave its half-made model with no one to free it.
     */
    private val reads = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** A model being read, for the place it is for. */
    private class Read(val env: Environment, val model: Deferred<Entity?>, val started: Long)
    private var reading: Read? = null

    /**
     * Shows [env], or nothing. The same model under a new seat or light is moved, not reloaded.
     * Another model is read first and takes the shown one's place only once it is in: the place on
     * show stays up meanwhile, where it used to drop to black for the seconds a model takes. With
     * [reveal] false the model is read and placed but not yet shown — a place is read before its stage
     * opens, so the stage opens on it. Two callers asking for the same model share its read. A model
     * that cannot be read leaves the place before it.
     */
    @MainThread
    suspend fun show(env: Environment?, reveal: Boolean = true, seat: ViewingSeat = ViewingSeat.CENTER) {
        if (env == null) {
            clear()
            return
        }
        if (isShown(env)) {
            letGo()
            place(env, seat)
            light(env)
            shown = env
            if (reveal) root.enabled = true
            logBounds(env)
            return
        }
        val read = readOf(env)
        val loaded = read.model.await()
        // All scene mutations run on Main. Only the read above suspends. A canceled or
        // superseded request must never install a model returned after it lost ownership.
        when {
            reading === read -> {
                reading = null
                if (loaded != null) attach(env, loaded, reveal, read.started, seat)
            }
            isShown(env) -> if (reveal) root.enabled = true
        }
    }

    /** Revoke an obsolete read as soon as the choice changes, including before the next stage frame. */
    @MainThread
    fun discardPendingRead(keep: Environment? = null) {
        val r = reading ?: return
        if (keep == null || r.env.id != keep.id || r.env.source != keep.source) letGo(r)
    }

    private fun isShown(env: Environment): Boolean {
        val before = shown ?: return false
        return model != null && before.id == env.id && before.source == env.source
    }

    /** The read under way of [env]'s model, or a new one — another model's read is let go. */
    private fun readOf(env: Environment): Read {
        reading?.let { r ->
            if (r.env.id == env.id && r.env.source == env.source) return r
            letGo(r)
        }
        Log.i(TAG, "${env.id} loading")
        return Read(env, reads.async { read(env) }, System.nanoTime()).also { reading = it }
    }

    /** A read nobody waits for any more: its model, when it comes, is freed. */
    private fun letGo(r: Read? = reading) {
        r ?: return
        if (reading === r) reading = null
        r.model.invokeOnCompletion {
            reads.launch {
                val m = runCatching { r.model.await() }.getOrNull() ?: return@launch
                if (m === model) return@launch
                Log.i(TAG, "${r.env.id} read but no longer wanted")
                disposeModel(m)
            }
        }
    }

    private fun attach(env: Environment, loaded: Entity, reveal: Boolean, started: Long, seat: ViewingSeat) {
        clear()
        model = loaded
        holder.addChild(loaded)
        collectTints(env, loaded)
        if (roomLight != 1f) applyLight()
        makeRealtimeLights()
        if (houseLayers.isNotEmpty()) applyHouseLights()
        place(env, seat)
        light(env)
        root.enabled = reveal
        shown = env
        Log.i(TAG, "${env.id} up in ${(System.nanoTime() - started) / 1_000_000} ms${if (reveal) "" else ", held for its stage"}")
        logBounds(env)
    }

    /** [env]'s model, or null if it cannot be read. */
    private suspend fun read(env: Environment): Entity? = try {
        // Global: the stage light's component gives its texture back when it is taken off, and a place with another
        // light level takes it off and puts a new one on — with the texture closed under it, which crashed. Closed here.
        val generation = lightGeneration
        val ready = iblLock.withLock {
            if (generation != lightGeneration) return@withLock false
            if (ibl == null) {
                val texture = withContext(Dispatchers.IO) { TextureResource.load(IBL_PATH, LoadType.FROM_ASSETS) }
                if (generation != lightGeneration) {
                    texture.close()
                    return@withLock false
                }
                texture.toGlobal()
                ibl = texture
            }
            true
        }
        if (ready) load(env.source) else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "${env.id} did not load", e)
        null
    }

    private suspend fun load(source: EnvSource): Entity = when (source) {
        is EnvSource.Asset -> Entity.loadSuspend("asset://${source.path}")
        is EnvSource.File -> if (SPLATS.any { source.path.endsWith(it, ignoreCase = true) }) splats(source.path) else Entity.loadSuspend(File(source.path))
    }

    /**
     * A 3D Gaussian splat file (PLY / SPZ) tried as a place (`-e aura.env_file …/x.spz`): the SDK's own splat renderer,
     * GaussianSplattingComponent, in its default performance mode — for measuring what a splatted place costs.
     */
    private suspend fun splats(path: String): Entity {
        val resource = withContext(Dispatchers.IO) { GaussianSplattingResource(path, LoadType.FROM_STORAGE) }
        return Entity().also { e -> e.components.set(GaussianSplattingComponent().apply { gaussianSplattingResource = resource }) }
    }

    @MainThread
    fun moveToSeat(env: Environment, seat: ViewingSeat): Boolean {
        if (env != shown || seat == activeSeat.value || seat !in env.seatChoices) return false
        place(env, seat)
        return true
    }

    private fun place(env: Environment, seat: ViewingSeat) {
        val t = holder.components[TransformComponent::class.java]
            ?: TransformComponent().also { holder.components.set(it) }
        val pose = env.seatPose(seat)
        val o = env.origin(seat)
        t.setScaleVector(Vector3(env.scale, env.scale, env.scale))
        t.setEulerAngles(EulerAngles(0f, pose.yaw, 0f))
        t.setPosition(Vector3(o[0], o[1], o[2]))
        activeSeat.value = seat
    }

    private fun light(env: Environment) {
        val texture = ibl ?: return
        if (env.light == lightLevel) return
        if (light.components.has(StageEnvironmentLightingComponent::class.java)) {
            light.components.remove(StageEnvironmentLightingComponent::class.java)
        }
        light.components.set(StageEnvironmentLightingComponent(ImageBasedLightSource.Single(texture), env.light))
        lightLevel = env.light
    }

    /**
     * The room's light as a share of how it was made — 1 as it came, lower while a film plays in it
     * (the lights of a cinema going down): every collected colour scaled by [level]. Unlit and baked
     * places dim too, which the stage's environment light could not do (an exponent their materials
     * ignore).
     */
    fun setLight(level: Float) {
        if (kotlin.math.abs(level - roomLight) < LIGHT_STEP && level != 1f) return
        if (level == roomLight) return
        roomLight = level
        applyLight()
    }

    /**
     * Every collected colour at [roomLight]. Also run when a model comes up: the light may have gone
     * down while it loaded — the bar fades in seconds, the living room takes more — and the level,
     * set then on nothing, is not set again.
     */
    private fun applyLight() {
        val level = roomLight
        for (t in tints) runCatching {
            when (val m = t.material) {
                is PhysicallyBasedMaterial -> {
                    m.setBaseColor(scaled(t.base, level))
                    t.emissive?.let { m.setEmissiveColor(scaled(it, level)) }
                }
                is UnlitMaterial -> m.setBaseColor(scaled(t.base, level))
            }
        }
    }

    private fun scaled(c: Color4, k: Float) = Color4(c.red * k, c.green * k, c.blue * k, c.alpha)

    /** Every PBR and unlit material under [model], with its colours as made; logged with what it holds. */
    private fun collectTints(env: Environment, model: Entity) {
        var graphs = 0
        var bases = 0
        var loungeFloor: Entity? = null
        fun walk(e: Entity, depth: Int) {
            e.components[ModelComponent::class.java]?.materials?.forEach { m ->
                runCatching {
                    val name = when (m) {
                        is UnlitMaterial -> m.getName()
                        is PhysicallyBasedMaterial -> m.getName()
                        else -> ""
                    }
                    if (env.id == "space_lounge") {
                        // The other meshes include the sky and coincident copies for baked light.
                        e.components.set(DynamicShadowComponent(name == "base_near", false))
                        if (name == "house_floor") loungeFloor = e
                    }
                    when {
                        m is UnlitMaterial && name.startsWith(BASE_LAYER) -> {
                            m.toGlobal(); m.setApplyToneMapping(false); baseLayers += m; bases++
                        }
                        m is UnlitMaterial && AMB_ZONES.keys.any { name.startsWith(it) } -> {
                            m.toGlobal()
                            m.setApplyToneMapping(false)
                            m.setBlendingMode(BlendingMode.ADD)
                            m.setDepthWrite(false)
                            val zone = AMB_ZONES.entries.firstOrNull { name.startsWith(it.key) }?.value ?: ALL_ZONES
                            ambLayers += AmbLayer(e, m, zone)
                        }
                        m is UnlitMaterial && HOUSE_LAYERS.any { name.startsWith(it) } -> {
                            m.toGlobal()
                            m.setApplyToneMapping(false)
                            m.setBlendingMode(BlendingMode.ADD)
                            m.setDepthWrite(false)
                            houseLayers += Layer(e, m)
                        }
                        m is PhysicallyBasedMaterial && name.startsWith(HOUSE_LAYER) -> {
                            m.toGlobal()
                            m.setBlendingMode(BlendingMode.ADD)
                            m.setDepthWrite(false)
                            houseLayers += Layer(e, m)
                        }
                        else -> when (m) {
                        is PhysicallyBasedMaterial -> { m.toGlobal(); tints += Tint(m, m.getBaseColor(), m.getEmissiveColor()) }
                        is UnlitMaterial -> { m.toGlobal(); tints += Tint(m, m.getBaseColor(), null) }
                        else -> graphs++
                        }
                    }
                }.onFailure { Log.w(TAG, "${env.id} material not read: ${it.message}") }
            }
            if (depth < MAX_DEPTH) e.getChildren().forEach { walk(it, depth + 1) }
        }
        walk(model, 0)
        loungeFloor?.let(::addLoungeShadows)
        hasLights.value = houseLayers.isNotEmpty()
        updateAmbientLayers()
        Log.i(TAG, "${env.id} light: ${tints.size} materials dim, $graphs keep their own" +
            (if (houseLayers.isEmpty()) "" else "; layered: $bases base, ${houseLayers.size} house") +
            if (ambLayers.isEmpty()) "" else ", ${ambLayers.size} ambience")
    }

    /** Add direct light to the existing marble under the chairs, leaving its baked light and UVs intact. */
    private fun addLoungeShadows(floor: Entity) {
        val floorModel = floor.components[ModelComponent::class.java] ?: return
        val mesh = floorModel.mesh ?: return
        val baked = floorModel.materials.firstOrNull() as? UnlitMaterial ?: return
        val finish = PhysicallyBasedMaterial.create(BlendingMode.ADD).apply {
            setBaseColor(Color4(0.42f, 0.39f, 0.36f, 1f))
            baked.getBaseColorTexture()?.let(::setBaseColorTexture)
            setRoughness(0.9f)
            setMetallic(0f)
            setDepthWrite(false)
        }
        loungeReceiver = Entity().apply {
            setName("aura_lounge_floor_shadow_receiver")
            components.set(ModelComponent(mesh, finish))
            components.set(DynamicShadowComponent(false, true))
            floor.addChild(this)
        }
        loungeSpot = Entity().apply {
            setName("aura_lounge_ceiling_spot")
            components.set(TransformComponent().apply {
                setPosition(Vector3(0f, 4.2f, -1.8f))
                setQuaternion(facing(floatArrayOf(0f, -0.8660254f, -0.5f)))
            })
            components.set(SpotLightComponent(Color4(1f, 0.91f, 0.79f, 1f), LOUNGE_SPOT_LUMENS,
                10f, 32f, 55f, true))
            holder.addChild(this)
        }
        Log.d(TAG, "Lounge shadow light and marble receiver attached")
    }

    /**
     * The room's own lights at [level] (0 off … 1 as baked) in a layered place: the house layer's colour scaled,
     * and the layer not drawn at all while they are off — the whole room is not drawn a second time for nothing.
     */
    fun setHouseLights(level: Float) {
        val k = level.coerceIn(0f, 1f)
        if (k == houseLight && houseLayers.isNotEmpty()) return
        houseLight = k
        applyHouseLights()
    }

    private fun applyHouseLights() {
        val k = houseLight
        val e = encoded(k)
        val c = Color4(e, e, e, 1f)
        for (l in houseLayers) runCatching {
            when (val m = l.material) {
                is UnlitMaterial -> m.setBaseColor(c)
                // the surfaces' colour scaled is the light on them scaled: the real-time lights stay as made
                is PhysicallyBasedMaterial -> m.setBaseColor(c)
            }
            l.entity.enabled = k > HOUSE_OFF && (effectsOn || !isEffect(l.material))
        }
        for (e in realtimeLights) runCatching { e.enabled = k > HOUSE_OFF }
        val shadowsOn = k > HOUSE_OFF && effectsOn
        loungeReceiver?.enabled = shadowsOn
        loungeSpot?.let { spot ->
            spot.enabled = shadowsOn
            if (spot.enabled) spot.components[SpotLightComponent::class.java]?.intensity = LOUNGE_SPOT_LUMENS * k
        }
        updateAmbientLayers()
    }

    private fun updateAmbientLayers() {
        ambienceVisible.value = ambLayers.isNotEmpty() && (houseLayers.isEmpty() || houseLight <= ambHouseCutoff)
        if (ambLayers.isNotEmpty()) setAmbience(ambience)
    }

    /**
     * A factor on a layer's light as the base colour that gives it: the SDK reads an unlit material's base colour as sRGB
     * and decodes it before it multiplies the texture — 0.5 gave the light a fifth (measured on the B3110: 1 → 2.86 on a
     * zone made it 10× brighter, not 2.86×). Encoded here, a factor is the light's own: 0.5 is half.
     */
    private fun encoded(linear: Float): Float =
        if (linear <= 0.0031308f) 12.92f * linear else 1.055f * linear.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f

    private fun isEffect(m: Material) = (m as? UnlitMaterial)?.getName()?.startsWith(FX_HOUSE) == true

    /**
     * The film's light now, per zone of the screen: [factors] holds [Ambience.ZONES] × red, green, blue, each a multiple of
     * the light the bake gave the zone (1 = as baked); null puts the film's light out (no film on the screen).
     */
    fun setAmbience(factors: FloatArray?) {
        ambience = factors
        // With the room's lights up the screen's light is a small part of the room's, and a third drawing of the room
        // (base, house, the film's light) missed frames on the B3110: the film's light goes while they are.
        for (l in ambLayers) runCatching {
            val f = factors
            if (f == null || !ambienceVisible.value) {
                if (l.shown != false) { l.entity.enabled = false; l.shown = false }
                return@runCatching
            }
            var r = 0f; var g = 0f; var b = 0f
            if (l.zone == ALL_ZONES) {
                for (z in 0 until Ambience.ZONES) { r += f[z * 3]; g += f[z * 3 + 1]; b += f[z * 3 + 2] }
                r /= Ambience.ZONES; g /= Ambience.ZONES; b /= Ambience.ZONES
            } else {
                r = f[l.zone * 3]; g = f[l.zone * 3 + 1]; b = f[l.zone * 3 + 2]
            }
            // Thirty times a second: written only when the colour moves.
            if (kotlin.math.abs(r - l.r) + kotlin.math.abs(g - l.g) + kotlin.math.abs(b - l.b) > AMB_STEP) {
                l.material.setBaseColor(Color4(encoded(r), encoded(g), encoded(b), 1f))
                l.r = r; l.g = g; l.b = b
            }
            val on = r + g + b > AMB_OFF
            if (l.shown != on) { l.entity.enabled = on; l.shown = on }
        }
    }

    /** The place's effects (the LED halos) shown or not — for weighing what they cost. */
    fun setEffects(on: Boolean) {
        effectsOn = on
        applyHouseLights()
    }

    /**
     * A hybrid place's room lights made by the headset, from export_hybrid.py's [path] (lights.json: spots and points
     * in the model's units, their power in Blender's watts) at [lumensPerWatt]; null removes them. For comparing a
     * baked room's lights with real-time ones (`-e aura.env_rt_lights`); made again whenever a place comes up.
     */
    fun setRealtimeLights(path: String?, lumensPerWatt: Float) {
        realtimeSpec = path?.let { it to lumensPerWatt }
        makeRealtimeLights()
        applyHouseLights()
    }

    private fun makeRealtimeLights() {
        realtimeLights.forEach { runCatching { if (it.valid) it.destroy() } }
        realtimeLights.clear()
        val (path, lumensPerWatt) = realtimeSpec ?: return
        if (model == null) return
        val lights = runCatching { org.json.JSONObject(File(path).readText()).getJSONArray("lights") }
            .getOrElse { Log.w(TAG, "realtime lights not read from $path: ${it.message}"); return }
        for (i in 0 until lights.length()) runCatching {
            val o = lights.getJSONObject(i)
            fun vec(key: String) = o.getJSONArray(key).let { a -> FloatArray(3) { a.getDouble(it).toFloat() } }
            val p = vec("pos")
            val c = vec("color")
            val color = Color4(c[0], c[1], c[2], 1f)
            val lumens = o.getDouble("watts").toFloat() * lumensPerWatt
            val radius = o.optDouble("radius", REALTIME_RADIUS.toDouble()).toFloat()
            val e = Entity().apply { setName("aura_rt_${o.getString("name")}") }
            val t = TransformComponent().also { e.components.set(it) }
            t.setPosition(Vector3(p[0], p[1], p[2]))
            if (o.getString("kind") == "spot") {
                t.setQuaternion(facing(vec("dir")))
                e.components.set(SpotLightComponent(color, lumens, radius,
                    o.getDouble("inner").toFloat(), o.getDouble("outer").toFloat()))
            } else {
                e.components.set(PointLightComponent(color, lumens, radius))
            }
            // under the pose holder, beside the model: in the model's units, moved with the place
            holder.addChild(e)
            realtimeLights += e
        }.onFailure { Log.w(TAG, "realtime light $i not made: ${it.message}") }
        Log.i(TAG, "realtime lights: ${realtimeLights.size} from $path at $lumensPerWatt lm/W")
    }

    /** The turn that brings a light's own way, −z, onto [d] (a unit vector). */
    private fun facing(d: FloatArray): Quat {
        val w = 1f - d[2]
        if (w < 1e-4f) return Quat(0f, 1f, 0f, 0f)
        val n = kotlin.math.sqrt(d[1] * d[1] + d[0] * d[0] + w * w)
        return Quat(d[1] / n, -d[0] / n, 0f, w / n)
    }

    /**
     * What the runtime makes of the model: its bounds in its own units, before the seat's pose. With
     * `adb shell setprop log.tag.AuraEnv DEBUG`, every mesh under it too, named — seats are chosen
     * from these numbers when a model comes without a source to measure offline.
     */
    private fun logBounds(env: Environment) {
        val m = model ?: return
        runCatching {
            val b = m.getVisualBounds(holder)
            Log.i(TAG, "${env.id} model bounds ${fmt(b.min)} .. ${fmt(b.max)} size ${fmt(b.size)}")
            if (!Log.isLoggable(TAG, Log.DEBUG)) return@runCatching
            var lines = 0
            fun walk(e: Entity, depth: Int) {
                for (c in e.getChildren()) {
                    if (lines >= 250) return
                    if (c.components.has(ModelComponent::class.java)) {
                        val cb = runCatching { c.getVisualBounds(holder) }.getOrNull()
                        if (cb != null) {
                            lines++
                            Log.d(TAG, "${env.id} mesh ${c.getName()}: ${fmt(cb.min)} .. ${fmt(cb.max)}")
                        }
                    }
                    if (depth < 12) walk(c, depth + 1)
                }
            }
            walk(m, 0)
        }.onFailure { Log.w(TAG, "${env.id} bounds unavailable: ${it.message}") }
    }

    private fun fmt(v: Vector3) = String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z)

    private fun disposeModel(m: Entity) {
        if (!m.valid) return
        runCatching { m.components[GaussianSplattingComponent::class.java]?.gaussianSplattingResource?.close() }
        runCatching { m.removeFromParent() }
        runCatching { m.destroy() }
    }

    /** Takes the shown model off the stage and frees it. */
    fun clear() {
        letGo()
        loungeSpot?.let { if (it.valid) it.destroy() }
        loungeSpot = null
        loungeReceiver = null // destroyed with the floor
        tints.forEach { t -> runCatching { t.material.close() } }
        tints.clear()
        houseLayers.forEach { l -> runCatching { l.material.close() } }
        houseLayers.clear()
        ambLayers.forEach { l -> runCatching { l.material.close() } }
        ambLayers.clear()
        ambience = null
        ambienceVisible.value = false
        realtimeLights.forEach { runCatching { if (it.valid) it.destroy() } }
        realtimeLights.clear()
        baseLayers.forEach { m -> runCatching { m.close() } }
        baseLayers.clear()
        hasLights.value = false
        model?.let(::disposeModel)
        model = null
        shown = null
        activeSeat.value = ViewingSeat.CENTER
        root.enabled = false
    }

    /** [clear], and the light's texture goes too; the next [show] reads it again. */
    fun release() {
        lightGeneration++
        clear()
        if (light.components.has(StageEnvironmentLightingComponent::class.java)) {
            light.components.remove(StageEnvironmentLightingComponent::class.java)
        }
        lightLevel = Float.NaN
        roomLight = 1f
        ibl?.let { runCatching { it.close() } }
        ibl = null
    }

    private companion object {
        const val TAG = "AuraEnv"
        const val LOUNGE_SPOT_LUMENS = 3800f
        val SPLATS = listOf(".spz", ".ply")
        const val IBL_PATH = "ibl.ktx"
        const val MAX_DEPTH = 32

        /** The material names of a layered place's two drawings (tools/envbake/bake_layers.py). */
        const val BASE_LAYER = "base_"
        const val HOUSE_LAYER = "house_"

        /** A hybrid place's LED strips: drawn with the house layer, glowing (export_hybrid.py). */
        const val GLOW_LAYER = "glow_"

        /** Effects that follow the room's lights (the LED halos). */
        const val FX_HOUSE = "fxh_"
        val HOUSE_LAYERS = listOf(HOUSE_LAYER, GLOW_LAYER, FX_HOUSE)

        /** The screen's light in zones (bake_layers.py): their material names, and the zone each is. */
        val AMB_ZONES = mapOf("ambl_" to 0, "ambc_" to 1, "ambr_" to 2, "amb_" to -1)
        const val ALL_ZONES = -1

        /** Below this (summed red, green, blue) a zone's light is out and its layer not drawn. */
        const val AMB_OFF = 0.003f

        /** A change of a layer's colour (summed red, green, blue) smaller than this is not written. */
        const val AMB_STEP = 0.004f

        /** Room lights above this: the film's light is not drawn (see [setAmbience]). */
        const val AMB_HOUSE_CUTOFF = 0.6f

        /**
         * How far a real-time room light reaches when lights.json does not say, in metres. Short: 102 spots reaching
         * 10 m (every light on every part of the room) faulted the B3110's GPU and took the app down.
         */
        const val REALTIME_RADIUS = 3.5f

        /** Below this the room's lights are off and their layer is not drawn. */
        const val HOUSE_OFF = 0.002f
        /** Steps of light finer than this are not worth a pass over every material. */
        const val LIGHT_STEP = 0.01f
    }
}
