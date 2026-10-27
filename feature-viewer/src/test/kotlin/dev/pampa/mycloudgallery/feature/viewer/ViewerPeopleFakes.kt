package dev.pampa.mycloudgallery.feature.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceAction
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceChip
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceOutcome
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceRef
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerPeople
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerPersonChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Un `ViewerPeople` finto per le prove della riga "Persone" (Fase 8, P9): ricorda cosa gli e' stato chiesto (tocchi, azioni, ritagli). **Nessun volto vero**: i
 * ritagli sono sagome astratte su un fondo a colori, i nomi inventati.
 */
internal class FakeViewerPeople(initial: List<ViewerFaceChip>) : ViewerPeople {
  val chips = MutableStateFlow(initial)
  val opened = mutableListOf<PersonId>()
  val acts = mutableListOf<Pair<ViewerFaceRef, ViewerFaceAction>>()
  val crops = mutableListOf<ViewerFaceRef>()
  var outcome: ViewerFaceOutcome = ViewerFaceOutcome.DONE
  var choices: List<ViewerPersonChoice> = emptyList()

  override fun faces(media: MediaId): Flow<List<ViewerFaceChip>> = chips.map { it }

  override suspend fun crop(media: MediaId, face: ViewerFaceRef, sizePx: Int): Bitmap {
    crops += face
    return silhouette(face.token.hashCode(), sizePx)
  }

  override suspend fun choices(media: MediaId, face: ViewerFaceRef): List<ViewerPersonChoice> = choices

  override suspend fun act(media: MediaId, face: ViewerFaceRef, action: ViewerFaceAction): ViewerFaceOutcome {
    acts += face to action
    return outcome
  }

  override fun openPerson(person: PersonId) {
    opened += person
  }

  companion object {
    /** Una sagoma astratta (testa e spalle) su un fondo a colori: mai un volto vero. */
    fun silhouette(seed: Int, px: Int): Bitmap {
      val hue = (((seed % 360) + 360) % 360).toFloat()
      val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap)
      val paint = Paint(Paint.ANTI_ALIAS_FLAG)
      paint.color = Color.HSVToColor(floatArrayOf(hue, 0.35f, 0.9f))
      canvas.drawRect(0f, 0f, px.toFloat(), px.toFloat(), paint)
      paint.color = Color.argb(200, 255, 255, 255)
      canvas.drawCircle(px * 0.5f, px * 0.40f, px * 0.17f, paint)
      canvas.drawOval(RectF(px * 0.18f, px * 0.62f, px * 0.82f, px * 1.15f), paint)
      return bitmap
    }
  }
}
