package dev.pampa.mycloudgallery.feature.backup.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
//
// **Quali miniature si chiedono** (giro 1 della fase I, CG-3). Prima: sempre le prime 24 sorgenti, anche fuori schermo, e mai la 25esima (le
// impostazioni ne elencano fino a 200: restava il glifo anche portandola sullo schermo). Ora ogni copertina dice quando e' sullo schermo
// ([BackupSourceCover], dalla sua posizione nella finestra) e si chiedono quelle, piu' poche accanto ([coverWindow]): come la griglia, una
// finestra che segue lo scorrimento. La finestra si manda al caricatore solo quando cambia.

/** Le copertine delle sorgenti: le mette la pagina (o le impostazioni) del backup; null = solo le piastrelle. */
val LocalBackupCovers = staticCompositionLocalOf<BackupCovers?> { null }

/** Le foto delle sorgenti del backup, per le copertine. Una per ViewModel. */
@Stable
class BackupCovers(private val ports: BackupCoverPorts) {

  /** Per ogni sorgente (per id), le foto mostrabili adesso, dalla piu' recente. */
  private val shown = mutableStateMapOf<String, List<MediaId>>()

  /** CG-3: le sorgenti con la copertina sullo schermo adesso (per id: quante copertine ciascuna, la stessa puo' stare in due posti). */
  private val onScreen = MutableStateFlow<Map<String, Int>>(emptyMap())

  /** La copertina di [source] e' entrata nello schermo. */
  internal fun enter(source: BackupSource) {
    onScreen.update { it + (source.id to (it[source.id] ?: 0) + 1) }
  }

  /** La copertina di [source] e' uscita dallo schermo (o dalla composizione). */
  internal fun leave(source: BackupSource) {
    onScreen.update { map ->
      val left = (map[source.id] ?: 0) - 1
      if (left <= 0) map - source.id else map + (source.id to left)
    }
  }

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
   * sole mostrabili; e chiede le miniature delle copertine sullo schermo e di [lookahead] sorgenti accanto (CG-3), di nuovo solo quando la finestra
   * cambia. Gira finche' la pagina e' composta.
   */
  suspend fun refresh(sources: List<BackupSource>, lookahead: Int = Lookahead): Unit = coroutineScope {
    if (sources.isEmpty()) {
      shown.clear()
      return@coroutineScope
    }
    val found = ports.newest.newest(sources, AskPerSource)
    val visible = MutableStateFlow<Map<String, List<MediaId>>?>(null)
    launch {
      // Una StateFlow e' gia' «conflata»: chi raccoglie lento vede sempre l'ultima vista.
      view.collect { current ->
        val now = found.mapValues { (_, ids) -> ids.filter { current.viewIndexOf(it) >= 0 }.take(ShowPerSource) }.filterValues { it.isNotEmpty() }
        shown.keys.retainAll(now.keys)
        // Si scrive solo cio' che cambia: le copertine non si ricompongono a ogni passaggio.
        now.forEach { (id, ids) -> if (shown[id] != ids) shown[id] = ids }
        visible.value = now
        delay(PassPeriodMs)
      }
    }
    var sent: List<ThumbKey>? = null
    combine(visible.filterNotNull(), onScreen) { now, screen -> now to screen.keys }.conflate().collect { (now, screen) ->
      val window = coverWindow(sources, screen, lookahead)
      val current = view.value
      fun requestsOf(list: List<BackupSource>) = list.mapNotNull { source -> now[source.id]?.firstOrNull()?.let { request(current, it) } }
      val onScreenNow = requestsOf(window.visible)
      val ahead = requestsOf(window.ahead)
      val keys = (onScreenNow + ahead).map { it.key }
      if (keys.isNotEmpty() && keys != sent) {
        ports.loader.window(onScreenNow, ahead)
        sent = keys
      }
      delay(WindowStepMs)
    }
  }

