package dev.pampa.mycloudgallery.bench

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.Build
import android.util.Base64
import dev.pampa.mycloudgallery.core.metadata.contract.ThumbHashCodec
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.SplittableRandom
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

// La REPLICA SINTETICA della variante di misura (docs/fase4-progetto.md §5.6, P10): pacchi di miniature WebP **vere** che
// il dataset misto (60% solo-NAS) serve come se venissero dalla replica locale del NAS (D15). Nessun contratto di
// `core-index` qui dentro: l'app non vede ancora `ReplicaBlobs` (lo vedra' con P11) e questa variante deve compilare gia'.
// Il formato e' nostro e minimo; l'adattatore verso `ReplicaBlobs` e' di una ventina di righe e sta in docs/bench/README.md
// §6-bis. Quando P4 consegna il formato vero dei pacchi (`replica_blob` + file in `noBackupFilesDir/replica/packs/`) il
// seeder lo scrivera' al posto di questo (stesso schema: chiave di contenuto ridotta, classe, offset, lunghezza).

/**
 * Le classi d'immagine della replica, con il codice dei segmenti (`docs/fase4-progetto.md` §2.1: 2 `thumbS`, 3 `thumbM`,
 * 5 `hq`). Sono numeri e non `SegmentClass` perche' questa variante non vede `core-trust`.
 */
internal object BenchBlobClass {
  const val THUMB_S: Int = 2
  const val THUMB_M: Int = 3
  const val HQ: Int = 5
}

/** Un blob dentro il pacco dei modelli. */
internal class BenchBlob(val offset: Int, val length: Int)

/** Dove sta un blob della replica (il `BlobLocation` di `core-index`, senza il tipo): il pacco e' uno solo, `packId` = 0. */
internal class BenchBlobLocation(val packId: Long, val offset: Long, val length: Int, val cls: Int)

/** Le proporzioni dei modelli (millesimi larghezza / altezza): le piu' comuni di una libreria di foto. */
internal object BenchAspect {
  val BUCKETS_MILLI: IntArray = intArrayOf(562, 667, 750, 1000, 1333, 1500, 1778, 3000)

  /** Il modello piu' vicino alla proporzione [aspectMilli] (distanza in scala logaritmica: 2:1 dista quanto 1:2). */
  fun bucketOf(aspectMilli: Int): Int {
    val target = ln(aspectMilli.coerceAtLeast(1).toDouble())
    var best = 0
    var bestDistance = Double.MAX_VALUE
    for (i in BUCKETS_MILLI.indices) {
      val distance = abs(ln(BUCKETS_MILLI[i].toDouble()) - target)
      if (distance < bestDistance) {
        bestDistance = distance
        best = i
      }
    }
    return best
  }
}

/**
 * Un'immagine-modello: il blob S (lato corto 200 px), il blob M (400 px), l'HQ (lato lungo 2048 px) solo per alcuni, e il
 * ThumbHash per il segnaposto. Molte chiavi di contenuto puntano allo stesso modello: il pacco resta di pochi MB, mentre
 * l'indice (chiave -> blob) ha tante voci quante la replica vera.
 */
internal class BenchTemplate(
  val bucket: Int,
  val variant: Int,
  val s: BenchBlob,
  val m: BenchBlob,
  val hq: BenchBlob?,
  val thumbHash: ByteArray,
)

/** L'insieme dei modelli, con il file del pacco. */
internal class BenchTemplateSet(
  val packFile: File,
  val perBucket: Int,
  val hqPerBucket: Int,
  val templates: List<BenchTemplate>,
  /** Millisecondi spesi a generarli ora (0 se erano gia' sul dispositivo). */
  val generationMs: Long,
) {
  /** Il modello di un'immagine con proporzione [bucket], scelto da [salt] (tutti i modelli del gruppo). */
  fun template(bucket: Int, salt: Int): BenchTemplate = templates[bucket * perBucket + Math.floorMod(salt, perBucket)]

  /** Come [template] ma solo fra quelli che hanno anche l'HQ. */
  fun hqTemplate(bucket: Int, salt: Int): BenchTemplate = templates[bucket * perBucket + Math.floorMod(salt, hqPerBucket)]

  val packBytes: Long get() = packFile.length()
  val averageBytesS: Int get() = templates.map { it.s.length }.average().roundToInt()
  val averageBytesM: Int get() = templates.map { it.m.length }.average().roundToInt()
  val averageBytesHq: Int get() = templates.mapNotNull { it.hq?.length }.takeIf { it.isNotEmpty() }?.average()?.roundToInt() ?: 0
}

