package dev.pampa.mycloudgallery.core.ai.testing

import dev.pampa.mycloudgallery.core.ai.contract.CutoutImage
import dev.pampa.mycloudgallery.core.ai.contract.NormalizedPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// LE FIXTURE SINTETICHE DEL RITAGLIO (docs/fase6-progetto.md §4.4 P13: "forme e sagome su sfondi diversi"; §5.2). Scene disegnate per
// costruzione con distanze con segno (forme piene con bordo antialias, sfondi con texture), senza nessun dato personale e senza file:
// le stesse fixture girano sulla JVM (fake, filtro guidato, maschera) e sul dispositivo (MagicTouch vero). Ogni fixture ha il punto
// toccato e la verita' (i pixel del soggetto toccato; null = nessun soggetto, E-V10).
//
// Le soglie con cui un ritagliatore le passa sono in [CutoutQuality], fissate PRIMA di misurare.

/** Una scena di prova: l'immagine, dove si tocca e cosa ci si aspetta. */
class CutoutFixture internal constructor(
  val name: String,
  val width: Int,
  val height: Int,
  private val argb: IntArray,
  val point: NormalizedPoint,
  private val truthBits: BooleanArray?,
) {
  /** Ha un soggetto toccato (altrimenti il punto cade nel vuoto e il ritaglio non deve dare niente). */
  val hasSubject: Boolean get() = truthBits != null

  /** I pixel del soggetto toccato (riga per riga), o null se non c'e'. */
  val truth: BooleanArray? get() = truthBits?.copyOf()

  fun image(): CutoutImage = AiTestInputs.cutout(width, height, argb)

  /** I pixel ARGB riga per riga. */
  fun pixels(): IntArray = argb.copyOf()

  internal fun hasPixels(other: IntArray): Boolean = argb.contentEquals(other)

  override fun toString(): String = "CutoutFixture($name, ${width}x$height)"
}

/** Il banco delle fixture. */
object CutoutFixtures {

  /** Tutte le fixture, nell'ordine fisso del banco. Le prime [SUBJECT_COUNT] hanno un soggetto, le altre sono il vuoto. */
  val all: List<CutoutFixture> by lazy { build() }

  val withSubject: List<CutoutFixture> get() = all.filter { it.hasSubject }

  val empty: List<CutoutFixture> get() = all.filter { !it.hasSubject }

  fun named(name: String): CutoutFixture = all.first { it.name == name }

  /**
   * L'oracolo per il ritagliatore finto: se [image] e' una delle fixture (stesse misure e stessi pixel) dice la verita' del soggetto
   * al punto, altrimenti null. Il fake lo usa per "disegnare la maschera dalla fixture" senza indovinarla dai colori.
   */
  fun truthFor(image: CutoutImage): CutoutFixture? =
    all.firstOrNull { it.width == image.width && it.height == image.height && it.hasPixels(image.raw()) }

  private fun build(): List<CutoutFixture> = listOf(
    discOnSky(),
    boxOnGrass(),
    starOnBricks(),
    ellipseOnBokeh(),
    person(),
    animal(),
    cupOnWood(),
    house(),
    twoObjects(),
    smallDuck(),
    balloonPanorama(),
    emptySky(),
    emptyBricks(),
    emptyBokeh(),
    emptyGrass(),
    emptyWall(),
    emptyNight(),
  )

  // --- Le scene con un soggetto --------------------------------------------------------------------------------------------------

  private fun discOnSky(): CutoutFixture {
    val w = 640
    val h = 480
    val u = 480f
    val disc = Layer(circle(320f, 250f, 0.22f * u), { x, y -> shaded(rgb(255, 150, 40), rgb(190, 70, 10), (x - 270f) / 100f + (y - 200f) / 100f) }, target = true, shadow = true)
    return render("disco-su-cielo", w, h, sky(11), listOf(disc), 0.5f, 0.52f)
  }

