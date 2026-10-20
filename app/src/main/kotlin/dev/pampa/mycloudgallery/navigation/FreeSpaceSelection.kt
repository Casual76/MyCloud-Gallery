package dev.pampa.mycloudgallery.navigation

import androidx.compose.runtime.saveable.Saver
import dev.pampa.mycloudgallery.backup.FreeSpaceRequest
import dev.pampa.mycloudgallery.core.model.MediaId

/**
 * Correzioni 5B (LS-6 della verifica funzionale 5B): **la selezione di "Libera spazio" non si allarga mai**. Prima la rotta leggeva la selezione
 * da una variabile del processo (`FiveBUi`): dopo la morte del processo la navigazione ripristinava la pagina e la variabile era vuota, quindi
 * "Elimina solo dal telefono" di una foto diventava "tutto il sicuro tranne gli ultimi 30 giorni" (migliaia di media).
 *
 * Adesso la rotta tiene nel suo stato salvato (`rememberSaveable`, il `Bundle` dell'activity) **cosa** ha aperto: "tutto il sicuro", oppure gli
 * id scelti. Una scelta troppo grande per il `Bundle` (oltre [MAX_SAVED] media) si ricorda solo per numero di richiesta: dopo una ricreazione
 * dell'activity la ritrova il processo vivo; dopo la morte del processo e' **persa**, e la pagina si chiude invece di proporre di piu'. Il giro
 * gia' confermato non dipende da questo: i suoi elementi sono nel diario.
 */
internal class FreeSpaceSelection private constructor(
  private val kind: Kind,
  /** Il numero della richiesta che ha aperto la rotta (0 = nessuna: aperta da altrove). */
  val serial: Long,
  private val ids: Set<MediaId>?,
) {
  private enum class Kind { ALL, CHOSEN, LOST }

  /** Cosa mostra la pagina. */
  sealed interface Resolved {
    /** [selection] null = tutto il sicuro tranne gli ultimi giorni; altrimenti al massimo questi media. */
    data class Page(val selection: Set<MediaId>?) : Resolved

    /** La scelta dell'utente non c'e' piu' (morte del processo con una scelta grande): la pagina si chiude, mai "tutto il sicuro". */
    data object Close : Resolved
  }

  /** Che cosa mostrare, con [lookup] per una richiesta ricordata solo per numero (il processo vivo la sa). */
  fun resolve(lookup: (Long) -> FreeSpaceRequest?): Resolved = when (kind) {
    Kind.ALL -> Resolved.Page(null)
    Kind.CHOSEN -> Resolved.Page(ids)
    Kind.LOST -> lookup(serial)?.selection?.let { Resolved.Page(it) } ?: Resolved.Close
  }

  override fun toString(): String = "FreeSpaceSelection(${kind.name}, #$serial)"

  companion object {
    /** Oltre questi media la scelta non va nel `Bundle` (circa 40 kB di id: il `Bundle` di un'activity ha un tetto). */
    const val MAX_SAVED: Int = 500

    /** La rotta si apre con [request] (null = da altrove: tutto il sicuro). */
    fun of(request: FreeSpaceRequest?): FreeSpaceSelection {
      val selection = request?.selection ?: return FreeSpaceSelection(Kind.ALL, request?.serial ?: 0L, null)
      return FreeSpaceSelection(Kind.CHOSEN, request.serial, selection)
    }

    private const val ALL = "all"
    private const val CHOSEN = "ids"
    private const val LOST = "lost"

    /**
     * Lo stato salvato: il tipo, il numero della richiesta e, se sono pochi, gli id. Una scelta grande si salva come "persa" con il suo numero:
     * si ritrova solo nel processo che l'ha fatta.
     */
    val Saver: Saver<FreeSpaceSelection, ArrayList<String>> = Saver(
      save = { state ->
        val head = arrayListOf(
          when {
            state.kind == Kind.ALL -> ALL
            state.kind == Kind.CHOSEN && state.ids!!.size <= MAX_SAVED -> CHOSEN
            else -> LOST
          },
          state.serial.toString(),
        )
        if (head[0] == CHOSEN) head.addAll(state.ids!!.map { it.value })
        head
      },
      restore = { saved -> restore(saved) },
    )

    /** Uno stato salvato illeggibile vale "perso": la pagina si chiude (mai piu' media di quelli scelti). */
    internal fun restore(saved: List<String>): FreeSpaceSelection {
      val serial = saved.getOrNull(1)?.toLongOrNull() ?: 0L
      return try {
        when (saved.firstOrNull()) {
          ALL -> FreeSpaceSelection(Kind.ALL, serial, null)
          CHOSEN -> FreeSpaceSelection(Kind.CHOSEN, serial, saved.drop(2).mapTo(LinkedHashSet()) { MediaId(it) })
          else -> FreeSpaceSelection(Kind.LOST, serial, null)
        }
      } catch (_: IllegalArgumentException) {
        FreeSpaceSelection(Kind.LOST, serial, null)
      }
    }
  }
}
