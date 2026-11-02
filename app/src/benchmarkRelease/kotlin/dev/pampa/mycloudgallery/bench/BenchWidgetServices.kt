package dev.pampa.mycloudgallery.bench

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.pampa.mycloudgallery.core.library.visibility.RotatingWidgetFeed
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.widget.contract.WidgetChoice
import dev.pampa.mycloudgallery.core.widget.contract.WidgetClock
import dev.pampa.mycloudgallery.core.widget.contract.WidgetLedger
import dev.pampa.mycloudgallery.core.widget.contract.WidgetLock
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMemories
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMemoryPick
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPeriod
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlanner
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPrefs
import dev.pampa.mycloudgallery.core.widget.contract.WidgetThumbs
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTitle
import dev.pampa.mycloudgallery.core.widget.ledger.FileWidgetLedger
import dev.pampa.mycloudgallery.core.widget.plan.DefaultWidgetPlanner
import dev.pampa.mycloudgallery.feature.widget.host.WidgetAppearance
import dev.pampa.mycloudgallery.feature.widget.host.WidgetPlaceNames
import dev.pampa.mycloudgallery.feature.widget.host.WidgetServices
import dev.pampa.mycloudgallery.feature.widget.host.WidgetThumbnails
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.memories.MemoriesGraph
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Il **seme del widget** della variante di misura (Fase 10, P9; `docs/fase10-progetto.md` §5.4, scenario `widget`). Le porte di `feature-widget` ([WidgetServices]) sopra la
 * Libreria da 100 mila righe del dataset, **senza scrivere il legame di produzione** (`WidgetGraph` e' di P13: finche' non e' su `main` il widget vero non disegna).
 *
 * Cosa e' vero e cosa e' di prova:
 *  - **vero**: il pianificatore ([DefaultWidgetPlanner] sopra [RotatingWidgetFeed]), la visibilita' e la maschera dei nascosti **del catalogo vero** (quelle di [GalleryGraph]:
 *    questo file non nomina i contratti dei Nascosti, li passa soltanto, cosi' il registro delle superfici non ha una riga in piu'), il registro su file ([FileWidgetLedger]),
 *    il ricordo di oggi letto dallo scaffale seminato (`BenchShelf`), il motore del ridisegno, Glance e la consegna ad `AppWidgetManager` (tutto dentro `feature-widget`);
 *  - **di prova**: le miniature ([SyntheticThumbnails]: una decodifica WebP di 1024x768 ridotta al lato lungo chiesto, ogni media ne ha una: la replica del NAS sintetico
 *    ha solo i blob S e M per riga e non e' agganciata ai `MediaId`), il blocco dell'app (spento), le scelte (di serie: il ricordo di oggi, poi le foto) e i nomi dei luoghi.
 *
 * **Dopo P13** (che lega `WidgetGraph` in `Application.onCreate`): questo file e `BenchWidgetDriver.installProvider` non servono piu'; resta il pilota (`BenchWidgetDriver`)
 * che lega un widget grande e chiede i giri, sopra le porte vere sul dataset di misura.
 */
internal class BenchWidgetServices(context: Context, private val memories: MemoriesGraph, gallery: GalleryGraph) : WidgetServices {

  override val clock: WidgetClock = object : WidgetClock {
    override fun nowLocal(): LocalDateTime = LocalDateTime.now()

    override fun monotonicMs(): Long = SystemClock.elapsedRealtime()
  }

  /** Il registro vero su file, nella cache del dataset di misura (non nei dati dell'utente, non nel backup). */
  override val ledger: WidgetLedger = FileWidgetLedger(File(context.cacheDir, "bench-widget"))

  override val prefs: WidgetPrefs = object : WidgetPrefs {
    override val choice = MutableStateFlow(WidgetChoice.DEFAULT)
    override val period = MutableStateFlow(WidgetPeriod.DEFAULT)

    override suspend fun setChoice(choice: WidgetChoice) {
      this.choice.value = choice
    }

    override suspend fun setPeriod(period: WidgetPeriod) {
      this.period.value = period
    }
  }

  private val lock: WidgetLock = object : WidgetLock {
    override val appLocked: StateFlow<Boolean> = MutableStateFlow(false)

    override suspend fun onPhone(ids: Collection<MediaId>): Set<MediaId> = emptySet()
  }

  /** Il ricordo di oggi: un ricordo dello scaffale (seminato o generato) pubblicato oggi, mai `PERSON`, con abbastanza elementi (D3). */
  private val shelf = object : WidgetMemories {
    override suspend fun today(): WidgetMemoryPick? {
      val file = memories.live.value?.engine?.current() ?: return null
      val today = dev.pampa.mycloudgallery.core.personal.contract.MemoryDays.dayOf(memories.today())
      val memory = file.memories.firstOrNull { it.kind != MemoryKind.PERSON && it.publishedDay == today && it.members.size >= MIN_MEMORY_MEMBERS } ?: return null
      val title = memory.title
      return WidgetMemoryPick(
        id = memory.id,
        kind = memory.kind,
        title = WidgetTitle(title.yearsAgo, title.placeIds.take(WidgetTitle.MAX_PLACES), title.tripLabel, title.month),
        cover = memory.cover,
        members = memory.members,
      )
    }
  }

  override val planner: WidgetPlanner = DefaultWidgetPlanner(
    feed = RotatingWidgetFeed(gallery.visibleMedia, gallery.hiddenMask),
    memories = shelf,
    visible = gallery.visibleMedia,
    mask = gallery.hiddenMask,
    // Ogni media ha la sua miniatura locale: la misura e' il giro, non la presenza delle miniature.
    thumbs = object : WidgetThumbs {
      override suspend fun local(ids: Collection<MediaId>): Set<MediaId> = ids.toSet()
    },
    lock = lock,
    prefs = prefs,
    clock = clock,
    ledger = ledger,
  )

  override val thumbnails: WidgetThumbnails = SyntheticThumbnails()

  override val places: WidgetPlaceNames = WidgetPlaceNames { memories.placeLabel(it) }

  override val appearance: WidgetAppearance = WidgetAppearance { EngineSettings() }

  private companion object {
    /** Un ricordo va sul widget solo con almeno 8 elementi (D3, `WidgetRules.MEMORY_MIN_VISIBLE`). */
    const val MIN_MEMORY_MEMBERS = 8
  }
}

/**
 * Miniature di prova: **una decodifica vera** (WebP con perdita, 1024x768, un disegno con rumore: circa 60-120 KB) ridotta al lato lungo chiesto, a ogni chiamata.
 * La bitmap sorgente si costruisce una volta (a caldo dal primo uso) e non e' un dato dell'utente. Il costo e' quello di una miniatura locale di 384-512 px.
 */
internal class SyntheticThumbnails : WidgetThumbnails {

  private val encoded: ByteArray by lazy(LazyThreadSafetyMode.PUBLICATION) { encode() }

  override suspend fun load(media: MediaId, longSidePx: Int): Bitmap? {
    val bytes = encoded
    // La riduzione al decodificatore (potenze di due) e poi la misura esatta: come un decodificatore di miniature.
    var sample = 1
    while (SOURCE_WIDTH / (sample * 2) >= longSidePx) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    if (maxOf(decoded.width, decoded.height) <= longSidePx) return decoded
    val scale = longSidePx.toFloat() / maxOf(decoded.width, decoded.height)
    val scaled = Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
    if (scaled !== decoded) decoded.recycle()
    return scaled
  }

  private fun encode(): ByteArray {
    val source = Bitmap.createBitmap(SOURCE_WIDTH, SOURCE_HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(source)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawColor(Color.rgb(40, 60, 90))
    // Rettangoli e cerchi di colori diversi con un seme fisso: abbastanza dettaglio perche' il WebP non sia un colore solo.
    val random = java.util.Random(SEED)
    repeat(SHAPES) {
      paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
      val x = random.nextFloat() * SOURCE_WIDTH
      val y = random.nextFloat() * SOURCE_HEIGHT
      val r = 12f + random.nextFloat() * 120f
      if (it % 2 == 0) canvas.drawCircle(x, y, r, paint) else canvas.drawRect(RectF(x, y, x + r * 1.6f, y + r), paint)
    }
    val out = ByteArrayOutputStream()
    @Suppress("DEPRECATION")
    source.compress(Bitmap.CompressFormat.WEBP, QUALITY, out)
    source.recycle()
    return out.toByteArray()
  }

  private companion object {
    const val SOURCE_WIDTH = 1024
    const val SOURCE_HEIGHT = 768
    const val SHAPES = 900
    const val QUALITY = 80
    const val SEED = 20_261_102L
  }
}