  private fun boxOnGrass(): CutoutFixture {
    val w = 640
    val h = 480
    val box = Layer(roundBox(290f, 270f, 130f, 80f, 14f), { x, y -> mix(rgb(40, 90, 210), rgb(120, 170, 255), 0.5f + 0.5f * sin(x * 0.05f) * 0.3f + (y - 190f) / 400f) }, target = true, shadow = true)
    val stripe = Layer(roundBox(290f, 270f, 130f, 14f, 3f), { _, _ -> rgb(235, 235, 245) }, target = true)
    return render("rettangolo-su-erba", w, h, grass(5), listOf(box, stripe), 290f / w, 232f / h)
  }

  private fun starOnBricks(): CutoutFixture {
    val w = 640
    val h = 480
    val star = Layer(polygon(starPoints(320f, 250f, 150f, 62f)), { x, y -> mix(rgb(250, 215, 40), rgb(225, 150, 20), (y - 100f) / 300f + noise(x * 0.2f, y * 0.2f, 3) * 0.1f) }, target = true, shadow = true)
    return render("stella-su-muro", w, h, bricks(), listOf(star), 0.5f, 0.52f)
  }

  private fun ellipseOnBokeh(): CutoutFixture {
    val w = 640
    val h = 480
    val ellipse = Layer(ellipse(330f, 250f, 170f, 105f), { x, y -> shaded(rgb(220, 30, 40), rgb(130, 10, 25), (x - 260f) / 140f + (y - 190f) / 130f) }, target = true)
    val glint = Layer(ellipse(290f, 215f, 40f, 15f), { _, _ -> rgb(255, 190, 190) }, target = true)
    return render("ellisse-su-sfocato", w, h, bokeh(21), listOf(ellipse, glint), 0.52f, 0.52f)
  }

  private fun person(): CutoutFixture {
    val w = 480
    val h = 640
    val skin = rgb(228, 178, 140)
    val hair = rgb(60, 40, 30)
    val shirt = rgb(35, 55, 110)
    val trousers = rgb(55, 55, 60)
    val head = circle(240f, 150f, 52f)
    val neck = capsule(240f, 195f, 240f, 215f, 17f)
    val torso = roundBox(240f, 330f, 78f, 110f, 34f)
    val armL = capsule(165f, 250f, 135f, 400f, 20f)
    val armR = capsule(315f, 250f, 345f, 400f, 20f)
    val legL = capsule(205f, 430f, 200f, 590f, 27f)
    val legR = capsule(275f, 430f, 282f, 590f, 27f)
    val layers = listOf(
      Layer(smoothUnion(legL, legR, 6f), { _, _ -> trousers }, target = true, shadow = true),
      Layer(smoothUnion(torso, smoothUnion(armL, armR, 4f), 10f), { _, y -> mix(shirt, rgb(20, 35, 80), (y - 220f) / 260f) }, target = true),
      Layer(neck, { _, _ -> skin }, target = true),
      Layer(head, { _, y -> if (y < 128f) hair else skin }, target = true),
    )
    return render("sagoma-persona", w, h, office(), layers, 0.5f, 0.52f)
  }

  private fun animal(): CutoutFixture {
    val w = 640
    val h = 480
    val brown = rgb(150, 100, 60)
    val dark = rgb(100, 65, 35)
    val body = ellipse(310f, 270f, 150f, 80f)
    val head = circle(470f, 215f, 56f)
    val legs = smoothUnion(
      smoothUnion(capsule(210f, 320f, 205f, 405f, 18f), capsule(255f, 330f, 255f, 408f, 18f), 4f),
      smoothUnion(capsule(370f, 330f, 372f, 408f, 18f), capsule(415f, 320f, 420f, 402f, 18f), 4f),
      4f,
    )
    val tail = capsule(170f, 250f, 120f, 195f, 14f)
    val ears = polygon(listOf(440f to 175f, 455f to 130f, 478f to 172f, 490f to 128f, 508f to 180f))
    val layers = listOf(
      Layer(smoothUnion(legs, tail, 3f), { _, _ -> dark }, target = true, shadow = true),
      Layer(body, { x, y -> mix(brown, dark, (y - 200f) / 160f + noise(x * 0.1f, y * 0.1f, 8) * 0.2f) }, target = true),
      Layer(head, { _, _ -> brown }, target = true),
      Layer(ears, { _, _ -> dark }, target = true),
    )
    return render("sagoma-animale", w, h, snow(), layers, 0.48f, 0.57f)
  }

