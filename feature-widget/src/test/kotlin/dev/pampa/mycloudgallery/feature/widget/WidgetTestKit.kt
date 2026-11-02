package dev.pampa.mycloudgallery.feature.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTitle
import dev.pampa.mycloudgallery.feature.widget.contract.WidgetUiCell
import dev.pampa.mycloudgallery.feature.widget.contract.WidgetUiState
import dev.pampa.mycloudgallery.feature.widget.frame.WidgetFrame
import dev.pampa.mycloudgallery.feature.widget.frame.WidgetFrameCell
import dev.pampa.mycloudgallery.feature.widget.glance.GlanceWidgetComposer
import dev.pampa.mycloudgallery.feature.widget.glance.GalleryWidgetReceiver
import org.robolectric.Shadows.shadowOf

/**
 * Gli arnesi dei test del widget: bitmap **sintetiche** (sfumature e forme, nessuna foto, nessun dato personale), fotogrammi di ogni caso e misura, e la composizione vera con Glance
 * applicata a una vista (i `RemoteViews` come li vedrebbe il launcher).
 */
internal object WidgetTestKit {

  /** Una "foto" disegnata: una sfumatura con un sole e un monte, di tinte diverse per [seed], delle misure [width] x [height]. */
  fun bitmap(seed: Int, width: Int = 240, height: Int = 180): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val hue = (seed * 47 % 360).toFloat()
    val top = Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.95f))
    val bottom = Color.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.55f, 0.65f))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    paint.shader = null
    paint.color = Color.argb(200, 255, 255, 255)
    canvas.drawCircle(width * 0.72f, height * 0.3f, height * 0.12f, paint)
    paint.color = Color.argb(150, 20, 30, 50)
    val mountain = android.graphics.Path().apply {
      moveTo(0f, height.toFloat())
      lineTo(width * 0.35f, height * 0.5f)
      lineTo(width * 0.6f, height * 0.8f)
      lineTo(width * 0.8f, height * 0.6f)
      lineTo(width.toFloat(), height.toFloat())
      close()
    }
    canvas.drawPath(mountain, paint)
    return bitmap
  }

  private fun uiCells(n: Int, from: Int = 0, videoEvery: Int = 3) = List(n) {
    WidgetUiCell(from + it, if ((from + it) % videoEvery == videoEvery - 1) MediaKind.VIDEO else MediaKind.PHOTO, 20251031 - it)
  }

  private fun cellsFor(count: Int, videoEvery: Int = 3) = List(count) {
    WidgetFrameCell(bitmap(it + 1), if ((it) % videoEvery == videoEvery - 1) "Video, ${31 - it} ottobre 2025" else "Foto, ${31 - it} ottobre 2025")
  }

  val slot: WidgetSlot = WidgetSlot(7)

  /** L'id GeoNames del luogo di prova ("Oslo"). */
  const val OSLO: Int = 3_143_244

  /** "Un anno fa · Oslo". */
  val memoryTitle: WidgetTitle = WidgetTitle(yearsAgo = 1, placeIds = listOf(OSLO), tripLabel = null, month = null)

  /** Il ricordo di oggi: la copertina e le altre celle fino alla misura. */
  fun memory(size: WidgetSize, title: WidgetTitle = memoryTitle, text: String = "Un anno fa · Oslo"): WidgetFrame {
    val state = WidgetUiState.Memory(size, uiCells(1).first(), uiCells(size.cells - 1, from = 1), title)
    return WidgetFrame(slot, state, cellsFor(size.cells), text, null)
  }

  fun photos(size: WidgetSize, count: Int = size.cells): WidgetFrame {
    val state = WidgetUiState.Photos(size, uiCells(count))
    return WidgetFrame(slot, state, cellsFor(count), null, null)
  }

  fun locked(size: WidgetSize, count: Int = size.cells): WidgetFrame {
    val state = WidgetUiState.Locked(size, uiCells(count))
    return WidgetFrame(slot, state, cellsFor(count), null, null)
  }

  fun message(size: WidgetSize, reason: WidgetEmpty = WidgetEmpty.NO_MEDIA, text: String = "Apri MyCloud Gallery"): WidgetFrame =
    WidgetFrame(slot, WidgetUiState.Message(size, reason), emptyList(), null, text)

  /** I dp di una misura (larghezza, altezza) come li darebbe una Home su un telefono. */
  fun dp(size: WidgetSize): Pair<Int, Int> = when (size) {
    WidgetSize.SMALL -> 160 to 160
    WidgetSize.WIDE -> 320 to 160
    WidgetSize.LARGE -> 320 to 320
  }

  /** La composizione vera (Glance) di [frame] nella misura [size], applicata a una vista della grandezza giusta e disposta. */
  suspend fun render(context: Context, frame: WidgetFrame, size: WidgetSize, settings: EngineSettings = EngineSettings()): View {
    registerWidget(context, frame.slot)
    val (w, h) = dp(size)
    val views = GlanceWidgetComposer(context).compose(frame, DpSize(w.dp, h.dp), settings)
    return inflate(context, views, w, h)
  }

  /** Il widget [slot] sulla Home di prova: senza, Glance non ne conosce l'id (`getGlanceIdBy`). */
  fun registerWidget(context: Context, slot: WidgetSlot) {
    val manager = AppWidgetManager.getInstance(context)
    if (manager.getAppWidgetInfo(slot.appWidgetId) != null) return
    val info = AppWidgetProviderInfo().apply { provider = ComponentName(context, GalleryWidgetReceiver::class.java) }
    shadowOf(manager).addBoundWidget(slot.appWidgetId, info)
  }

  fun inflate(context: Context, views: RemoteViews, widthDp: Int, heightDp: Int): View {
    val density = context.resources.displayMetrics.density
    val widthPx = (widthDp * density).toInt()
    val heightPx = (heightDp * density).toInt()
    val parent = FrameLayout(context)
    val view = views.apply(context, parent)
    parent.addView(view, FrameLayout.LayoutParams(widthPx, heightPx))
    parent.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
    parent.layout(0, 0, widthPx, heightPx)
    return parent
  }

  /** Tutte le viste dell'albero, in ordine di profondita'. */
  fun View.descendants(): List<View> {
    val found = ArrayList<View>()
    fun walk(v: View) {
      found += v
      if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
    walk(this)
    return found
  }

  /** Tutte le viste di un tipo nell'albero. */
  inline fun <reified T : View> View.all(): List<T> = descendants().filterIsInstance<T>()

  /** Le immagini con una bitmap (le foto e i glifi) di un albero. */
  fun View.images(): List<ImageView> = all<ImageView>()

  fun View.texts(): List<String> = all<TextView>().map { it.text.toString() }.filter { it.isNotEmpty() }

  /** Le viste che si possono toccare. */
  fun View.clickables(): List<View> = descendants().filter { it.hasOnClickListeners() }
}
