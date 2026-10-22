package dev.pampa.mycloudgallery.feature.viewer.testing

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.viewer.cutout.CutoutSaveResult
import dev.pampa.mycloudgallery.feature.viewer.cutout.CutoutSaver
import dev.pampa.mycloudgallery.feature.viewer.cutout.ViewerCutout
import dev.pampa.mycloudgallery.feature.viewer.cutout.ViewerMask
import dev.pampa.mycloudgallery.feature.viewer.cutout.ViewerRect
import dev.pampa.mycloudgallery.feature.viewer.cutout.ViewerSubjectCutter
import dev.pampa.mycloudgallery.feature.viewer.livetext.ViewerTextBox
import dev.pampa.mycloudgallery.feature.viewer.livetext.ViewerTextLayout
import dev.pampa.mycloudgallery.feature.viewer.livetext.ViewerTextLine
import dev.pampa.mycloudgallery.feature.viewer.livetext.ViewerTextReader
import dev.pampa.mycloudgallery.feature.viewer.livetext.ViewerTextSource
import dev.pampa.mycloudgallery.feature.viewer.livetext.ViewerWordSpan

// I fake del testo dal vivo e del ritaglio (Fase 6, P0): P12 e P13 partono da qui (testo letto finto con righe dritte, inclinate e
// su piu' righe; ritagliatore finto). Mai nella release (tools/check.ps1). Nessun dato personale: testi di prova generici.

/** Il testo letto finto, per media. */
class FakeViewerTextSource(val layouts: MutableMap<MediaId, ViewerTextLayout> = HashMap()) : ViewerTextSource {
  override suspend fun layout(id: MediaId): ViewerTextLayout? = layouts[id]
}

/**
 * La lettura al volo finta: con [available] falso **fallisce se chiamata** (`AssertionError`, D41, §4.3): il viewer non deve mai
 * chiederla dove la lettura del testo e' spenta.
 */
class FakeViewerTextReader(override var available: Boolean = false, var result: ViewerTextLayout? = null) : ViewerTextReader {
  var reads: Int = 0
    private set

  override suspend fun readNow(id: MediaId, ref: MediaRef): ViewerTextLayout? {
    if (!available) throw AssertionError("Lettura al volo chiesta con la lettura del testo spenta (D41)")
    reads++
    return result
  }
}

/** Le fixture del testo dal vivo (P12): una riga dritta, una inclinata, un testo su piu' righe. Parole ed estremi coerenti. */
object LiveTextFixtures {
  private fun line(text: String, left: Float, top: Float, right: Float, bottom: Float, angle: Int = 0): ViewerTextLine {
    val words = text.split(' ')
    val total = text.length.toFloat()
    var cursor = 0
    val spans = words.map { word ->
      val start = cursor / total
      cursor += word.length
      val end = cursor / total
      cursor += 1
      ViewerWordSpan(start, end)
    }
    return ViewerTextLine(ViewerTextBox(left, top, right, bottom), angle, spans, text)
  }

  val straight = ViewerTextLayout(listOf(line("Via delle Rose 12", 0.10f, 0.20f, 0.60f, 0.26f)))

  val tilted = ViewerTextLayout(listOf(line("Totale euro 23,40", 0.20f, 0.50f, 0.75f, 0.56f, angle = 12)))

  val multiline = ViewerTextLayout(
    listOf(
      line("Prova di lettura", 0.10f, 0.10f, 0.55f, 0.15f),
      line("su piu' righe di testo", 0.10f, 0.17f, 0.70f, 0.22f),
      line("fine", 0.10f, 0.24f, 0.20f, 0.29f),
    ),
  )
}

/**
 * Il ritagliatore finto: un rettangolo intorno al punto, o nessun soggetto se il test lo chiede. Per le prove piu' fini: [result] decide cosa
 * rispondere a ogni punto (un soggetto disegnato, un guasto, nessun soggetto), [latencyMs] fa aspettare (il velo che pulsa), [failWith] fa lanciare
 * un'eccezione come un motore che si rompe, [gate] lo tiene fermo finche' non lo si apre.
 */
class FakeViewerSubjectCutter(
  var noSubject: Boolean = false,
  var latencyMs: Long = 0,
  var failWith: Throwable? = null,
  var result: ((x: Float, y: Float) -> ViewerCutout)? = null,
  /** Se c'e', il ritagliatore aspetta che lo si apra prima di rispondere: il velo che pulsa si cattura finche' il modello "lavora". */
  var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
) : ViewerSubjectCutter {
  val calls: MutableList<Pair<Float, Float>> = mutableListOf()

  /** Le foto su cui si e' ritagliato, nell'ordine delle chiamate. */
  val ids: MutableList<MediaId> = mutableListOf()

  override suspend fun cut(id: MediaId, ref: MediaRef, x: Float, y: Float): ViewerCutout {
    calls += x to y
    ids += id
    if (latencyMs > 0) kotlinx.coroutines.delay(latencyMs)
    gate?.await()
    failWith?.let { throw it }
    result?.let { return it(x, y) }
    if (noSubject) return ViewerCutout.NoSubject
    val w = 64
    val h = 64
    val alpha = ByteArray(w * h) { i -> if (i % w in 16 until 48 && i / w in 16 until 48) 0xff.toByte() else 0 }
    return ViewerCutout.Subject(ViewerMask(w, h, alpha), ViewerRect(0.25f, 0.25f, 0.75f, 0.75f))
  }
}

/**
 * Il salvataggio finto: registra cosa e' stato chiesto (copia, condivisione, nuova foto e se nasce nascosta, con il Contorno acceso o no).
 * [copyFails], [shareFails], [saveFails] simulano un'azione che non riesce; [latencyMs] fa aspettare ("Salvo la foto nuova...").
 */
class FakeCutoutSaver : CutoutSaver {
  val copies: MutableList<MediaId> = mutableListOf()
  val shares: MutableList<MediaId> = mutableListOf()
  val saved: MutableList<Pair<MediaId, Boolean>> = mutableListOf()

  /** Il Contorno com'era a ogni azione, nell'ordine delle chiamate. */
  val outlines: MutableList<Boolean> = mutableListOf()
  var copyFails: Boolean = false
  var shareFails: Boolean = false
  var saveFails: Boolean = false
  var latencyMs: Long = 0

  /** Se c'e', il salvataggio aspetta che lo si apra: lo stato "Salvo la foto nuova..." si cattura finche' il PNG si scrive. */
  var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

  override suspend fun copy(id: MediaId, ref: MediaRef, cutout: ViewerCutout.Subject, outline: Boolean): Boolean {
    if (latencyMs > 0) kotlinx.coroutines.delay(latencyMs)
    outlines += outline
    if (copyFails) return false
    copies += id
    return true
  }

  override suspend fun share(id: MediaId, ref: MediaRef, cutout: ViewerCutout.Subject, outline: Boolean): Boolean {
    if (latencyMs > 0) kotlinx.coroutines.delay(latencyMs)
    outlines += outline
    if (shareFails) return false
    shares += id
    return true
  }

  override suspend fun saveAsNewPhoto(id: MediaId, ref: MediaRef, cutout: ViewerCutout.Subject, outline: Boolean, fromHidden: Boolean): CutoutSaveResult {
    if (latencyMs > 0) kotlinx.coroutines.delay(latencyMs)
    gate?.await()
    outlines += outline
    if (saveFails) return CutoutSaveResult.Failed
    saved += id to fromHidden
    return CutoutSaveResult.Saved(newMedia = null)
  }
}
