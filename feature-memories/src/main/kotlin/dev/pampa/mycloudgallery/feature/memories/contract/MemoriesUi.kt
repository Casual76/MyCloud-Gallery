package dev.pampa.mycloudgallery.feature.memories.contract

import androidx.compose.ui.graphics.ImageBitmap
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCard
import dev.pampa.mycloudgallery.core.memories.contract.MusicTrackPin
import dev.pampa.mycloudgallery.core.memories.contract.MusicTrackState
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId

// LO STATO COMPLETO DI OGNI SCHERMATA DEI RICORDI (docs/fase9-progetto.md §1.5, D13, D34; riga P9). Gli stati del §1.5 (`MemoryPageState`,
// `PlayerState`, ...) sono il contratto di P0 e non cambiano; qui sta cio' che la schermata ci aggiunge attorno -- il foglio aperto, l'offerta di
// "Annulla", l'introduzione della presentazione -- in tipi **di P9**, cosi' il filone grafico rifinisce senza toccare i contratti. Nessun
// `toString()` mostra titoli, luoghi, date, id o nomi (D33).

/** Cosa si puo' annullare per 10 secondi dopo averlo fatto (§2.3: "Non mostrare" e "Non ricordarmi questo periodo"; "Mostra meno" idem). */
enum class UndoKind { DISMISS, LESS_PERSON, NEVER_DAYS }

/** L'offerta di "Annulla": c'e' per [WINDOW_SECONDS] secondi poi sparisce, e la scelta resta. */
data class UndoOffer(val kind: UndoKind) {
  companion object {
    const val WINDOW_SECONDS: Int = 10
  }
}

/**
 * La pagina del ricordo con tutto cio' che le sta attorno. [preview] e' la voce dello scaffale che si conosce **gia' al primo fotogramma** (titolo,
 * conto, copertina): si disegna subito, la griglia arriva dopo ("Avvio e pagine istantanei"). [less] e' il foglio aperto, [undo] l'offerta di
 * "Annulla", [message] cio' che un'azione ha lasciato.
 */
data class MemoryPageUi(
  val page: MemoryPageState = MemoryPageState.Loading,
  val preview: MemoryCard? = null,
  val less: LessSheetState? = null,
  val undo: UndoOffer? = null,
  val message: MemoriesMessage? = null,
) {
  override fun toString(): String = "MemoryPageUi($page, foglio=${less != null}, annulla=${undo?.kind})"
}

/**
 * "Tutti i ricordi": lo stato del §1.5 e, **solo dopo il filtro finale dei Nascosti**, la Libreria da cui vengono le copertine ([snapshot] null =
 * non si disegna nessuna copertina: la prima immagine e' subito, le copertine arrivano dopo).
 */
data class AllMemoriesUi(
  val state: MemoriesListState,
  val snapshot: TimelineSnapshot? = null,
  /** I nomi delle persone gia' letti (per i titoli dei ricordi `PERSON`): cambiano, quindi la schermata si ricompone. Mai nei log. */
  val named: Map<PersonId, String> = emptyMap(),
) {
  override fun toString(): String = "AllMemoriesUi($state, copertine=${snapshot != null})"
}

/** Il titolo sopra la copertina nei primi 2 secondi della presentazione (§2.4), o finche' l'utente non la fa partire con TalkBack. */
data class PlayerIntro(val header: MemoryHeader, val cover: PlayerItem) {
  override fun toString(): String = "PlayerIntro($header)"
}

/**
 * La presentazione con tutto cio' che la schermata disegna (D13, §2.4): [state] il passo del §1.5 (null = si sta caricando), [header] la testa del
 * ricordo (per la descrizione di ogni foto), [intro] il titolo,
 * [items] tutti gli elementi di adesso (per la carta finale), [snapshot] la Libreria da cui vengono le miniature, [controls] i comandi visibili
 * (dopo un tocco, in pausa, con TalkBack sempre), [musicMissing] l'avviso "la canzone non c'e' piu'", [gone] "Questo ricordo non c'e' piu'".
 */
data class PlayerUi(
  val state: PlayerState? = null,
  val header: MemoryHeader? = null,
  val intro: PlayerIntro? = null,
  val items: List<PlayerItem> = emptyList(),
  val snapshot: TimelineSnapshot? = null,
  val controls: Boolean = false,
  val musicMissing: Boolean = false,
  val gone: Boolean = false,
) {
  override fun toString(): String = "PlayerUi($state, ${items.size} elementi, gone=$gone)"
}

/**
 * Le immagini della presentazione (D13, E-MEM14, E-MEM15): **mai un'attesa**. [want] chiede senza aspettare le immagini dell'elemento di adesso e
 * dei successivi (al piu' [PlayerState.Playing.PRELOAD]); [previewOf] da' l'anteprima ad alta qualita' locale **se c'e' gia'**, altrimenti null e
 * la schermata usa la miniatura grande e poi il segnaposto. In memoria al piu' tre bitmap a schermo intero.
 */
interface PlayerImages {
  fun want(snapshot: TimelineSnapshot, current: MediaId, next: List<MediaId>)

  fun previewOf(media: MediaId): ImageBitmap?

  fun clear()
}

/** Senza legame: nessuna anteprima, la schermata usa le miniature e i segnaposto. */
object NoPlayerImages : PlayerImages {
  override fun want(snapshot: TimelineSnapshot, current: MediaId, next: List<MediaId>) = Unit

  override fun previewOf(media: MediaId): ImageBitmap? = null

  override fun clear() = Unit
}

/** Un brano del pacchetto di musica libera come lo mostra la pagina delle impostazioni, con il suo stato (Q3). */
data class MusicTrackRow(val pin: MusicTrackPin, val state: MusicTrackState, val chosen: Boolean) {
  override fun toString(): String = "MusicTrackRow(${state.javaClass.simpleName}, scelto=$chosen)"
}

/**
 * Impostazioni -> Ricordi e notifiche con cio' che la schermata ci aggiunge attorno al §1.5: [tracks] le righe dei brani del pacchetto di musica libera
 * (**vuote** finche' l'utente non approva fonti e brani, Q3: la pagina dice che il pacchetto non c'e' ancora) e [message] cio' che un'azione ha
 * lasciato. Q6 (a): nessuna riga del filtro automatico.
 */
data class MemoriesSettingsUi(
  val state: MemoriesSettingsState,
  val tracks: List<MusicTrackRow> = emptyList(),
  val message: MemoriesMessage? = null,
) {
  override fun toString(): String = "MemoriesSettingsUi($state, ${tracks.size} brani)"
}
