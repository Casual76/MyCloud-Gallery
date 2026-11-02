package dev.pampa.mycloudgallery.core.widget.contract

import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.StableCode
import java.time.YearMonth

// IL WIDGET "MyCloud Gallery" (docs/fase10-progetto.md §1.2 "core-widget", D3-D9; risposte Q1 (a), Q2 (a), Q3 (c), Q4 (a) del 31/10). Contratto
// di P0. Un solo tipo in tre misure; di base il ricordo di oggi, altrimenti foto a caso (Q1 (a)); mai un nascosto, mai una persona, mai un
// nome. Il piano e' fatto di **dati**: il testo del titolo e le descrizioni li compone la UI (feature-widget, P3). Nessun `toString()` mostra id
// di media, titoli, luoghi o date.

/** Un widget sulla Home, per il suo `appWidgetId` (positivo, come li da' Android). */
@JvmInline
value class WidgetSlot(val appWidgetId: Int) {
  init {
    require(appWidgetId > 0) { "Id di widget non valido." }
  }

  override fun toString(): String = "WidgetSlot($appWidgetId)"
}

/**
 * Le tre misure di D3 (`SizeMode.Responsive`): [cells] quante immagini al piu', [thumbPx] il lato lungo delle miniature locali (D7).
 * - [SMALL] 2x2: una foto, o la copertina del ricordo con il titolo;
 * - [WIDE] 4x2: la copertina del ricordo piu' tre foto, o quattro foto;
 * - [LARGE] 4x4: la copertina e sei foto.
 */
enum class WidgetSize(override val code: String, val cells: Int, val thumbPx: Int) : StableCode {
  SMALL("small", 1, 384),
  WIDE("wide", 4, 384),
  LARGE("large", 7, 512),
}

/** Che cosa mostra il widget (Q1). [DEFAULT] e' la risposta dell'utente: il ricordo di oggi se c'e', altrimenti foto a caso. */
enum class WidgetChoice(override val code: String) : StableCode {
  MEMORY_THEN_PHOTOS("memory_then_photos"),
  PHOTOS_ONLY("photos_only"),
  MEMORIES_ONLY("memories_only"),
  ;

  companion object {
    /** Q1 (a). */
    val DEFAULT: WidgetChoice = MEMORY_THEN_PHOTOS
  }
}

/** Ogni quanto cambiano le foto (Q2): mai fra le 23 e le 7 ([WidgetScheduleRules]). [DEFAULT] e' la risposta dell'utente. */
enum class WidgetPeriod(override val code: String, val hours: Int) : StableCode {
  HOURS_3("hours_3", 3),
  HOURS_6("hours_6", 6),
  DAILY("daily", 24),
  ;

  companion object {
    /** Q2 (a): circa ogni 3 ore, mai di notte. */
    val DEFAULT: WidgetPeriod = HOURS_3
  }
}

/** Perche' il widget non mostra foto (E-W1, E-W5, E-W6, E-W9, D5 punto 5): un codice stabile, il testo e' della UI ("Apri MyCloud Gallery"). */
enum class WidgetEmpty(override val code: String) : StableCode {
  /** La Libreria non ha niente da mostrare (vuota, o tutto escluso). */
  NO_MEDIA("no_media"),

  /** Ci sono media ma nessuno ha la miniatura sul telefono (qualita' Risparmio, replica in corso): mai il NAS, mai la rete (D7). */
  NO_LOCAL_THUMBS("no_local_thumbs"),

  /** "Solo ricordi" e oggi nessun ricordo: l'invito ad aprire l'app. */
  NO_MEMORY_TODAY("no_memory_today"),

  /** Blocco dell'app attivo e nessun media che sia anche sul telefono (Q3 (c)). */
  LOCKED_NOTHING_ON_PHONE("locked_nothing_on_phone"),

  /** Azzeramento, dispositivo perso, uscita dalla famiglia, app non configurata: "Configura MyCloud Gallery" (D42). */
  NOT_CONFIGURED("not_configured"),

  /** Un errore di lettura o il budget superato: nessuna foto, mai una lista non verificata (D5, punto 5). */
  ERROR("error"),

  /**
   * La Libreria non e' ancora pronta (processo freddo, istantanea non pubblicata, E-W4): **non** si ridisegna, resta il contenuto gia' mostrato
   * (era verificato, o gia' svuotato da `WidgetRefresher.hiddenChanged`) e si riprova al giro dopo. Mai un contenuto non verificato.
   */
  NOT_READY("not_ready"),
}

/**
 * Il titolo del ricordo **come dati** (D4): [yearsAgo] "Un anno fa", [placeIds] i luoghi principali (id GeoNames, al piu' 3), [tripLabel]
 * l'etichetta **generata** dalla Fase 7 per un viaggio o un evento ("Oslo, marzo 2025"), [month] il mese di "Il meglio del mese". **Mai una
 * persona**: non c'e' un campo per lei (un ricordo `PERSON` non arriva mai al widget).
 */
