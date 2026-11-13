package dev.pampa.mycloudgallery.feature.viewer.menu

import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ImageSearch
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.R
import kotlinx.coroutines.flow.Flow

// "Analizza adesso" del viewer (Pacchetto X, 12/11/2026): la foto o il video aperti passano davanti nella coda dell'analisi. Il viewer
// non vede core-ai ne' core-search (Phase6ArchitectureTest): chiede a questa porta lo stato del media e, al tocco, la precedenza; chi
// analizza, con quali regole e con quale esito lo decide `:app` (stesso percorso del calcolo, nessuna scorciatoia).

/** Com'e' messo un media rispetto all'analisi per la ricerca, per il segno nelle informazioni e la voce del menu. */
enum class MediaAnalysisKind {
  /** Gia' analizzato: pronto per la ricerca. Un segno, nessun tasto inutile. */
  READY,

  /** In coda: si puo' farlo passare davanti ("Analizza adesso"). */
  QUEUED,

  /** Chiesto adesso dall'utente: e' il primo in coda (o lo sara' appena le regole lo lasciano partire). */
  FIRST,

  /** In analisi in questo momento. */
  ANALYZING,

  /** Questo formato non si puo' analizzare. */
  UNSUPPORTED,

  /** Manca il modello di analisi su questo dispositivo: si scarica da "Ricerca e modelli". Nessun tasto. */
  MODEL_MISSING,

  /** Questo dispositivo non analizza: le foto le analizza un altro dispositivo. Nessun tasto che non puo' funzionare. */
  NOT_HERE,

  /** Non si sa ancora (il catalogo non ha risposto): nessuna voce, nessuna capsula. */
  UNKNOWN,
}

/** Perche' un media chiesto adesso non parte subito: il motivo vero delle regole, in una riga. */
enum class MediaAnalysisHold {
  /** Il telefono e' troppo caldo: parte appena si raffredda. */
  HOT,

  /** Il media sta solo sul NAS e il NAS non risponde. */
  NAS,

  /** Le anteprime non sono ancora pronte. */
  PREPARATION,

  /** Un altro motivo delle regole (la Diagnostica lo dice per esteso). */
  RULES,

  /**
   * XS-3 (Pacchetto XF): sui dati mobili, in roaming o con il tetto superato la richiesta **non apre nessun foglio**: il media resta primo in
   * coda e parte sul Wi-Fi.
   */
  WIFI,
}

/**
 * Lo stato di un media: il suo caso e, se e' stato chiesto ma non parte, perche'. [version] (XS2-5, additivo): un'etichetta opaca della **versione**
 * del media per cui lo stato e' stato letto (null = non si sa); il visore la usa per non mescolare stati e richieste di versioni diverse dello stesso media.
 */
data class MediaAnalysis(
  val kind: MediaAnalysisKind,
  val hold: MediaAnalysisHold? = null,
  val version: Long? = null,
  /**
   * XS3-3 (additivo): l'ultima richiesta "Analizza adesso" di **questo media** non e' stata accodata (il catalogo non ha il media, o il lavoro non si e' potuto accodare).
   * Vero finche' non si chiede di nuovo o il media non e' pronto. Chi disegna puo' dirlo (es. "Non sono riuscito a metterla in coda"); la richiesta, se e' arrivata al
   * registro, resta e il lavoro di fondo la servira' comunque. Mai un crash.
   */
  val askFailed: Boolean = false,
) {
  companion object {
    val UNKNOWN = MediaAnalysis(MediaAnalysisKind.UNKNOWN)
  }
}

/**
 * L'analisi del media aperto. `:app` la lega al calcolo e all'indice locale: [observe] segue il media mentre il pannello e' aperto (da
 * "in coda" a "primo" a "pronto"), [analyzeNow] lo mette davanti nella coda e fa partire subito il calcolo **per quel media**: come
 * richiesta dell'utente **non confermata** (se le regole chiedono una conferma per i dati mobili, il roaming o il tetto, nessun foglio: il
 * media resta primo in coda e parte sul Wi-Fi) e solo per i media chiesti (XS-3, Pacchetto XF). Lo stato e' quello della **chiave corrente**
 * del media, e il visore lo lega al media per cui e' nato (`observeBound`).
 */
interface ViewerAnalysis {
  fun observe(id: MediaId): Flow<MediaAnalysis>

  fun analyzeNow(id: MediaId)
}

/** La voce "Analizza adesso" del menu: c'e' solo quando il media e' in coda (gia' pronto o gia' chiesto: nessun tasto inutile). */
internal fun analysisMenuEntries(resources: Resources, media: MediaAnalysis, onAnalyze: () -> Unit): List<GalleryMenuEntry> =
  if (media.kind == MediaAnalysisKind.QUEUED) {
    listOf(GalleryMenuEntry.Item(resources.getString(R.string.analysis_now), onAnalyze, Icons.Rounded.ImageSearch))
  } else {
    emptyList()
  }
