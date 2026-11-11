package dev.pampa.mycloudgallery.feature.backup.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.backup.contract.BackupCoverPorts
import kotlinx.coroutines.delay

// LE COPERTINE DELLE SORGENTI (I3, fase I, 11/11). L'utente: «la galleria Samsung mostra le anteprime, la nostra solo il testo»; la pagina Backup e
// «Cosa salvare» erano righe con la stessa icona grigia di cartella. Ora ogni cartella mostra **la sua foto piu' recente**.
//
// **Stesse porte, stessi cancelli** (`brief-I-regole.md`): le ultime foto di ogni cartella le dice il catalogo (`SourceNewestMedia`, una lettura di
// `:app`), ma si disegna solo una foto che e' **nella Libreria visibile di adesso** (che non contiene mai un nascosto ne' un eliminato), con la
// stessa chiave e la stessa cache delle miniature della griglia (versione e taglia M, come le copertine delle Raccolte). Una foto nascosta un attimo
// fa non resta: [BackupSourceCover] riverifica la vista a ogni disegno.
//
// **Costo.** La lettura del catalogo si fa **una volta** per elenco di sorgenti, fuori dal thread principale. Il filtro con la vista (una ricerca
// per id, poche foto per cartella) al piu' una volta al secondo mentre l'indice cambia (il backup cambia i segni delle celle), sempre sull'ultima
// vista. Le righe leggono e basta; nessuna chiamata di sistema per riga o per fotogramma.

/** Le copertine delle sorgenti: le mette la pagina (o le impostazioni) del backup; null = solo le piastrelle. */
val LocalBackupCovers = staticCompositionLocalOf<BackupCovers?> { null }

/** Le foto delle sorgenti del backup, per le copertine. Una per ViewModel. */
@Stable
class BackupCovers(private val ports: BackupCoverPorts) {

  /** Per ogni sorgente (per id), le foto mostrabili adesso, dalla piu' recente. */
  private val shown = mutableStateMapOf<String, List<MediaId>>()

  /** La Libreria visibile. */
  val view get() = ports.library.view

  internal val loader get() = ports.loader

  /** La foto di copertina di [source] (la piu' recente mostrabile), o null. */
  fun cover(source: BackupSource): MediaId? = shown[source.id]?.firstOrNull()

  /** La richiesta della miniatura di [media] in [view], o null se il media non e' nella Libreria visibile (un nascosto, un eliminato). */
  fun request(view: TimelineView, media: MediaId): ThumbRequest? {
    val viewIndex = view.viewIndexOf(media)
    if (viewIndex < 0) return null
    val ref = ports.library.refOrNull(media) ?: return null
    val index = view.snapshotIndex(viewIndex)
    val version = (view.snapshot as? ColumnarTimelineSnapshot)?.versionKeyAt(index) ?: DefaultVersionKey
    return ThumbRequest(ThumbKey(media, version, ThumbBucket.M), ref)
  }

  /**
   * Prepara le copertine di [sources]: le ultime foto dal catalogo (una lettura), poi, per ogni vista della Libreria (al piu' una al secondo), le
   * sole mostrabili; chiede le miniature delle prime [requestLimit] sorgenti una volta per vista. Gira finche' la pagina e' composta.
   */
  suspend fun refresh(sources: List<BackupSource>, requestLimit: Int = RequestLimit) {
    if (sources.isEmpty()) {
      shown.clear()
      return
    }
    val found = ports.newest.newest(sources, AskPerSource)
    // Una StateFlow e' gia' «conflata»: chi raccoglie lento vede sempre l'ultima vista.
    view.collect { current ->
      val visible = found.mapValues { (_, ids) -> ids.filter { current.viewIndexOf(it) >= 0 }.take(ShowPerSource) }.filterValues { it.isNotEmpty() }
      shown.keys.retainAll(visible.keys)
      shown.putAll(visible)
      val requests = sources.take(requestLimit).mapNotNull { source -> visible[source.id]?.firstOrNull()?.let { request(current, it) } }
      if (requests.isNotEmpty()) ports.loader.window(requests, emptyList())
      delay(PassPeriodMs)
    }
  }

  companion object {
    /** Dal catalogo se ne chiedono di piu' di quelle che si mostrano: alcune possono essere nascoste. */
    const val AskPerSource: Int = 4

    /** Quante foto per cartella si tengono (la copertina e' la prima). */
    const val ShowPerSource: Int = 1

    /** Le miniature si chiedono per le prime sorgenti (le impostazioni ne elencano fino a 200: le altre arrivano se sono gia' in cache). */
    const val RequestLimit: Int = 24

    /** Al piu' un passaggio al secondo mentre l'indice cambia. */
    const val PassPeriodMs: Long = 1_000L

    /** La versione di una miniatura quando la vista non e' quella a colonne (la stessa regola della griglia). */
    private const val DefaultVersionKey: Long = 1L
  }
}

/** Prepara le copertine di [sources] una volta per elenco (e di nuovo solo se l'elenco cambia), finche' la pagina e' composta. */
@Composable
internal fun BackupCoverWindow(covers: BackupCovers?, sources: List<BackupSource>) {
  if (covers == null) return
  val ids = remember(sources) { sources.map { it.id } }
  LaunchedEffect(covers, ids) { covers.refresh(sources) }
}

/**
 * La foto di copertina di [source] dentro la sua piastrella, o niente (resta la piastrella della famiglia sotto). Si disegna solo se la foto e' nella
 * Libreria visibile adesso; compare quando la miniatura c'e', senza animazione e senza spostare niente.
 */
@Composable
internal fun BoxScope.BackupSourceCover(source: BackupSource) {
  val covers = LocalBackupCovers.current ?: return
  val media = covers.cover(source) ?: return
  val view by covers.view.collectAsState()
  val request = remember(view, media) { covers.request(view, media) } ?: return
  val image = covers.loader.image(request.key, request.ref).value ?: return
  Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
}

/** Il glifo di una sorgente quando non ha (ancora) una foto: la fotocamera, lo screenshot, la cartella. */
internal fun sourceGlyph(source: BackupSource): ImageVector = when (source) {
  BackupSource.Camera -> Icons.Rounded.PhotoCamera
  BackupSource.Screenshots -> Icons.Rounded.Screenshot
  is BackupSource.Folder -> Icons.Rounded.Folder
}