  private fun cupOnWood(): CutoutFixture {
    val w = 640
    val h = 480
    val body = roundBox(310f, 270f, 100f, 105f, 26f)
    val handle = ring(circle(430f, 270f, 52f), 13f)
    val layers = listOf(
      Layer(handle, { _, _ -> rgb(235, 232, 225) }, target = true),
      Layer(body, { x, _ -> mix(rgb(250, 248, 240), rgb(205, 200, 190), (x - 210f) / 200f) }, target = true, shadow = true),
    )
    return render("tazza-su-legno", w, h, wood(), layers, 310f / w, 270f / h)
  }

  private fun house(): CutoutFixture {
    val w = 640
    val h = 480
    val wall = roundBox(320f, 300f, 140f, 95f, 4f)
    val roof = polygon(listOf(150f to 215f, 320f to 105f, 490f to 215f))
    val door = roundBox(320f, 345f, 28f, 50f, 3f)
    val layers = listOf(
      Layer(wall, { _, y -> mix(rgb(240, 225, 190), rgb(205, 185, 150), (y - 205f) / 190f) }, target = true, shadow = true),
      Layer(roof, { _, _ -> rgb(185, 55, 45) }, target = true),
      Layer(door, { _, _ -> rgb(110, 70, 40) }, target = true),
    )
    return render("casa", w, h, meadowAndSky(), layers, 0.5f, 0.57f)
  }

  private fun twoObjects(): CutoutFixture {
    val w = 640
    val h = 480
    val ball = Layer(circle(190f, 270f, 78f), { x, y -> shaded(rgb(220, 40, 40), rgb(130, 15, 15), (x - 140f) / 90f + (y - 220f) / 90f) }, target = true, shadow = true)
    val box = Layer(roundBox(470f, 280f, 90f, 95f, 8f), { _, y -> mix(rgb(45, 95, 215), rgb(25, 55, 150), (y - 190f) / 190f) }, shadow = true)
    return render("due-oggetti", w, h, wall(), listOf(ball, box), 190f / w, 270f / h)
  }

  private fun smallDuck(): CutoutFixture {
    val w = 640
    val h = 480
    val bodyY = 255f
    val duckBody = ellipse(320f, bodyY, 38f, 26f)
    val duckHead = circle(348f, bodyY - 30f, 17f)
    val beak = polygon(listOf(362f to bodyY - 32f, 382f to bodyY - 28f, 362f to bodyY - 22f))
    val layers = listOf(
      Layer(smoothUnion(duckBody, duckHead, 8f), { _, _ -> rgb(250, 215, 30) }, target = true, shadow = true),
      Layer(beak, { _, _ -> rgb(240, 120, 20) }, target = true),
    )
    return render("piccolo-papero", w, h, sand(), layers, 0.5f, 0.52f)
  }

  private fun balloonPanorama(): CutoutFixture {
    val w = 960
    val h = 400
    val balloon = circle(480f, 155f, 82f)
    val basket = roundBox(480f, 275f, 24f, 18f, 3f)
    val ropes = capsule(480f, 235f, 480f, 258f, 2f)
    val layers = listOf(
      Layer(ropes, { _, _ -> rgb(60, 40, 30) }, target = true),
      Layer(basket, { _, _ -> rgb(120, 80, 40) }, target = true),
      Layer(balloon, { x, _ -> if (((x - 480f) / 27f).toInt() % 2 == 0) rgb(220, 45, 55) else rgb(250, 235, 210) }, target = true),
    )
    return render("pallone-panorama", w, h, mountains(), layers, 0.5f, 0.38f)
  }

  // --- Le scene senza soggetto (E-V10) ----------------------------------------------------------------------------------------------

  private fun emptySky(): CutoutFixture = render("vuoto-cielo", 640, 480, sky(33), emptyList(), 0.5f, 0.3f)

  private fun emptyBricks(): CutoutFixture = render("vuoto-muro", 640, 480, bricks(), emptyList(), 0.5f, 0.5f)

  private fun emptyBokeh(): CutoutFixture = render("vuoto-sfocato", 640, 480, bokeh(44), emptyList(), 0.5f, 0.5f)

