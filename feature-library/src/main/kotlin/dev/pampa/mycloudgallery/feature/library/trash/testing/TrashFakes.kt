package dev.pampa.mycloudgallery.feature.library.trash.testing

import dev.pampa.mycloudgallery.feature.library.trash.DoubleTapConfirm
import dev.pampa.mycloudgallery.feature.library.trash.NasTrashFeed
import dev.pampa.mycloudgallery.feature.library.trash.NasTrashOutcome
import dev.pampa.mycloudgallery.feature.library.trash.NasTrashPort
import dev.pampa.mycloudgallery.feature.library.trash.NasTrashRow
import dev.pampa.mycloudgallery.feature.library.trash.UserTap
import dev.pampa.mycloudgallery.feature.library.trash.singleTap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// I fake degli Eliminati di recente (Fase 7, P8, D26): in src/main, cosi' i test di questo modulo, gli screenshot e i test d'integrazione di `:app`
// (P13) li riusano. Mai nella release: tools/check.ps1 fallisce se una classe di un package `testing` compare nel dex dell'APK di release.

/**
 * Il cestino del NAS finto: tiene le voci, registra i gesti e si comporta come l'adattatore vero sopra `NasOperations`:
 *  - **ripristina** solo le voci con un record valido, in una share scrivibile;
 *  - **"Elimina subito"** solo le voci **proprie** e solo con **due tocchi** (`requiredConfirmations` del piano `PURGE_NOW`): con meno, o con
 *    chiavi non proprie, non elimina niente e risponde [NasTrashOutcome.REJECTED].
 * Cosi' un test che arriva a un'eliminazione con una prova debole, o su una voce non propria, vede il rifiuto.
 */
class FakeNasTrash(
  rows: List<NasTrashRow> = emptyList(),
  writable: Boolean = true,
) : NasTrashPort {

  private val state = MutableStateFlow<NasTrashFeed>(NasTrashFeed.Ready(rows, writable))
  override val feed: StateFlow<NasTrashFeed> = state

  /** Quante volte e' stato riletto. */
  var refreshes: Int = 0
    private set

  /** Le richieste di ripristino (le chiavi) e i tocchi con cui sono arrivate. */
  val restores: MutableList<Pair<Set<String>, Int>> = mutableListOf()

  /** Le richieste di "Elimina subito" (le chiavi) e i tocchi con cui sono arrivate, anche quelle rifiutate. */
  val purges: MutableList<Pair<Set<String>, Int>> = mutableListOf()

  /** Cosa risponde ai gesti (di serie: partiti). */
  var restoreOutcome: NasTrashOutcome = NasTrashOutcome.STARTED
  var purgeOutcome: NasTrashOutcome = NasTrashOutcome.STARTED

  /** Cambia cio' che il NAS dice (nuove voci, il NAS che non risponde...). */
  fun show(feed: NasTrashFeed) {
    state.value = feed
  }

  private fun ready(): NasTrashFeed.Ready? = state.value as? NasTrashFeed.Ready

  override suspend fun refresh() {
    refreshes++
  }

  override suspend fun restore(keys: Set<String>, tap: UserTap): NasTrashOutcome {
    restores += keys to tap.taps
    if (restoreOutcome != NasTrashOutcome.STARTED) return restoreOutcome
    val current = ready() ?: return NasTrashOutcome.REJECTED
    val doable = current.rows.filter { it.key in keys && it.restorable && !it.withoutValidRecord }
    if (!current.writable || doable.isEmpty()) return NasTrashOutcome.REJECTED
    state.value = current.copy(rows = current.rows.filter { row -> doable.none { it.key == row.key } })
    return NasTrashOutcome.STARTED
  }

  override suspend fun purgeNow(keys: Set<String>, tap: UserTap): NasTrashOutcome {
    purges += keys to tap.taps
    val current = ready() ?: return NasTrashOutcome.REJECTED
    val own = current.rows.filter { it.key in keys && it.deletableNow }
    // Come il piano PURGE_NOW: due tocchi, e solo voci proprie. Una chiave non propria non elimina niente.
    if (!current.writable || tap.taps < 2 || own.isEmpty() || own.size != keys.size) return NasTrashOutcome.REJECTED
    if (purgeOutcome != NasTrashOutcome.STARTED) return purgeOutcome
    state.value = current.copy(rows = current.rows.filter { row -> own.none { it.key == row.key } })
    return NasTrashOutcome.STARTED
  }
}

/** Qualche voce del cestino del NAS per le prove e gli screenshot: proprie e di altri dispositivi, una cartella, una senza record valido. */
fun sampleNasRows(): List<NasTrashRow> = listOf(
  NasTrashRow(
    key = "mio/a1", name = "IMG_20261012_184501.jpg", folder = "Backup / Pixel 9 / 2026 / 10", sizeBytes = 4_200_000, daysLeft = 29,
    deletableNow = true,
  ),
  NasTrashRow(
    key = "mio/a2", name = "VID_20261008_121100.mp4", folder = "Backup / Pixel 9 / 2026 / 10", sizeBytes = 182_000_000, daysLeft = 17,
    isVideo = true, deletableNow = true,
  ),
  NasTrashRow(
    key = "tab/b1", name = "Cena da Marco.jpg", folder = "Famiglia / Cene", sizeBytes = 3_100_000, daysLeft = 2,
    author = "Tablet di casa",
  ),
  NasTrashRow(
    key = "tab/b2", name = "Gita al lago", folder = "Famiglia / Viaggi", sizeBytes = 612_000_000, daysLeft = 11,
    isFolder = true, fileCount = 148, author = "Tablet di casa",
  ),
  NasTrashRow(
    key = "tab/b3", name = null, folder = null, sizeBytes = 900_000, daysLeft = 5, withoutValidRecord = true, restorable = false,
  ),
)

/**
 * La prova di **un** tocco, per i test di chi lega le porte (`:app`): la prova vera nasce solo nel foglio, e il suo costruttore e' interno a questo modulo
 * (un test d'architettura lo guarda), quindi `:app` non ne puo' fabbricare una. Mai usata dalle schermate.
 */
fun fakeSingleTap(): UserTap = singleTap()

/** La prova di **due tocchi distinti**, come la da' il foglio "Elimina subito" (il secondo oltre i 600 ms dal primo). */
fun fakeDoubleTap(): UserTap {
  val confirm = DoubleTapConfirm(shownAtMonotonicMs = 0)
  check(!confirm.tap(1_000))
  check(confirm.tap(2_000))
  return checkNotNull(confirm.proof())
}
