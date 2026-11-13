package dev.pampa.mycloudgallery.core.mediastore.sync

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * I numeri della lettura del telefono (docs/fase3-progetto.md §2.1). Valori di default = quelli del progetto; i test li
 * abbassano per provare lotti, tetti e riprese con poche righe.
 *
 * @property batchSize righe per lotto: una transazione del catalogo e un salvataggio del cursore per lotto.
 * @property fullHashMaxBytes oltre questa dimensione l'hash completo non si calcola durante la lettura: il media resta
 *   separato (`KEEP_SEPARATE`) e lo completa la manutenzione. Nessuna lettura a file interi grandi durante lo scorrimento.
 * @property vanishCap quante assenze confermate per transazione (l'accesso si ricontrolla prima di ognuna).
 * @property vanishAlarmMin l'ondata: se le posizioni che il MediaStore non mostra sono almeno `max(vanishAlarmMin, vivi)` (i vivi sono gli
 *   `_ID` del volume), un MediaStore che risponde a meta' non deve svuotare la Libreria (E-P1, E-P15): solo le assenze che il disco,
 *   attendibile su [waveProbeSample] vivi di controllo, dice con certezza file per file (pacchetto F, FS2-6, FS3-1: una cartella cancellata
 *   davvero esce; righe solo nascoste restano). Con meno vivi di controllo nessuna conferma automatica (un volume svuotato tutto restera' con i
 *   suoi fantasmi finche' non arriva una prova o una scelta dell'utente).
 * @property absenceConfirmAfter pacchetto F (FS-1): una posizione che il MediaStore non mostra (un `_ID` che manca, una posizione
 *   non vista da una scansione completa) diventa assente solo a un confronto successivo, almeno dopo questo tempo, se manca ancora
 *   e il controllo puntuale non la trova. Un provider in ricostruzione (riavvio, aggiornamento) non fa sparire foto vere.
 * @property probeSample pacchetto F (FS3-1): i vivi di controllo che il disco deve vedere perche' una sua risposta "non c'e'" valga: fino a
 *   tanti nella cartella di ogni candidato e tanti sparsi sul volume (un disco cieco non conferma niente).
 * @property waveProbeSample in un'ondata (almeno [vanishAlarmMin] posizioni mancanti) i vivi di controllo sparsi sul volume devono essere
 *   **esattamente** tanti: con meno vivi nessuna conferma automatica. Solo con il disco attendibile e la prova file per file un'ondata vera
 *   (una cartella cancellata dal PC) si conferma.
 * @property absenceUnknownRetry pacchetto F (FS3-7): dopo una conferma finita in "non so", fra quanto si riprova in primo piano (il giro da
 *   solo), senza un ciclo stretto.
 * @property recoverCap quanti `_ID` vivi sconosciuti al catalogo si rileggono al piu' per giro, per riportare presente una posizione
 *   assente tornata con un altro `_ID` (FS2-5).
 * @property absenceRecheckMargin il margine dopo la maturazione di un'annotazione prima del giro da solo in primo piano.
 * @property quickReconcileEvery ogni quanto, al massimo, un incrementale con cancellazioni non spiegate (la generazione sale
 *   piu' delle righe viste e di quelle in `deleted_files`) confronta subito gli `_ID` con la cache; null = solo la
 *   manutenzione quotidiana. Serve perche' `deleted_files` puo' rispondere vuoto alle app (emulatore API 37).
 * @property purgeCap le assenze scadute dimenticate al massimo in un giro di manutenzione (E-P9).
 * @property maxCandidateHashesPerBatch quante copie gia' note si rileggono per intero, per lotto, per decidere se un file
 *   nuovo e' identico (doppioni di WhatsApp): oltre, nel dubbio, si tiene separato.
 * @property foregroundMetaBudget quanto lavora META in primo piano dopo ogni giro, per fetta.
 * @property foregroundMetaPause la pausa fra due fette di META in primo piano, finche' il lavoro non e' finito (GA-6).
 * @property metaBatch media per passo di META.
 * @property metaRetryAfter dopo quanto si riprova un META fallito.
 * @property metaMaxSkips quanti media saltati (senza copia leggibile, permesso della posizione negato) fermano META.
 */
data class PhoneSyncConfig(
  val batchSize: Int = 500,
  val fullHashMaxBytes: Long = 64L * 1024 * 1024,
  val vanishCap: Int = 500,
  val vanishAlarmMin: Int = 2_000,
  val absenceConfirmAfter: Duration = 5.minutes,
  val absenceUnknownRetry: Duration = 15.minutes,
  val probeSample: Int = 3,
  val waveProbeSample: Int = 20,
  val recoverCap: Int = 2_000,
  val absenceRecheckMargin: Duration = 10.seconds,
  val quickReconcileEvery: Duration? = 2.minutes,
  val purgeCap: Int = 500,
  val maxCandidateHashesPerBatch: Int = 20,
  val foregroundMetaBudget: Duration = 30.seconds,
  val foregroundMetaPause: Duration = 5.seconds,
  val metaBatch: Int = 20,
  val metaRetryAfter: Duration = 1.hours,
  val metaMaxSkips: Int = 2_000,
) {
  init {
    require(batchSize in 1..2_000) { "Lotto fuori misura." }
    require(fullHashMaxBytes >= 0) { "Soglia dell'hash completo negativa." }
    require(vanishCap > 0 && vanishAlarmMin > 0 && purgeCap > 0) { "Tetti non validi." }
    require(!absenceConfirmAfter.isNegative()) { "Attesa delle assenze negativa." }
    require(absenceUnknownRetry.isPositive()) { "Nuovo tentativo non positivo." }
    require(probeSample > 0 && waveProbeSample >= probeSample && recoverCap >= 0) { "Campioni non validi." }
    require(!absenceRecheckMargin.isNegative()) { "Margine negativo." }
    require(maxCandidateHashesPerBatch >= 0 && metaBatch > 0 && metaMaxSkips > 0) { "Limiti non validi." }
    require(!foregroundMetaPause.isNegative()) { "Pausa negativa." }
  }
}