  private fun emptyGrass(): CutoutFixture = render("vuoto-prato", 640, 480, grass(9), emptyList(), 0.5f, 0.5f)

  private fun emptyWall(): CutoutFixture = render("vuoto-parete", 640, 480, wall(), emptyList(), 0.5f, 0.5f)

  private fun emptyNight(): CutoutFixture = render("vuoto-notte", 640, 480, night(), emptyList(), 0.5f, 0.5f)

  // --- Il disegno -----------------------------------------------------------------------------------------------------------------

  /** Uno strato: la forma (distanza con segno in pixel, negativa dentro), come si dipinge, se e' il soggetto toccato, se fa ombra. */
  private class Layer(val sdf: Sdf, val paint: Paint, val target: Boolean = false, val shadow: Boolean = false)

  private fun render(name: String, w: Int, h: Int, background: Paint, layers: List<Layer>, px: Float, py: Float): CutoutFixture {
    val pixels = IntArray(w * h)
    val targets = layers.filter { it.target }
    val truth = if (targets.isEmpty()) null else BooleanArray(w * h)
    val shadowShift = 7f
    for (yi in 0 until h) {
      val y = yi + 0.5f
      for (xi in 0 until w) {
        val x = xi + 0.5f
        var c = background(x, y)
        for (layer in layers) {
          if (layer.shadow) {
            val d = layer.sdf(x - shadowShift, y - shadowShift * 1.3f)
            val s = 0.38f * clamp01(1f - d / 9f)
            if (s > 0f) c = mix(c, rgb(0, 0, 0), s)
          }
        }
        for (layer in layers) {
          val d = layer.sdf(x, y)
          val cov = clamp01(0.5f - d)
          if (cov > 0f) c = mix(c, layer.paint(x, y), cov)
          if (truth != null && layer.target && d <= 0f) truth[yi * w + xi] = true
        }
        pixels[yi * w + xi] = c or (0xff shl 24)
      }
    }
    // Il soggetto toccato dev'essere davvero sotto il dito: una fixture che sbaglia il punto si vede subito.
    val point = NormalizedPoint(px, py)
    if (truth != null) {
      val ix = (px * (w - 1)).toInt()
      val iy = (py * (h - 1)).toInt()
      check(truth[iy * w + ix]) { "Fixture $name: il punto toccato non e' sul soggetto." }
    }
    return CutoutFixture(name, w, h, pixels, point, truth)
  }

  // --- Sfondi ---------------------------------------------------------------------------------------------------------------------

  private fun sky(seed: Int): Paint = { x, y ->
    val t = clamp01(y / 480f)
    val clouds = fbm(x * 0.006f, y * 0.01f, seed, 4)
    mix(mix(rgb(95, 160, 235), rgb(190, 220, 245), t), rgb(255, 255, 255), clamp01((clouds - 0.55f) * 2.2f) * 0.6f)
  }

  private fun grass(seed: Int): Paint = { x, y ->
    val n = fbm(x * 0.09f, y * 0.09f, seed, 4)
    val blade = noise(x * 0.5f, y * 0.12f, seed + 1)
    mix(rgb(45, 110, 40), rgb(110, 170, 60), n * 0.8f + blade * 0.3f)
  }

  private fun bricks(): Paint = { x, y ->
    val row = floor(y / 30f).toInt()
    val shift = if (row % 2 == 0) 0f else 30f
    val col = floor((x + shift) / 60f).toInt()
    val fx = (x + shift) - col * 60f
    val fy = y - row * 30f
    if (fx < 3f || fy < 3f) {
      rgb(190, 185, 175)
    } else {
      val tone = hash(col, row, 7)
      mix(rgb(160, 70, 50), rgb(190, 100, 70), tone) .let { mix(it, rgb(120, 55, 40), noise(x * 0.3f, y * 0.3f, 5) * 0.35f) }
    }
  }

