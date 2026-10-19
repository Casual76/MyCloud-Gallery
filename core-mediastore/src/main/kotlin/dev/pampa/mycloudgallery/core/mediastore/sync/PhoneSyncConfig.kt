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
 * @property vanishCap il tetto di `markVanishedNotSeenSince` per chiamata.
 * @property vanishAlarmMin quante assenze, in una scansione completa, bastano a fermarsi se sono anche piu' dei file
 *   vivi del volume: un MediaStore che risponde a meta' non deve svuotare la Libreria (E-P1, E-P15).
 * @property reconcileMaxDirect oltre questo numero di `_ID` mancanti la riconciliazione non segna assenze da sola: chiede
 *   una scansione completa, che ha le sue protezioni.
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
  val reconcileMaxDirect: Int = 500,
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
    require(vanishCap > 0 && vanishAlarmMin > 0 && reconcileMaxDirect >= 0 && purgeCap > 0) { "Tetti non validi." }
    require(maxCandidateHashesPerBatch >= 0 && metaBatch > 0 && metaMaxSkips > 0) { "Limiti non validi." }
    require(!foregroundMetaPause.isNegative()) { "Pausa negativa." }
  }
}