/**
 * Crea (o ritrova) i modelli: foto finte ma con la complessita' di una foto (sfumature, macchie morbide, grana), codificate
 * in **WebP vero** con `Bitmap.compress`. Il pacco e il suo elenco stanno in `noBackupFilesDir/bench-replica/`; si rifanno
 * solo se cambia [VERSION] o la taglia (smoke / misura).
 */
internal object BenchReplicaBuilder {

  /** Da alzare quando cambia il modo di disegnare o codificare: i modelli vecchi si rifanno. */
  const val VERSION: Int = 3

  private const val SHORT_S = 200
  private const val SHORT_M = 400
  private const val LONG_HQ = 2048
  private const val QUALITY_S = 70
  private const val QUALITY_M = 75
  private const val QUALITY_HQ = 80
  private const val SEED = 0x4D43475245504C49L


  /**
   * La grana: una macchia ogni [density] pixel con trasparenza fra [alphaMin] e [alphaMin] + [alphaRange]. Regola il peso
   * del WebP (la S piu' densa della M: a parita' di contenuto una miniatura piccola ha piu' dettaglio per pixel) per
   * avvicinarlo ai numeri del §2.10: circa 7-8 KB la S, 20 KB la M, 250 KB l'HQ.
   */
  private class Grain(val density: Int, val alphaMin: Int, val alphaRange: Int)

  private val GRAIN_S = Grain(density = 13, alphaMin = 40, alphaRange = 80)
  private val GRAIN_M = Grain(density = 14, alphaMin = 36, alphaRange = 70)
  private val GRAIN_HQ = Grain(density = 26, alphaMin = 30, alphaRange = 60)

  fun directory(context: Context): File = File(context.noBackupFilesDir, "bench-replica")

  /** Il pacco dei modelli che c'e' sul dispositivo (uno solo: gli altri si tolgono), o `null` se non e' stato creato. */
  fun existingPack(context: Context): File? =
    directory(context).listFiles()?.firstOrNull { it.name.startsWith("templates-") && it.name.endsWith(".pack") }

  fun templates(context: Context, smoke: Boolean): BenchTemplateSet {
    val dir = directory(context)
    dir.mkdirs()
    val perBucket = if (smoke) 6 else 24
    val hqPerBucket = if (smoke) 1 else 3
    val tag = "v$VERSION-$perBucket-$hqPerBucket"
    val pack = File(dir, "templates-$tag.pack")
    val meta = File(dir, "templates-$tag.json")
    // Via i modelli di altre taglie o versioni: non servono piu' (e un emulatore con poco spazio ringrazia).
    dir.listFiles()?.filter { it.name.startsWith("templates-") && !it.name.startsWith("templates-$tag.") }?.forEach { it.delete() }
    load(pack, meta, perBucket, hqPerBucket)?.let { return it }
    return generate(pack, meta, perBucket, hqPerBucket)
  }

  private fun load(pack: File, meta: File, perBucket: Int, hqPerBucket: Int): BenchTemplateSet? {
    if (!pack.exists() || !meta.exists()) return null
    return try {
      val json = JSONObject(meta.readText())
      if (json.getLong("packBytes") != pack.length()) return null
      val array = json.getJSONArray("templates")
      if (array.length() != BenchAspect.BUCKETS_MILLI.size * perBucket) return null
      val list = ArrayList<BenchTemplate>(array.length())
      for (i in 0 until array.length()) {
        val t = array.getJSONObject(i)
        list += BenchTemplate(
          bucket = t.getInt("b"),
          variant = t.getInt("v"),
          s = BenchBlob(t.getInt("so"), t.getInt("sl")),
          m = BenchBlob(t.getInt("mo"), t.getInt("ml")),
          hq = if (t.has("ho")) BenchBlob(t.getInt("ho"), t.getInt("hl")) else null,
          thumbHash = Base64.decode(t.getString("th"), Base64.NO_WRAP),
        )
      }
      BenchTemplateSet(pack, perBucket, hqPerBucket, list, generationMs = 0L)
    } catch (e: Exception) {
      null
    }
  }

