package dev.pampa.mycloudgallery.feature.nas.contract

import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop

// LE ANTEPRIME DI CARTELLE NAS (I4, fase I: «l'anteprima vince sull'icona»). Una pagina di cartella chiede a `:app`, **una volta per elenco** e fuori dal
// thread principale, che cosa la Libreria conosce dei suoi file e delle sue sottocartelle ([NasAreas.previews]); i pixel arrivano dalla stessa cache e dalla
// stessa porta delle miniature della Libreria ([NasThumbnails]). Cio' che la Libreria non mostra (i Nascosti, le aree che l'utente non vede, i file non
// indicizzati, i PDF) non ha anteprima e resta con la piastrella per tipo.

/**
 * Che cosa la Libreria conosce di una pagina di Cartelle NAS.
 *
 * @property files per ogni file della pagina che e' una foto o un video conosciuto (e non nascosto), il suo media.
 * @property folders per ogni sottocartella, i media piu' recenti che stanno sotto di lei (al piu' [FOLDER_COVER_COUNT], dal piu' recente): la copertina.
 */
@Immutable
data class NasFolderPreviews(
  val files: Map<SharePath, MediaId> = emptyMap(),
  val folders: Map<SharePath, List<MediaId>> = emptyMap(),
) {
  override fun toString(): String = "NasFolderPreviews(file=${files.size}, cartelle=${folders.size})"

  companion object {
    /** Nessuna anteprima: ogni riga ha la sua piastrella. */
    val None: NasFolderPreviews = NasFolderPreviews()

    /** Quante foto al piu' fanno la copertina di una cartella (un mosaico di quattro). */
    const val FOLDER_COVER_COUNT: Int = 4
  }
}

/**
 * Le miniature delle anteprime: la cache della Libreria ([loader], la stessa delle griglie) e la richiesta di un media ([request]), che `:app` costruisce
 * dall'istantanea **visibile** della Libreria **di adesso**: un media nascosto, o di un'area che l'utente non vede, non ha richiesta (null) e la riga resta
 * con la piastrella.
 *
 * CG-2 (fase I): [visible] e' la Libreria visibile che cambia (in `:app` l'istantanea stessa). Le righe la osservano e rifanno le richieste a ogni valore
 * nuovo, cosi' una foto nascosta (o di un'area appena esclusa) smette di disegnarsi **subito**, anche se la pagina era gia' composta o stava sotto il
 * viewer; e la pagina rilegge le copertine dal catalogo con una dose ([NasPreviewDose]).
 */
class NasThumbnails(
  val loader: ThumbnailLoader?,
  val visible: StateFlow<Any?> = Unchanging,
  val request: (MediaId) -> ThumbRequest?,
) {
  companion object {
    /** Nessuna miniatura (i test, un dispositivo senza galleria): solo piastrelle. */
    val None: NasThumbnails = NasThumbnails(loader = null, request = { null })
  }
}

/** Una Libreria visibile che non cambia mai (i test e le anteprime senza Libreria). */
private val Unchanging: StateFlow<Any?> = MutableStateFlow(Unit)

/**
 * La dose delle riletture delle anteprime quando la Libreria visibile cambia (CG-2): la vista cambia spesso (a ogni lotto di un backup, a ogni foto nuova),
 * la lettura del catalogo no. Il primo cambio passa **subito**; dopo, al piu' uno ogni [PERIOD_MS], e sempre **l'ultimo** (quelli in mezzo si saltano),
 * come `PassDose` delle Raccolte. Il disegno non aspetta la dose: il cancello delle righe e' immediato.
 */
object NasPreviewDose {
  /** Al piu' una rilettura al secondo. */
  const val PERIOD_MS: Long = 1_000L

  /** I cambi di [flow] dopo il primo valore, con la dose: [onChange] gira subito per il primo, poi al piu' una volta ogni [PERIOD_MS] con l'ultimo. */
  @OptIn(ExperimentalCoroutinesApi::class)
  suspend fun <T> collect(flow: Flow<T>, onChange: suspend (T) -> Unit) {
    flow.drop(1).conflate().collect { value ->
      onChange(value)
      delay(PERIOD_MS)
    }
  }
}