  private fun bokeh(seed: Int): Paint {
    val circles = (0 until 14).map { i ->
      floatArrayOf(
        hash(i, 1, seed) * 640f, hash(i, 2, seed) * 480f, 40f + hash(i, 3, seed) * 70f,
        0.5f + hash(i, 4, seed) * 0.5f, 0.4f + hash(i, 5, seed) * 0.6f, 0.3f + hash(i, 6, seed) * 0.6f,
      )
    }
    return { x, y ->
      var r = 0.15f
      var g = 0.18f
      var b = 0.22f
      for (c in circles) {
        val d = hypot(x - c[0], y - c[1]) / c[2]
        val a = clamp01(1.4f - d) * 0.55f
        r = r * (1 - a) + c[3] * a
        g = g * (1 - a) + c[4] * a
        b = b * (1 - a) + c[5] * a
      }
      rgbf(r, g, b)
    }
  }

  private fun office(): Paint = { x, y ->
    val base = mix(rgb(226, 214, 190), rgb(190, 172, 145), y / 640f)
    val window = x in 60f..190f && y in 80f..300f
    if (window) mix(rgb(165, 205, 235), rgb(215, 235, 250), (y - 80f) / 220f) else base
  }

  private fun snow(): Paint = { x, y -> mix(rgb(238, 242, 248), rgb(214, 222, 235), fbm(x * 0.05f, y * 0.05f, 12, 3)) }

  private fun wood(): Paint = { x, y ->
    val stripe = sin(y * 0.14f + noise(x * 0.01f, y * 0.02f, 6) * 5f) * 0.5f + 0.5f
    mix(rgb(95, 60, 35), rgb(150, 100, 60), stripe * 0.8f + noise(x * 0.3f, y * 0.04f, 4) * 0.2f)
  }

  private fun meadowAndSky(): Paint = { x, y ->
    if (y < 330f) mix(rgb(120, 180, 240), rgb(205, 230, 248), y / 330f) else grass(15)(x, y)
  }

  private fun wall(): Paint = { x, y ->
    val base = mix(rgb(232, 226, 214), rgb(205, 197, 184), y / 480f)
    if (y > 390f) mix(rgb(150, 135, 120), rgb(120, 105, 90), (y - 390f) / 90f) else mix(base, rgb(255, 255, 255), noise(x * 0.01f, y * 0.01f, 2) * 0.1f)
  }

  private fun sand(): Paint = { x, y -> mix(rgb(222, 200, 150), rgb(200, 175, 125), fbm(x * 0.12f, y * 0.12f, 18, 4)) }

  private fun mountains(): Paint = { x, y ->
    val sky = mix(rgb(120, 170, 225), rgb(220, 232, 245), y / 400f)
    val ridge = 255f + 45f * sin(x * 0.012f) + 30f * sin(x * 0.031f + 1f) + 18f * noise(x * 0.05f, 0f, 3)
    if (y > ridge) mix(rgb(90, 105, 125), rgb(60, 75, 95), (y - ridge) / 150f) else sky
  }

  private fun night(): Paint = { x, y ->
    val base = mix(rgb(8, 12, 40), rgb(35, 40, 80), y / 480f)
    val star = hash(floor(x / 11f).toInt(), floor(y / 11f).toInt(), 9)
    if (star > 0.985f) mix(base, rgb(255, 255, 230), 0.9f) else base
  }

  // --- Forme (distanza con segno in pixel) ---------------------------------------------------------------------------------------

  private fun circle(cx: Float, cy: Float, r: Float): Sdf = { x, y -> hypot(x - cx, y - cy) - r }

  private fun ellipse(cx: Float, cy: Float, a: Float, b: Float): Sdf = { x, y ->
    val dx = x - cx
    val dy = y - cy
    val k = sqrt((dx * dx) / (a * a) + (dy * dy) / (b * b))
    if (k < 1e-6f) {
      -min(a, b)
    } else {
      val gx = dx / (a * a * k)
      val gy = dy / (b * b * k)
      (k - 1f) / max(hypot(gx, gy), 1e-6f)
    }
  }

  private fun roundBox(cx: Float, cy: Float, hw: Float, hh: Float, r: Float): Sdf = { x, y ->
    val qx = abs(x - cx) - (hw - r)
    val qy = abs(y - cy) - (hh - r)
    hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - r
  }

