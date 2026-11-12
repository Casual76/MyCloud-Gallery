package dev.pampa.mycloudgallery.feature.search

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId

// I VOLTI DELLE ANTEPRIME DELLA RICERCA (CG-1, giro 1 della fase I). Lo stesso contratto dei volti delle Raccolte (CU-19 e CG-5 della fase H,
// `CollectionCovers`), non uno simile:
//
//  - i ritagli stanno **in un posto solo**, qui. Gli esempi, la terna gia' pronta per la prossima apertura, le recenti e i completamenti tengono solo
//    **chi** e' la persona ([SuggestionArt.Face]), mai il bitmap: buttato qui, il volto non c'e' piu' da nessuna parte;
//  - ogni ritaglio porta il **segno delle revoche** con cui il cancello l'ha dato e le sue **foto d'origine**. A ogni segno nuovo (cancellazione locale,
//    Persone spenta o rimontata, cache dei ritagli svuotata: `CollectionFaceCrops.revocations`, lo stesso segno delle Raccolte) si butta **tutto**
//    ([revoke]); la barra aperta legge questo stato quando compone i tondi e al fotogramma dopo il segno non disegna piu' i volti, senza aspettare niente;
//  - un ritaglio chiesto con un segno vecchio e arrivato dopo una revoca **non entra** ([admit]);
//  - un volto si disegna solo se almeno una delle sue foto d'origine e' nell'istantanea della Libreria di adesso ([image]): una foto nascosta un attimo
//    fa toglie il suo volto gia' al primo disegno con l'istantanea nuova.

/**
 * I volti delle anteprime: li scrive [SearchExamples] (dal contesto del controller, il thread principale), la barra li legge e basta. Stato di Compose:
 * chi legge [image] si ridisegna quando un volto arriva o se ne va.
 */
@Stable
class SuggestionFaces {

  /** Un ritaglio, le sue foto d'origine e il segno delle revoche con cui il cancello l'ha permesso. */
  private class Held(val image: ImageBitmap, val origins: Set<MediaId>, val token: Any?)

  private val held = mutableStateMapOf<PersonId, Held>()

  /** L'ultimo segno delle revoche: un volto permesso con un segno diverso non si disegna. */
  private var token by mutableStateOf<Any?>(Unseen)

  /**
   * Il volto di [person] da disegnare adesso, o null: non ancora dato, rifiutato dal cancello, revocato, o con **nessuna foto d'origine** in [library]
   * (l'istantanea della Libreria di adesso, che non ha i nascosti).
   */
  fun image(person: PersonId, library: TimelineSnapshot?): ImageBitmap? {
    val face = held[person] ?: return null
    if (face.token != token) return null
    if (library == null || face.origins.none { library.indexOf(it) >= 0 }) return null
    return face.image
  }

  /** Il segno di adesso: chi chiede un volto al cancello lo prende **prima** di chiederlo e lo ripassa ad [admit]. */
  internal val currentToken: Any? get() = token

  /** Un segno nuovo delle revoche: via tutti i volti, subito. */
  internal fun revoke(next: Any?) {
    token = next
    held.clear()
  }

  /**
   * Il cancello ha dato il volto di [person] alla domanda fatta con il segno [asked]: si tiene solo se il segno e' ancora quello (una risposta in volo da
   * prima di una revoca non rientra) e se ha almeno una foto d'origine. Vero se tenuto.
   */
  internal fun admit(person: PersonId, image: ImageBitmap, origins: Set<MediaId>, asked: Any?): Boolean {
    if (asked != token || origins.isEmpty()) return false
    held[person] = Held(image, origins, asked)
    return true
  }

  /** Il cancello ha rifiutato [person] alla domanda fatta con il segno [asked]: il volto se ne va (se nel frattempo non e' cambiato tutto). */
  internal fun refuse(person: PersonId, asked: Any?) {
    if (asked == token) held.remove(person)
  }

  /** Quanti volti sono tenuti con il segno di adesso (per le prove: dopo una revoca, zero). */
  internal val count: Int get() = held.values.count { it.token == token }

  /** Nessun segno delle revoche ancora visto. */
  private object Unseen

  companion object {
    /** Volti fissi (prove e screenshot): persona -> ritaglio e foto d'origine. */
    fun fixed(faces: Map<PersonId, Pair<ImageBitmap, Set<MediaId>>>): SuggestionFaces = SuggestionFaces().apply {
      revoke(Unit)
      faces.forEach { (person, face) -> admit(person, face.first, face.second, Unit) }
    }
  }
}
