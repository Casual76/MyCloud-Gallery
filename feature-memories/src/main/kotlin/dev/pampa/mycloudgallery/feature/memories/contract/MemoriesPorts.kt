package dev.pampa.mycloudgallery.feature.memories.contract

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import dev.pampa.mycloudgallery.core.imaging.contract.PlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.memories.contract.ClipSource
import dev.pampa.mycloudgallery.core.memories.contract.MusicSource
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.StableCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

// LE PORTE DELLE SCHERMATE DEI RICORDI (docs/fase9-progetto.md §1.4, §2.4, D13, D18; riga P9 del §4.4). Aggiunte di P9 ai contratti di feature-memories:
// le miniature e l'anteprima locale, i nomi dei luoghi, il permesso delle notifiche, il lettore audio (focus compreso), il fotogramma di un clip
// e l'ambiente della presentazione. Le lega `:app` (P13); finche' non lo fa le schermate partono lo stesso con i null-object, che **non fanno
// niente di pericoloso** (nessuna miniatura: segnaposto; nessun luogo; nessun audio). Nessuna porta porta un nome o un titolo da salvare. Mai
// niente nei log (D33).

/** La sorgente di una miniatura: per una cella dell'istantanea (come la Libreria). */
interface MemoriesRefs {
  /** La sorgente locale della miniatura per la cella [index], o null se il media non ha una sorgente locale (segnaposto sfocato). */
  fun refAt(snapshot: TimelineSnapshot, index: Int): MediaRef?
}

/** Nessuna sorgente: ogni miniatura e' un segnaposto. */
object NoMemoriesRefs : MemoriesRefs {
  override fun refAt(snapshot: TimelineSnapshot, index: Int): MediaRef? = null
}

/**
 * L'anteprima ad alta qualita' **locale** di un media per la presentazione (D13): quella che il viewer ha gia' decodificato o che si legge dal
 * telefono. Null se non c'e' **adesso**: mai un'attesa sul NAS e mai una richiesta di rete (E-MEM14). `:app` (P13) la lega ai file locali.
 */
fun interface PlayerPreviewSource {
  suspend fun preview(ref: MediaRef, sidePx: Int): ImageBitmap?
}

/** Tutto cio' che serve a disegnare i media di un ricordo: miniature della Libreria, segnaposto e (per la presentazione) l'anteprima locale. */
class MemoriesThumbnails(
  val loader: ThumbnailLoader,
  val placeholders: PlaceholderCache,
  val refs: MemoriesRefs,
  val preview: PlayerPreviewSource? = null,
)

/** I nomi dei luoghi di un ricordo (id GeoNames di `MemoryTitle.placeIds`), gia' scritti: legata da `:app` a `PlaceNames`. Mai la parola "casa". */
fun interface MemoryPlaceNames {
  fun names(placeIds: List<Int>): List<String>
}

/** Nessun nome di luogo: i titoli restano senza luogo. */
object NoMemoryPlaceNames : MemoryPlaceNames {
  override fun names(placeIds: List<Int>): List<String> = emptyList()
}

/**
 * Il permesso e il canale delle notifiche di adesso (D18, E-NT1, E-NT2). [refresh] lo rilegge (si chiama quando la pagina torna in primo piano,
 * dopo le impostazioni di sistema). Legata da `:app` a `PermissionAsks` e al canale `memories` (P10).
 */
interface NotificationAccess {
  val state: StateFlow<NotifyPermission>

  fun refresh()
}

/** Senza legame: "negato ma si puo' chiedere" (la pagina offre "Consenti", che non fa niente finche' `:app` non lega le azioni). */
object UnboundNotificationAccess : NotificationAccess {
  override val state: StateFlow<NotifyPermission> = MutableStateFlow(NotifyPermission.ASKABLE).asStateFlow()

  override fun refresh() = Unit
}

/** Cosa succede all'audio della presentazione fuori dal nostro controllo (Q3, D13). */
enum class MusicEvent(override val code: String) : StableCode {
  /** Un'altra app ha preso l'audio per un momento (una chiamata, un messaggio vocale): la presentazione e la musica si fermano. */
  FOCUS_LOST("focus_lost"),

  /** L'audio e' tornato disponibile: se ci si era fermati per questo, si riparte. */
  FOCUS_GAINED("focus_gained"),

  /** Un'altra app ha preso l'audio per sempre: la musica si ferma, la presentazione resta ferma finche' l'utente non la riprende, muta. */
  FOCUS_LOST_FOR_GOOD("focus_lost_for_good"),

  /** Il file non c'e' piu' (spostato, cancellato, permesso perso): presentazione muta e un avviso. */
  MISSING("missing"),
}

/**
 * Il lettore audio della presentazione (Q3: Media3 a volume moderato, un solo brano, in ripetizione), con il **focus audio**. Legato da `:app`
 * (P13): questo modulo non ha la libreria e decide solo **quando** far partire, fermare e riprendere. [events] sono i fatti che arrivano da fuori.
 */
interface MusicPlayback {
  val events: Flow<MusicEvent>

  /** Chiede il focus e parte. Se il file non si apre, emette [MusicEvent.MISSING]. */
  fun start(source: MusicSource)

  fun pause()

  fun resume()

  /** Ferma e lascia il focus (uscita dalla presentazione, fine, scelta di restare muti). */
  fun stop()
}

/** Nessun audio: la presentazione e' muta (la scelta resta, ma non c'e' chi la suona). */
object NoMusicPlayback : MusicPlayback {
  override val events: Flow<MusicEvent> = emptyFlow()

  override fun start(source: MusicSource) = Unit

  override fun pause() = Unit

  override fun resume() = Unit

  override fun stop() = Unit
}

/**
 * Il clip muto di un video **del telefono** (D13, E-MEM17): disegnato sopra il fotogramma di copertina per i suoi secondi. `:app` lo lega a Media3;
 * senza legame resta il fotogramma con l'icona "video".
 */
fun interface PlayerClipSurface {
  @Composable
  fun Show(clip: ClipSource, modifier: Modifier)
}

/** Nessun clip: si vede il fotogramma di copertina con l'icona. */
object NoPlayerClipSurface : PlayerClipSurface {
  @Composable
  override fun Show(clip: ClipSource, modifier: Modifier) = Unit
}

/**
 * Come sta il dispositivo adesso per la presentazione (E-MEM19): [reducedMotion] le animazioni di sistema sono spente (nessun movimento, solo
 * dissolvenze), [talkBack] un lettore di schermo e' acceso (niente avanzamento automatico).
 */
data class PlayerEnvironment(val reducedMotion: Boolean = false, val talkBack: Boolean = false)

/** Cosa un'azione lascia sulla schermata quando va detta: un codice stabile, il testo e' della schermata. */
enum class MemoriesMessage(override val code: String) : StableCode {
  /** Il ricordo non c'e' piu' (uscito dallo scaffale mentre si decideva). */
  GONE("gone"),

  /** La scelta non si e' potuta salvare adesso. */
  FAILED("failed"),
}