  private fun generate(pack: File, meta: File, perBucket: Int, hqPerBucket: Int): BenchTemplateSet {
    val startNs = System.nanoTime()
    val tmp = File(pack.parentFile, pack.name + ".tmp")
    val list = ArrayList<BenchTemplate>(BenchAspect.BUCKETS_MILLI.size * perBucket)
    var position = 0
    BufferedOutputStream(FileOutputStream(tmp), 64 * 1024).use { out ->
      fun put(bytes: ByteArray): BenchBlob {
        out.write(bytes)
        val blob = BenchBlob(position, bytes.size)
        position += bytes.size
        return blob
      }
      for (bucket in BenchAspect.BUCKETS_MILLI.indices) {
        val aspect = BenchAspect.BUCKETS_MILLI[bucket]
        for (variant in 0 until perBucket) {
          val seed = SEED xor (bucket * 1_000L + variant)
          val (mw, mh) = sizeForShortSide(aspect, SHORT_M)
          val (sw, sh) = sizeForShortSide(aspect, SHORT_S)
          val medium = draw(mw, mh, seed, GRAIN_M)
          // La S non e' la M rimpicciolita (perderebbe la grana): stessa scena, disegnata alla sua taglia.
          val small = draw(sw, sh, seed, GRAIN_S)
          val s = put(encode(small, QUALITY_S))
          val m = put(encode(medium, QUALITY_M))
          val hash = thumbHash(small)
          small.recycle()
          medium.recycle()
          val hq = if (variant < hqPerBucket) {
            val (hw, hh) = sizeForLongSide(aspect, LONG_HQ)
            val big = draw(hw, hh, seed xor 0x5A5A, GRAIN_HQ)
            val blob = put(encode(big, QUALITY_HQ))
            big.recycle()
            blob
          } else {
            null
          }
          list += BenchTemplate(bucket, variant, s, m, hq, hash)
        }
      }
    }
    if (!tmp.renameTo(pack)) {
      pack.delete()
      check(tmp.renameTo(pack)) { "Pacco dei modelli non scritto." }
    }
    val array = JSONArray()
    for (t in list) {
      val o = JSONObject().put("b", t.bucket).put("v", t.variant).put("so", t.s.offset).put("sl", t.s.length)
        .put("mo", t.m.offset).put("ml", t.m.length).put("th", Base64.encodeToString(t.thumbHash, Base64.NO_WRAP))
      t.hq?.let { o.put("ho", it.offset).put("hl", it.length) }
      array.put(o)
    }
    // L'elenco per ultimo: se manca, i modelli si rifanno (un pacco senza elenco non vale).
    meta.writeText(JSONObject().put("version", VERSION).put("packBytes", pack.length()).put("templates", array).toString())
    return BenchTemplateSet(pack, perBucket, hqPerBucket, list, generationMs = (System.nanoTime() - startNs) / 1_000_000)
  }

  /** Larghezza e altezza con proporzione [aspectMilli] e lato corto [side]. */
  private fun sizeForShortSide(aspectMilli: Int, side: Int): Pair<Int, Int> =
    if (aspectMilli >= 1000) (side * aspectMilli / 1000.0).roundToInt() to side else side to (side * 1000.0 / aspectMilli).roundToInt()

  /** Larghezza e altezza con proporzione [aspectMilli] e lato lungo [side]. */
  private fun sizeForLongSide(aspectMilli: Int, side: Int): Pair<Int, Int> =
    if (aspectMilli >= 1000) side to (side * 1000.0 / aspectMilli).roundToInt() else (side * aspectMilli / 1000.0).roundToInt() to side

