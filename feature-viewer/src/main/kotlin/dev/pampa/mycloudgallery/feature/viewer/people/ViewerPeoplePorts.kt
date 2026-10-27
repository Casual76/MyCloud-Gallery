package dev.pampa.mycloudgallery.feature.viewer.people

import android.graphics.Bitmap
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlinx.coroutines.flow.Flow

// LE PERSONE NEL VIEWER (docs/fase8-progetto.md §1.2 "feature-viewer", §1.5 `ViewerFaceChip`, §2.6 "Info del viewer", D24, D25, D28).
// Porta e dati di P0; la riga "Persone" nelle info (`InfoBuilder`, solo P9), il foglio "Chi e'?" e le azioni sono di P9, l'aspetto di G3.
// Il viewer **non vede core-faces** (ne' core-ai, core-search): la porta la definisce lui e la lega :app sopra `People` (P13). Senza porta
// ([ViewerServices.people] null) la riga non c'e'. Niente riquadri disegnati sulla foto in questa fase. Mai nomi o id nei log (D37).

/**
 * Un volto del media aperto, come riferimento **opaco** scelto da chi lega la porta (il localizzatore del volto, D23): il viewer lo passa
 * indietro e basta, non lo mostra e non lo interpreta. Mai nei log.
 */
@JvmInline
value class ViewerFaceRef(val token: String) {
  init {
    require(token.isNotEmpty() && token.length <= MAX_CHARS) { "Riferimento di un volto non valido." }
  }

  override fun toString(): String = "ViewerFaceRef"

  companion object {
    const val MAX_CHARS: Int = 128
  }
}

/**
 * Un chip della riga "Persone" (§1.5): una persona con nome, un volto senza nome (in un gruppo senza nome, [person] non null, o non
 * ancora assegnato), o "N volti non riconoscibili" (volti NONE: nessun vettore, nessuna azione). Una persona **nascosta** non compare mai
 * (D28): chi lega la porta la toglie. La copertina si ritaglia al momento ([ViewerPeople.crop]), mai salvata (D25).
 */
sealed interface ViewerFaceChip {
  data class Named(val face: ViewerFaceRef, val person: PersonId, val name: String) : ViewerFaceChip {
    /** Mai il nome (D37). */
    override fun toString(): String = "ViewerFaceChip.Named"
  }

  data class Unnamed(val face: ViewerFaceRef, val person: PersonId?) : ViewerFaceChip {
    override fun toString(): String = "ViewerFaceChip.Unnamed(${person != null})"
  }

  data class Unrecognizable(val count: Int) : ViewerFaceChip {
    init {
      require(count >= 1) { "Almeno un volto." }
    }
  }
}

/** Le azioni su un volto dalle info (§2.6): "E' …" (persona esistente o nuova con un nome), "Non e' …", "Ignora questo volto". */
sealed interface ViewerFaceAction {
  data class Is(val person: PersonId) : ViewerFaceAction {
    override fun toString(): String = "ViewerFaceAction.Is"
  }

  data class IsNew(val name: String) : ViewerFaceAction {
    override fun toString(): String = "ViewerFaceAction.IsNew"
  }

  data class IsNot(val person: PersonId) : ViewerFaceAction {
    override fun toString(): String = "ViewerFaceAction.IsNot"
  }

  data object Ignore : ViewerFaceAction
}

/** L'esito di un'azione: fatto (vale subito), nome non ammesso (1-64 caratteri, `TextRules`), volto o persona spariti, Persone non attiva qui. */
enum class ViewerFaceOutcome { DONE, INVALID_NAME, NOT_FOUND, UNAVAILABLE }

/** Una persona proposta nel foglio "Chi e'?": solo persone con un nome, non nascoste (D28). */
data class ViewerPersonChoice(val person: PersonId, val name: String) {
  override fun toString(): String = "ViewerPersonChoice"
}

/**
 * Le persone del media aperto (D25), legate da :app sopra `People` di core-faces (P9, P13). [faces] emette i chip del media (vuoto se non
 * ci sono volti o Persone non e' attiva qui); [crop] ritaglia al momento la copertina di un volto dalla migliore immagine gia' sul
 * dispositivo, con cache **solo in memoria** (D25); [choices] sono le persone del foglio "Chi e'?"; [act] applica un'azione (vale subito,
 * viaggia con la pubblicazione dei volti, E-F5); [openPerson] apre la pagina della persona (la navigazione la fa :app). Dai Nascosti la riga
 * c'e' (il media e' aperto), ma non porta mai a una pagina che mostra altri nascosti (§2.9, F99).
 */
interface ViewerPeople {
  fun faces(media: MediaId): Flow<List<ViewerFaceChip>>

  suspend fun crop(media: MediaId, face: ViewerFaceRef, sizePx: Int): Bitmap?

  suspend fun choices(media: MediaId, face: ViewerFaceRef): List<ViewerPersonChoice>

  suspend fun act(media: MediaId, face: ViewerFaceRef, action: ViewerFaceAction): ViewerFaceOutcome

  fun openPerson(person: PersonId)
}
