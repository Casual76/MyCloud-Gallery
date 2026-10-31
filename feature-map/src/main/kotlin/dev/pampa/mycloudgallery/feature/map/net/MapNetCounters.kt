package dev.pampa.mycloudgallery.feature.map.net

import dev.pampa.mycloudgallery.core.map.contract.MapHost
import dev.pampa.mycloudgallery.core.map.contract.RefuseReason
import java.io.IOException
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

// I CONTATORI DEL CLIENT DELLE TESSERE (Fase 9, P1; docs/fase9-progetto.md §2.8 "L'intercettore ... conta richieste, byte e rifiuti per motivo
// (solo numeri, Diagnostica)", §5.5 riga "Debug", D33). Solo numeri: richieste uscite, per livello `z` (mai `x` e `y`), byte arrivati, rifiuti
// per motivo, errori del server o della rete, pause, TileJSON chiesti. Nessuna tessera, nessun percorso, nessun host diverso: niente che dica
// la zona guardata. P13 li porta nella Diagnostica (`MapDiag.MAP_REQUESTS`, `MAP_REFUSED`) attraverso `MapLibreInit.counts()`.

/** I numeri di adesso (una copia: chi li legge non vede i cambi successivi). `toString()` di soli numeri. */
internal data class MapNetSnapshot(
  val allowed: Long,
  val byZoom: List<Long>,
  val bytes: Long,
  val refused: Map<RefuseReason, Long>,
  val errors: Long,
  val paused: Long,
  val noVersion: Long,
  val tileJsonFetches: Long,
  /** Al massimo quante richieste sono state in corso insieme (condizioni d'uso di OpenFreeMap: al piu' 4 verso l'host, `MapTileHttp.MAX_REQUESTS_PER_HOST`). */
  val peakInFlight: Int = 0,
)

internal class MapNetCounters {
  private val allowed = AtomicLong()
  private val byZoom = AtomicLongArray(MapHost.MAX_ZOOM + 1)
  private val bytes = AtomicLong()
  private val refused = RefuseReason.entries.associateWith { AtomicLong() }
  private val errors = AtomicLong()
  private val paused = AtomicLong()
  private val noVersion = AtomicLong()
  private val tileJsonFetches = AtomicLong()
  private val inFlight = AtomicInteger()
  private val peakInFlight = AtomicInteger()

  /** Una richiesta uscita e tornata con una risposta del server ([zoom] null per il TileJSON). */
  fun allowed(zoom: Int?) {
    allowed.incrementAndGet()
    if (zoom != null && zoom in 0..MapHost.MAX_ZOOM) byZoom.incrementAndGet(zoom)
  }

  fun bytes(count: Long) {
    if (count > 0) bytes.addAndGet(count)
  }

  fun refused(reason: RefuseReason) {
    refused.getValue(reason).incrementAndGet()
  }

  /** Il server ha risposto male (5xx, 429) o la rete non ha risposto: E-MAP3, attesa crescente. */
  fun error() {
    errors.incrementAndGet()
  }

  /** Una richiesta ammessa ma tenuta ferma dall'attesa dopo un errore (E-MAP3). */
  fun paused() {
    paused.incrementAndGet()
  }

  /** Una tessera ammessa ma senza la versione del server (TileJSON non arrivato o non valido). */
  fun noVersion() {
    noVersion.incrementAndGet()
  }

  /** Una richiesta entra nel client (dopo la decisione del cancello): conta quante sono insieme, e il massimo. */
  fun enter() {
    val now = inFlight.incrementAndGet()
    peakInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
  }

  fun exit() {
    inFlight.decrementAndGet()
  }

  fun tileJsonFetch() {
    tileJsonFetches.incrementAndGet()
  }

  fun snapshot(): MapNetSnapshot = MapNetSnapshot(
    allowed = allowed.get(),
    byZoom = List(byZoom.length()) { byZoom.get(it) },
    bytes = bytes.get(),
    refused = refused.mapValues { it.value.get() },
    errors = errors.get(),
    paused = paused.get(),
    noVersion = noVersion.get(),
    tileJsonFetches = tileJsonFetches.get(),
    peakInFlight = peakInFlight.get(),
  )
}

/**
 * Una richiesta che il cancello non lascia uscire **adesso** (mappa spenta, rete non permessa, tessera coperta da una regione, versione del
 * server non nota, attesa dopo un errore): per MapLibre e' un errore di connessione (`SocketException`), cioe' "riprova quando la rete torna",
 * e i dati che ha gia' (cache, regioni) restano. Il messaggio non dice mai quale tessera.
 */
internal class MapRequestHeld(val reason: RefuseReason?) : SocketException("mappa: richiesta trattenuta (${reason?.code ?: "attesa"})")

/**
 * Una richiesta che il cancello rifiuta **per sempre** (host, percorso, redirect, risposta inattesa): per MapLibre e' un errore definitivo (un
 * `IOException` qualunque) e non la richiede piu' a raffica. Il messaggio non dice mai quale tessera.
 */
internal class MapRequestRefused(val reason: RefuseReason) : IOException("mappa: richiesta rifiutata (${reason.code})")