  /**
   * Una "foto": cielo e terra sfumati, una ventina di macchie morbide e della grana. Serve complessita' vera perche' il
   * WebP pesi e si decodifichi come una miniatura vera (7-8 KB per la S e circa 20 KB per la M, le stime del §2.10).
   */
  private fun draw(w: Int, h: Int, seed: Long, grain: Grain): Bitmap {
    val rnd = SplittableRandom(seed)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val fw = w.toFloat()
    val fh = h.toFloat()
    val side = minOf(w, h).toFloat()

    val hue = rnd.nextInt(360).toFloat()
    val sky = Color.HSVToColor(floatArrayOf(hue, 0.25f + rnd.nextFloat() * 0.35f, 0.85f + rnd.nextFloat() * 0.15f))
    val ground = Color.HSVToColor(floatArrayOf((hue + 30f + rnd.nextInt(90)) % 360f, 0.3f + rnd.nextFloat() * 0.4f, 0.25f + rnd.nextFloat() * 0.4f))
    paint.shader = LinearGradient(0f, 0f, 0f, fh, sky, ground, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, fw, fh, paint)

    repeat(14 + rnd.nextInt(14)) {
      val cx = rnd.nextFloat() * fw
      val cy = rnd.nextFloat() * fh
      val radius = side * (0.08f + rnd.nextFloat() * 0.35f)
      val color = Color.HSVToColor(160, floatArrayOf(rnd.nextInt(360).toFloat(), 0.2f + rnd.nextFloat() * 0.7f, 0.2f + rnd.nextFloat() * 0.8f))
      paint.shader = RadialGradient(cx, cy, radius, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
      canvas.drawCircle(cx, cy, radius, paint)
    }

    // La grana: tanti rettangolini quasi trasparenti, di luminosita' casuale (come il rumore del sensore).
    paint.shader = null
    val speckSize = (side / 400f).coerceAtLeast(1f)
    val specks = (w.toLong() * h / grain.density).toInt().coerceAtMost(150_000)
    repeat(specks) {
      val v = 40 + rnd.nextInt(180)
      paint.color = Color.argb(grain.alphaMin + rnd.nextInt(grain.alphaRange), v, v, v)
      val x = rnd.nextFloat() * fw
      val y = rnd.nextFloat() * fh
      canvas.drawRect(x, y, x + speckSize * (1 + rnd.nextInt(3)), y + speckSize * (1 + rnd.nextInt(3)), paint)
    }
    return bitmap
  }

  /** WebP con perdita (`WEBP_LOSSY` da API 30, `WEBP` prima). Controlla l'intestazione `RIFF....WEBP`: niente file finti. */
  private fun encode(bitmap: Bitmap, quality: Int): ByteArray {
    val out = ByteArrayOutputStream(32 * 1024)
    @Suppress("DEPRECATION")
    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    check(bitmap.compress(format, quality, out)) { "WebP non codificato." }
    val bytes = out.toByteArray()
    check(isWebp(bytes)) { "L'uscita non e' un WebP." }
    return bytes
  }

  internal fun isWebp(bytes: ByteArray): Boolean =
    bytes.size > 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() &&
      bytes[3] == 'F'.code.toByte() && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
      bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()

  /** Il ThumbHash di un'immagine (ridotta a 32 px sul lato lungo): quello che l'indice porta in `th`. */
  private fun thumbHash(bitmap: Bitmap): ByteArray {
    val scale = 32f / maxOf(bitmap.width, bitmap.height)
    val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    val tiny = Bitmap.createScaledBitmap(bitmap, w, h, true)
    val pixels = IntArray(w * h)
    tiny.getPixels(pixels, 0, w, 0, 0, w, h)
    tiny.recycle()
    val rgba = ByteArray(w * h * 4)
    for (i in pixels.indices) {
      val p = pixels[i]
      rgba[i * 4] = (p shr 16).toByte()
      rgba[i * 4 + 1] = (p shr 8).toByte()
      rgba[i * 4 + 2] = p.toByte()
      rgba[i * 4 + 3] = (p ushr 24).toByte()
    }
    return ThumbHashCodec.encode(w, h, rgba).bytes()
  }
}

/**
 * Scrive l'indice della replica: per ogni (chiave di contenuto ridotta, classe) dove sta il blob. Le voci si aggiungono
 * in qualunque ordine e [writeTo] le ordina per (chiave, classe), come la ricerca binaria di [BenchReplicaStore] vuole.
 */
internal class BenchReplicaIndexWriter(capacity: Int) {

