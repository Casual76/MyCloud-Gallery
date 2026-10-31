package dev.pampa.mycloudgallery.core.common.perf

import android.os.Trace
import java.util.concurrent.ConcurrentHashMap

/**
 * Le sezioni e i segni di traccia dell'avvio e delle pagine (Avvio istantaneo, 28/10): servono al `:benchmark` per
 * misurare il **primo disegno utile** e l'apertura delle pagine, e a chi legge una traccia Perfetto per vedere cosa sta
 * sul percorso critico. Costano un controllo di `atrace` quando nessuno registra: nessuna differenza nella release.
 *
 * Mai dati nei nomi: solo nomi fissi scritti nel codice (nessun id, nessun conteggio, nessun testo dell'utente).
 *
 * - una **sezione** ([section]) misura un pezzo di lavoro sul thread che la apre;
 * - un **segno** ([mark]) e' una sezione di durata zero: dice "da qui in poi" (il benchmark prende il fotogramma che lo segue);
 * - un **segno unico** ([markOnce]) si scrive una volta per processo (il primo disegno utile della Libreria);
 * - una **pagina** ([pageOpened], [pageDrawn]) e' una sezione asincrona dal tocco al primo fotogramma con qualcosa di vero.
 */
object AvvioTrace {

  /** Il prefisso di tutto cio' che questo file scrive. */
  const val PREFIX: String = "MCG:"

  /** La Libreria disegnata con le miniature delle celle in vista (istantanea o dati vivi): il primo disegno utile. */
  const val LIBRARY_USEFUL: String = "MCG:avvio:libreriaUtile"

  /** I dati vivi della Libreria hanno preso il posto dell'istantanea (indice confrontato con il catalogo). */
  const val LIBRARY_LIVE: String = "MCG:avvio:libreriaViva"

  /** I segni intermedi dell'avvio, per leggere una traccia: blocco app superato, destinazione nota, istantanea letta, prima griglia. */
  const val UNLOCKED: String = "MCG:avvio:sbloccato"
  const val DESTINATION: String = "MCG:avvio:destinazione"
  const val SNAPSHOT_READ: String = "MCG:avvio:istantaneaLetta"
  const val GRID_SHOWN: String = "MCG:avvio:griglia"

  /** Il lavoro rimandato a dopo il primo disegno e' partito (o e' scaduta l'attesa). */
  const val DEFERRED_START: String = "MCG:avvio:dopoPrimoDisegno"

  /** Le pagine principali: dal tocco al primo fotogramma con l'ultimo stato o lo scheletro (mai una rotella). */
  const val PAGE_COLLECTIONS: String = "MCG:pagina:raccolte"
  const val PAGE_ALBUM: String = "MCG:pagina:album"
  const val PAGE_PEOPLE: String = "MCG:pagina:persone"
  const val PAGE_SEARCH: String = "MCG:pagina:cerca"

  /**
   * Fase 9 (P12, `docs/fase9-progetto.md` §5.4): il lavoro dei ricordi e della mappa che il benchmark misura. Sezioni asincrone (il lavoro sospende e puo'
   * riprendere su un altro thread), nomi fissi, mai dati:
   *  - [MEMORIES_GENERATE]: un giro del motore dei ricordi con budget notturno (il passo pieno: tabella a colonne, candidati, scelta delle foto, scaffale);
   *  - [MEMORIES_GENERATE_LIGHT]: un giro con il budget del passo leggero (`LIGHT` e `OPEN`, 5 s);
   *  - [MAP_CLUSTERS]: un aggiornamento delle bolle a camera ferma (`BubbleEngine.compute`: celle dell'indice, le piu' grandi, copertine filtrate).
   */
  const val MEMORIES_GENERATE: String = "MCG:memories.generate"
  const val MEMORIES_GENERATE_LIGHT: String = "MCG:memories.generate.light"
  const val MAP_CLUSTERS: String = "MCG:map.clusters"

  /**
   * Il tempo di disegno di un fotogramma di MapLibre, in microsecondi, come lo misura MapLibre stessa (un contatore di traccia, non una sezione: il disegno
   * sta sul suo thread di rendering). Lo scrive la superficie della mappa a ogni fotogramma disegnato; nessun dato, solo il numero.
   */
  const val MAP_DRAW_US: String = "MCG:map.drawUs"

  /**
   * Vero se `android.os.Trace` c'e' davvero: nei test sulla JVM senza Robolectric le classi di Android sono vuote e lancerebbero a ogni
   * chiamata; li' le sezioni non si scrivono e il codice misurato gira lo stesso.
   */
  @PublishedApi
  internal val available: Boolean = runCatching { Trace.isEnabled() }.isSuccess

  private val once = ConcurrentHashMap.newKeySet<String>()
  private val openPages = ConcurrentHashMap<String, Int>()

  /** Esegue [block] dentro la sezione [name] (sul thread che chiama). */
  inline fun <T> section(name: String, block: () -> T): T {
    if (!available) return block()
    Trace.beginSection(name)
    try {
      return block()
    } finally {
      Trace.endSection()
    }
  }

  private val cookies = java.util.concurrent.atomic.AtomicInteger()

  /**
   * Come [section], ma per un lavoro che sospende (puo' riprendere su un altro thread): una sezione asincrona, con un
   * proprio numero.
   */
  suspend fun <T> asyncSection(name: String, block: suspend () -> T): T {
    if (!available) return block()
    val cookie = cookies.incrementAndGet()
    Trace.beginAsyncSection(name, cookie)
    try {
      return block()
    } finally {
      Trace.endAsyncSection(name, cookie)
    }
  }

  /** Un valore sulla traccia (contatore): [name] e' un nome fisso, [value] un numero. Come le sezioni, costa un controllo di `atrace` quando nessuno registra. */
  fun counter(name: String, value: Long) {
    if (!available) return
    Trace.setCounter(name, value)
  }

  /** Un segno di durata zero. */
  fun mark(name: String) {
    if (!available) return
    Trace.beginSection(name)
    Trace.endSection()
  }

  /** Come [mark], ma una volta sola per processo. Vero se questa e' stata la volta. */
  fun markOnce(name: String): Boolean {
    if (!once.add(name)) return false
    mark(name)
    return true
  }

  /** Vero se [markOnce] ha gia' scritto [name]. */
  fun marked(name: String): Boolean = name in once

  /**
   * Una pagina comincia ad aprirsi (il tocco). Una sola apertura per pagina alla volta: una seconda prima del disegno non
   * ne apre un'altra (la misura resta quella del primo tocco).
   */
  fun pageOpened(name: String) {
    val cookie = name.hashCode()
    if (openPages.putIfAbsent(name, cookie) == null && available) Trace.beginAsyncSection(name, cookie)
  }

  /** La pagina ha disegnato qualcosa di vero (ultimo stato o scheletro): chiude la sezione aperta da [pageOpened]. */
  fun pageDrawn(name: String) {
    val cookie = openPages.remove(name) ?: return
    if (available) Trace.endAsyncSection(name, cookie)
  }
}