  private fun capsule(ax: Float, ay: Float, bx: Float, by: Float, r: Float): Sdf = { x, y ->
    val pax = x - ax
    val pay = y - ay
    val bax = bx - ax
    val bay = by - ay
    val t = clamp01((pax * bax + pay * bay) / (bax * bax + bay * bay))
    hypot(pax - bax * t, pay - bay * t) - r
  }

  private fun polygon(points: List<Pair<Float, Float>>): Sdf = { x, y ->
    var dist = Float.MAX_VALUE
    var inside = false
    var j = points.size - 1
    for (i in points.indices) {
      val (xi, yi) = points[i]
      val (xj, yj) = points[j]
      val ex = xj - xi
      val ey = yj - yi
      val wx = x - xi
      val wy = y - yi
      val t = clamp01((wx * ex + wy * ey) / (ex * ex + ey * ey))
      dist = min(dist, hypot(wx - ex * t, wy - ey * t))
      if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
      j = i
    }
    if (inside) -dist else dist
  }

  private fun ring(shape: Sdf, thickness: Float): Sdf = { x, y -> abs(shape(x, y)) - thickness }

  private fun smoothUnion(a: Sdf, b: Sdf, k: Float): Sdf = { x, y ->
    val da = a(x, y)
    val db = b(x, y)
    val hh = clamp01(0.5f + 0.5f * (db - da) / k)
    db + (da - db) * hh - k * hh * (1f - hh)
  }

  private fun starPoints(cx: Float, cy: Float, outer: Float, inner: Float): List<Pair<Float, Float>> =
    (0 until 10).map { i ->
      val angle = (-Math.PI / 2 + i * Math.PI / 5).toFloat()
      val r = if (i % 2 == 0) outer else inner
      (cx + r * cos(angle)) to (cy + r * sin(angle))
    }

  // --- Colori e rumore -----------------------------------------------------------------------------------------------------------

  private fun shaded(light: Int, dark: Int, t: Float): Int = mix(light, dark, clamp01(0.5f + 0.5f * t))

  private fun rgb(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b

  private fun rgbf(r: Float, g: Float, b: Float): Int = rgb((clamp01(r) * 255f + 0.5f).toInt(), (clamp01(g) * 255f + 0.5f).toInt(), (clamp01(b) * 255f + 0.5f).toInt())

  private fun mix(a: Int, b: Int, t: Float): Int {
    val k = clamp01(t)
    val r = ((a shr 16) and 0xff) * (1 - k) + ((b shr 16) and 0xff) * k
    val g = ((a shr 8) and 0xff) * (1 - k) + ((b shr 8) and 0xff) * k
    val bl = (a and 0xff) * (1 - k) + (b and 0xff) * k
    return (r.toInt() shl 16) or (g.toInt() shl 8) or bl.toInt()
  }

  private fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

  /** Un numero in [0, 1) da una cella e un seme: aritmetica intera, lo stesso su ogni dispositivo. */
  private fun hash(ix: Int, iy: Int, seed: Int): Float {
    var h = ix * 374761393 + iy * 668265263 + seed * 1442695041
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xffffff) / 16777216f
  }

  /** Rumore di valori con interpolazione liscia. */
  private fun noise(x: Float, y: Float, seed: Int): Float {
    val ix = floor(x).toInt()
    val iy = floor(y).toInt()
    val fx = x - ix
    val fy = y - iy
    val sx = fx * fx * (3f - 2f * fx)
    val sy = fy * fy * (3f - 2f * fy)
    val a = hash(ix, iy, seed)
    val b = hash(ix + 1, iy, seed)
    val c = hash(ix, iy + 1, seed)
    val d = hash(ix + 1, iy + 1, seed)
    return (a * (1 - sx) + b * sx) * (1 - sy) + (c * (1 - sx) + d * sx) * sy
  }

  private fun fbm(x: Float, y: Float, seed: Int, octaves: Int): Float {
    var sum = 0f
    var amp = 0.5f
    var fx = x
    var fy = y
    var norm = 0f
    for (o in 0 until octaves) {
      sum += amp * noise(fx, fy, seed + o)
      norm += amp
      amp *= 0.5f
      fx *= 2f
      fy *= 2f
    }
    return sum / norm
  }
}

private typealias Sdf = (Float, Float) -> Float
private typealias Paint = (Float, Float) -> Int