  private var keys = LongArray(capacity.coerceAtLeast(16))
  private var classes = ByteArray(capacity.coerceAtLeast(16))
  private var offsets = IntArray(capacity.coerceAtLeast(16))
  private var lengths = IntArray(capacity.coerceAtLeast(16))

  var size: Int = 0
    private set

  fun add(contentKey: Long, cls: Int, blob: BenchBlob) {
    if (size == keys.size) {
      val grown = size * 2
      keys = keys.copyOf(grown)
      classes = classes.copyOf(grown)
      offsets = offsets.copyOf(grown)
      lengths = lengths.copyOf(grown)
    }
    keys[size] = contentKey
    classes[size] = cls.toByte()
    offsets[size] = blob.offset
    lengths[size] = blob.length
    size++
  }

  fun count(cls: Int): Int {
    var n = 0
    for (i in 0 until size) if (classes[i].toInt() == cls) n++
    return n
  }

  /** Formato: `BRX1`, N (int), poi N voci da 17 byte: chiave (8), classe (1), offset (4), lunghezza (4), big-endian. */
  fun writeTo(file: File) {
    val order = (0 until size).sortedWith(compareBy<Int>({ keys[it] }, { classes[it] }))
    val buffer = ByteBuffer.allocate(8 + size * ENTRY_BYTES).order(ByteOrder.BIG_ENDIAN)
    buffer.put(MAGIC)
    buffer.putInt(size)
    for (i in order) {
      buffer.putLong(keys[i])
      buffer.put(classes[i])
      buffer.putInt(offsets[i])
      buffer.putInt(lengths[i])
    }
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeBytes(buffer.array())
    if (!tmp.renameTo(file)) {
      file.delete()
      check(tmp.renameTo(file)) { "Indice della replica non scritto." }
    }
  }