data class WidgetTitle(
  val yearsAgo: Int?,
  val placeIds: List<Int>,
  val tripLabel: String?,
  val month: YearMonth?,
) {
  init {
    require(yearsAgo == null || yearsAgo >= 1) { "Anni fa: almeno uno." }
    require(placeIds.size <= MAX_PLACES && placeIds.all { it > 0 }) { "Luoghi non validi." }
    require(tripLabel == null || tripLabel.length <= MAX_LABEL) { "Etichetta troppo lunga." }
  }

  override fun toString(): String = "WidgetTitle(anniFa=${yearsAgo != null}, luoghi=${placeIds.size}, viaggio=${tripLabel != null})"

  companion object {
    const val MAX_PLACES: Int = 3
    const val MAX_LABEL: Int = 80
  }
}

/** Una cella del piano: [index] la posizione (0 = la prima, la copertina in un ricordo), il tipo (il segno "video", E-W17) e il giorno locale. */
data class WidgetCell(val index: Int, val media: MediaId, val kind: MediaKind, val localDay: Int) {
  init {
    require(index in 0 until WidgetSize.LARGE.cells) { "Indice di cella fuori dai limiti." }
  }

  override fun toString(): String = "WidgetCell($index, ${kind.code})"
}

/** Che cosa mostra un widget. */
sealed interface WidgetMode {
  /** Il ricordo di oggi (D3, D4): la cella 0 e' la copertina; il titolo come dati. */
  data class Memory(val id: MemoryId, val title: WidgetTitle) : WidgetMode {
    override fun toString(): String = "WidgetMode.Memory"
  }

  /** Foto a caso dalla Libreria (Q1 (a) nei giorni senza ricordo, o "Solo foto"). */
  data object Photos : WidgetMode

  /** Blocco dell'app attivo (Q3 (c)): solo media che sono anche sul telefono, **nessun titolo**, nessun ricordo. */
  data object Locked : WidgetMode

  /** Nessuna foto: "Apri MyCloud Gallery" o "Configura MyCloud Gallery" (E-W1, D42). */
  data class Message(val reason: WidgetEmpty) : WidgetMode
}

/**
 * Il piano di un widget, deciso **subito prima** del disegno (§2.1): chi lo consegna ha gia' applicato il filtro finale (`VisibleMedia` e
 * `HiddenMask`) a ogni cella. [epoch] cresce a ogni piano dello stesso [slot] (il registro tiene solo l'ultimo).
 */
data class WidgetPlan(
  val slot: WidgetSlot,
  val size: WidgetSize,
  val mode: WidgetMode,
  val cells: List<WidgetCell>,
  val epoch: Long,
) {
  init {
    require(cells.size <= size.cells) { "Troppe celle per la misura." }
    require(cells.map { it.index } == cells.indices.toList()) { "Le celle vanno da 0 in ordine." }
    require(cells.map { it.media }.distinct().size == cells.size) { "Celle ripetute." }
    require(mode !is WidgetMode.Message || cells.isEmpty()) { "Un messaggio non ha foto." }
    require(mode is WidgetMode.Message || cells.isNotEmpty()) { "Un piano con foto ne ha almeno una." }
    require(epoch >= 0) { "Epoca negativa." }
  }

  /** Gli id mostrati (per il registro e per `WidgetLedger.showing`). */
  val shown: Set<MediaId> get() = cells.mapTo(LinkedHashSet()) { it.media }

  override fun toString(): String = "WidgetPlan($slot, ${size.code}, $mode, ${cells.size} celle)"
}

/**
 * Le regole fisse del widget (D3-D7). P2 e P3 le leggono da qui; i test le pretendono.
 */
object WidgetRules {
  /** Al piu' 7 immagini per widget (D3). */
  const val MAX_IMAGES: Int = 7

  /** Si chiedono a `WidgetFeed` il triplo delle foto che servono: alcune cadono per il blocco o le miniature (§2.1, passo 3). */
  const val OVERFETCH: Int = 3

  /** Tetto delle bitmap per widget (D7): 2 MiB. */
  const val MAX_BITMAP_BYTES: Long = 2L * 1024 * 1024

  /** Un ricordo va sul widget solo con almeno 8 elementi visibili (D3); sotto, foto a caso (E-W19). */
  const val MEMORY_MIN_VISIBLE: Int = 8

  /** Budget di un giro (D6): 2 s; in un processo freddo si montano solo istantanea, maschera e scaffale, al piu' 1,5 s (§2.1, passo 2). */
  const val BUDGET_MS: Long = 2_000
  const val COLD_START_BUDGET_MS: Long = 1_500

  /**
   * L'attesa delle porte del **lavoro periodico** in un processo nato per lui (WG-5): non ha fretta (WorkManager gli da' minuti) e su un archivio grande 1,5 s dalla nascita del
   * processo non bastano a montare la Libreria, quindi il widget non si aggiornerebbe che all'apertura dell'app. Il ricevitore e lo svuotamento per un nascosto restano a
   * [COLD_START_BUDGET_MS].
   */
  const val PERIODIC_COLD_START_BUDGET_MS: Long = 10_000

  /** Svuotamento dopo un "Nascondi" (D5): un disegno senza immagini, nessuna decodifica [stima < 100 ms]. */
  const val BLANK_TARGET_MS: Long = 100
}