  companion object {
    /** Dal catalogo se ne chiedono di piu' di quelle che si mostrano: alcune possono essere nascoste. */
    const val AskPerSource: Int = 4

    /** Quante foto per cartella si tengono (la copertina e' la prima). */
    const val ShowPerSource: Int = 1

    /** CG-3: quante sorgenti accanto a quelle sullo schermo (prima e dopo) si chiedono in anticipo: due righe della griglia, sei righe di elenco. */
    const val Lookahead: Int = 6

    /** Mentre si scorre, la finestra si rifa' al piu' ogni tanto (le copertine che entrano arrivano entro questo tempo). */
    const val WindowStepMs: Long = 120L

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

/** Le copertine sullo schermo e quelle accanto: le richieste di [BackupCovers.refresh]. */
internal class CoverWindow(val visible: List<BackupSource>, val ahead: List<BackupSource>)

/**
 * CG-3: la finestra delle copertine. Quelle sullo schermo ([onScreen], per id) e [lookahead] sorgenti dopo l'ultima e prima della prima, nell'ordine
 * dell'elenco; con niente sullo schermo (la pagina non e' ancora arrivata alle sorgenti) le prime [lookahead], in anticipo.
 */
internal fun coverWindow(sources: List<BackupSource>, onScreen: Set<String>, lookahead: Int): CoverWindow {
  val first = sources.indexOfFirst { it.id in onScreen }
  if (first < 0) return CoverWindow(emptyList(), sources.take(lookahead))
  val last = sources.indexOfLast { it.id in onScreen }
  val visible = sources.filter { it.id in onScreen }
  val after = sources.subList(last + 1, minOf(sources.size, last + 1 + lookahead))
  val before = sources.subList(maxOf(0, first - lookahead), first).asReversed()
  return CoverWindow(visible, (after + before).filter { it.id !in onScreen })
}

/**
 * La foto di copertina di [source] dentro la sua piastrella, o niente (resta la piastrella della famiglia sotto). Si disegna solo se la foto e' nella
 * Libreria visibile adesso; compare quando la miniatura c'e', senza animazione e senza spostare niente. La copertina dice quando e' sullo schermo
 * (CG-3), anche prima di avere la sua foto: e' cosi' che la sua miniatura viene chiesta.
 */
@Composable
internal fun BoxScope.BackupSourceCover(source: BackupSource) {
  val covers = LocalBackupCovers.current ?: return
  CoverPresence(covers, source)
  val media = covers.cover(source) ?: return
  val view by covers.view.collectAsState()
  val request = remember(view, media) { covers.request(view, media) } ?: return
  val image = covers.loader.image(request.key, request.ref).value ?: return
  Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
}

/**
 * CG-3: se la copertina e' sullo schermo, dalla sua posizione nella finestra (ritagliata dai genitori: una riga fuori dall'elenco che scorre ha l'area
 * vuota). Si avvisa [BackupCovers] solo quando cambia; uscendo dalla composizione la copertina esce anche dallo schermo.
 */
@Composable
private fun BoxScope.CoverPresence(covers: BackupCovers, source: BackupSource) {
  val presence = remember(covers, source.id) { Presence() }
  DisposableEffect(presence) {
    onDispose {
      if (presence.seen) covers.leave(source)
      presence.seen = false
    }
  }
  Spacer(
    modifier = Modifier.matchParentSize().onGloballyPositioned { coordinates ->
      val bounds = coordinates.boundsInWindow()
      val now = coordinates.isAttached && bounds.width > 0f && bounds.height > 0f
      if (now != presence.seen) {
        presence.seen = now
        if (now) covers.enter(source) else covers.leave(source)
      }
    },
  )
}

/** Se la copertina e' sullo schermo adesso: un campo semplice, non uno stato (cambiarlo non ridisegna niente). */
private class Presence {
  var seen: Boolean = false
}

/** Il glifo di una sorgente quando non ha (ancora) una foto: la fotocamera, lo screenshot, la cartella. */
internal fun sourceGlyph(source: BackupSource): ImageVector = when (source) {
  BackupSource.Camera -> Icons.Rounded.PhotoCamera
  BackupSource.Screenshots -> Icons.Rounded.Screenshot
  is BackupSource.Folder -> Icons.Rounded.Folder
}