  companion object {
    val MAGIC: ByteArray = "BRX1".toByteArray(Charsets.US_ASCII)
    const val ENTRY_BYTES = 17
  }
}

/**
 * La replica sintetica letta come la leggerebbe la vera (D15): l'indice in memoria (chiavi ordinate, ricerca binaria), il
 * pacco mappato in sola lettura e mai copiato. [locate] e [read] sono bloccanti e veloci, da chiamare dal dispatcher delle
 * miniature. Un solo pacco: `packId` e' sempre 0.
 */
internal class BenchReplicaStore private constructor(
  private val pack: ByteBuffer,
  private val keys: LongArray,
  private val classes: ByteArray,
  private val offsets: IntArray,
  private val lengths: IntArray,
  val indexBytes: Long,
  val packBytes: Long,
) {

  val blobCount: Int get() = keys.size

  fun locate(contentKey: Long, cls: Int): BenchBlobLocation? {
    var lo = 0
    var hi = keys.size - 1
    while (lo <= hi) {
      val mid = (lo + hi) ushr 1
      val k = keys[mid]
      when {
        k < contentKey -> lo = mid + 1
        k > contentKey -> hi = mid - 1
        else -> {
          // Poche classi per chiave (S, M, HQ): si scorre dal primo uguale.
          var i = mid
          while (i > 0 && keys[i - 1] == contentKey) i--
          while (i < keys.size && keys[i] == contentKey) {
            if (classes[i].toInt() == cls) return BenchBlobLocation(0L, offsets[i].toLong(), lengths[i], cls)
            i++
          }
          return null
        }
      }
    }
    return null
  }

  /** I byte del blob: una fetta in sola lettura del pacco mappato, senza copie. */
  fun read(location: BenchBlobLocation): ByteBuffer {
    val offset = location.offset.toInt()
    if (offset < 0 || offset + location.length > pack.capacity()) throw IOException("blob fuori dal pacco")
    val view = pack.duplicate()
    view.position(offset)
    view.limit(offset + location.length)
    return view.slice().asReadOnlyBuffer()
  }

  /** I byte del blob come array (per i controlli e per chi decodifica con `BitmapFactory`). */
  fun readBytes(location: BenchBlobLocation): ByteArray {
    val buffer = read(location)
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
  }

  companion object {
    fun indexFile(context: Context, profile: BenchProfile): File = File(BenchReplicaBuilder.directory(context), "${profile.code}.idx")

    /** Apre l'indice del profilo e il pacco dei modelli; `null` se mancano (la replica non e' stata creata). */
    fun open(context: Context, profile: BenchProfile): BenchReplicaStore? {
      val pack = BenchReplicaBuilder.existingPack(context) ?: return null
      return open(context, profile, pack)
    }

    fun open(context: Context, profile: BenchProfile, packFile: File): BenchReplicaStore? {
      val index = indexFile(context, profile)
      if (!index.exists() || !packFile.exists()) return null
      return try {
        val bytes = index.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(4).also { buffer.get(it) }
        if (!magic.contentEquals(BenchReplicaIndexWriter.MAGIC)) return null
        val n = buffer.int
        if (n < 0 || bytes.size != 8 + n * BenchReplicaIndexWriter.ENTRY_BYTES) return null
        val keys = LongArray(n)
        val classes = ByteArray(n)
        val offsets = IntArray(n)
        val lengths = IntArray(n)
        for (i in 0 until n) {
          keys[i] = buffer.long
          classes[i] = buffer.get()
          offsets[i] = buffer.int
          lengths[i] = buffer.int
        }
        val mapped = RandomAccessFile(packFile, "r").use { file ->
          file.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        }
        BenchReplicaStore(mapped, keys, classes, offsets, lengths, indexBytes = bytes.size.toLong(), packBytes = packFile.length())
      } catch (e: Exception) {
        null
      }
    }
  }
}

/** L'esito del controllo della replica appena creata. */
internal class BenchReplicaCheck(
  val asked: Int,
  val ok: Int,
  /** Tempo medio di decodifica di un blob S e di uno M su questo dispositivo, in ms (0 se non misurato). */
  val decodeSMs: Double,
  val decodeMMs: Double,
)

/** Controlla che la replica serva davvero: ogni chiave campione ha S e M, sono WebP e si decodificano. */
internal object BenchReplicaVerifier {

  fun check(store: BenchReplicaStore, sampleKeys: LongArray): BenchReplicaCheck {
    var ok = 0
    var sNs = 0L
    var mNs = 0L
    var decoded = 0
    for (key in sampleKeys) {
      val s = store.locate(key, BenchBlobClass.THUMB_S)
      val m = store.locate(key, BenchBlobClass.THUMB_M)
      if (s == null || m == null) continue
      val sBytes = store.readBytes(s)
      val mBytes = store.readBytes(m)
      if (!BenchReplicaBuilder.isWebp(sBytes) || !BenchReplicaBuilder.isWebp(mBytes)) continue
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(sBytes, 0, sBytes.size, bounds)
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue
      // Decodifica vera su un campione (la prima volta paga il caricamento del codec: non conta nella media).
      if (decoded < DECODED_SAMPLES) {
        val t0 = System.nanoTime()
        val sBitmap = BitmapFactory.decodeByteArray(sBytes, 0, sBytes.size) ?: continue
        val t1 = System.nanoTime()
        val mBitmap = BitmapFactory.decodeByteArray(mBytes, 0, mBytes.size) ?: continue
        val t2 = System.nanoTime()
        sBitmap.recycle()
        mBitmap.recycle()
        if (decoded > 0) {
          sNs += t1 - t0
          mNs += t2 - t1
        }
        decoded++
      }
      ok++
    }
    val timed = (decoded - 1).coerceAtLeast(0)
    return BenchReplicaCheck(
      asked = sampleKeys.size,
      ok = ok,
      decodeSMs = if (timed > 0) sNs / timed / 1_000_000.0 else 0.0,
      decodeMMs = if (timed > 0) mNs / timed / 1_000_000.0 else 0.0,
    )
  }

  private const val DECODED_SAMPLES = 17
}
