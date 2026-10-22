package dev.pampa.mycloudgallery.core.ai.chaos

import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.AiSegments
import java.util.Locale

// Il VERIFICATORE del calcolo: gli invarianti del §5.5 letti dal NAS finto, dall'indice locale e dai registri del mondo. Ogni controllo
// restituisce violazioni (mai un'eccezione): un giro ne raccoglie tante.
//  1  nessun modello non verificato caricato (il motore delle immagini non gira senza un caricamento verificato; `models/` pulita);
//  4  nessun calcolo perso: un media salvato prima della morte non si ricalcola (nessun secondo salvataggio della stessa chiave);
//  5  dati coerenti: ogni riga ha il suo record nel file con il CRC giusto; ogni voce pubblicata ha la sua riga locale (e si pubblica solo
//     dopo averla salvata); ogni voce in attesa ha il corpo con cui e' nata;
//  6  nessuna voce fuori posto: nessuna voce per contenuti di un'altra share (M3), nessuna area privata di un altro toccata;
//  7  prenotazioni: mai oltre un'ora, mai la stessa unita' a due dispositivi insieme;
//  8  il lavoro arriva in fondo (a rete sana ogni contenuto in ambito ha `vec` e `ocr` della ricetta corrente o e' "non supportato");
//  9  buffer sotto il tetto, nessun temporaneo orfano, niente di gia' fatto rimasto;
// 10  nascosti: si calcolano (E-H1) e una ricerca di riferimento a fine giro non ne mostra nessuno.

class AiChaosVerifier(private val w: AiChaosWorld) {
  private val recipe = AiRecipe.CURRENT
  private var modelReported = 0
  private var publisherReported = 0

  private fun v(inv: AiInvariant, at: AiCheckPoint, detail: String) = AiViolation(inv, at, detail)

  /** Quello che si controlla dopo ogni morte e a fine passo: invarianti 1, 4, 5, 6 e 7. */
  fun cheap(at: AiCheckPoint): List<AiViolation> {
    w.stats.checks++
    val out = ArrayList<AiViolation>()
    val a = w.a
    // 1: il modello.
    val model = w.model
    while (modelReported < model.violations.size) out += v(AiInvariant.VERIFIED_MODEL, at, model.violations[modelReported++])
    model.directoryViolations().forEach { out += v(AiInvariant.VERIFIED_MODEL, at, it) }
    // 4: nessun calcolo rifatto.
    a.index.recomputed.forEach { out += v(AiInvariant.NO_RECOMPUTE, at, it) }
    a.index.recomputed.clear()
    // 5: le righe, il file, le voci.
    for ((k, row) in a.index.rows) if (!a.index.valid(row)) out += v(AiInvariant.COHERENT_DATA, at, "riga ${k.second.wire} senza il suo record nel file o con il CRC sbagliato")
    while (publisherReported < a.publisher.violations.size) {
      val (inv, detail) = a.publisher.violations[publisherReported++]
      out += v(inv, at, detail)
    }
    w.violations.forEach { out += v(it.first, at, it.second) }
    w.violations.clear()
    for (share in listOf(w.annaPrivate, w.common)) {
      for ((k, body) in w.nas.of(share).heads[a.id].orEmpty()) {
        val row = a.index.rows[k]
        if (row == null) {
          out += v(AiInvariant.COHERENT_DATA, at, "voce ${k.second.wire} pubblicata senza la sua riga locale")
        } else if (!AiSegments.encodeBody(body).contentEquals(AiSegments.encodeBody(row.body))) {
          out += v(AiInvariant.COHERENT_DATA, at, "voce ${k.second.wire} pubblicata diversa dalla riga locale")
        }
      }
    }
    for ((share, entries) in a.publisher.heldEntries()) {
      var count = 0
      for ((k, bytes) in entries) {
        count++
        val row = a.index.rows[k]
        if (row == null || !AiSegments.encodeBody(row.body).contentEquals(bytes)) out += v(AiInvariant.COHERENT_DATA, at, "voce ${k.second.wire} in attesa diversa da come e' nata")
      }
      if (a.publisher.waiting(share) != count) out += v(AiInvariant.COHERENT_DATA, at, "voci in attesa: ${a.publisher.waiting(share)} nel pubblicatore, $count in fila")
    }
    // 6: nessuna voce fuori posto.
    for (share in w.nas.shares.values) {
      for ((device, heads) in share.heads) {
        for (k in heads.keys) if (k.first !in share.media) out += v(AiInvariant.OUT_OF_PLACE, at, "voce di $device per un contenuto che non e' di questa share")
      }
    }
    if (a.simPublisher.foreign > 0) out += v(AiInvariant.OUT_OF_PLACE, at, "${a.simPublisher.foreign} voci messe in fila per contenuti di un'altra share")
    if (w.b.publisher.foreign > 0) out += v(AiInvariant.OUT_OF_PLACE, at, "l'altro dispositivo ha messo in fila voci per un'altra share")
    val mine = setOf(w.annaPrivate, w.common)
    w.nas.touched[a.id].orEmpty().filter { it !in mine }.forEach { out += v(AiInvariant.OUT_OF_PLACE, at, "toccata un'area che non e' di questo dispositivo") }
    w.nas.touched[w.b.id].orEmpty().filter { it == w.annaPrivate }.forEach { out += v(AiInvariant.OUT_OF_PLACE, at, "l'altro dispositivo ha toccato un'area privata altrui") }
    out += leases(at)
    // 9: il tetto del buffer.
    if (a.buffer.usedBytes.value > ChaosDevice.BUFFER_MAX) out += v(AiInvariant.BUFFER, at, "buffer oltre il tetto: ${a.buffer.usedBytes.value} byte")
    return out
  }

  /** Invariante 7: prenotazioni. */
  private fun leases(at: AiCheckPoint): List<AiViolation> {
    val out = ArrayList<AiViolation>()
    val max = TrustLimits.MAX_LEASE_MILLIS
    for (share in w.nas.shares.values) {
      for ((resource, held) in share.leases) {
        if (held.second > w.nas.now + max) out += v(AiInvariant.LEASES, at, "prenotazione oltre l'ora: ${(held.second - w.nas.now) / 60_000} minuti")
        if (resource.isEmpty()) out += v(AiInvariant.LEASES, at, "prenotazione senza risorsa")
      }
    }
    for (d in w.leases.doubleBooked) out += v(AiInvariant.LEASES, at, d)
    w.leases.doubleBooked.clear()
    // Mai la stessa unita' prenotata da due dispositivi insieme: le concessioni di Anna contro quelle di Bruno (`until - 1 ora` = quando).
    val log = w.leases
    for (claim in w.b.units.claims) {
      val held = claim.lease as? dev.pampa.mycloudgallery.core.index.contract.UnitLease.Held ?: continue
      val at0 = held.untilNasMillis - max
      for (g in log.grants) {
        if (g.resource != LeaseLog.idOf(claim) || at0 <= g.at || at0 >= g.until) continue
        val released = log.releases.any { it.resource == g.resource && it.at in g.at..at0 }
        if (!released) out += v(AiInvariant.LEASES, at, "un'unita' prenotata da due dispositivi insieme (Anna dal ${g.at} al ${g.until}, Bruno dal $at0; rilasci di Anna: ${log.releases.count { it.resource == g.resource }})")
      }
    }
    return out
  }

  /** A fine giro, con la rete sana e dopo le tappe di ripresa: invarianti 8, 9, 10 . */
  fun end(at: AiCheckPoint): List<AiViolation> {
    val out = ArrayList<AiViolation>(cheap(at))
    val a = w.a
    val nas = w.nas
    val reading = w.plan.reading
    fun missing(share: String, key: ContentKey, device: Boolean): List<String> {
      if (key in w.unsupportedKeys) return emptyList()
      val gaps = ArrayList<String>()
      if (!nas.doneIn(share, key, recipe.version, AiClass.VEC)) gaps += "vec"
      if (device && !nas.doneIn(share, key, recipe.ocrVersion, AiClass.OCR)) gaps += "ocr"
      return gaps
    }
    for (share in listOf(w.annaPrivate, w.common)) {
      for (key in nas.of(share).media.keys) {
        val gaps = missing(share, key, reading)
        if (gaps.isNotEmpty()) out += v(AiInvariant.REACHES_END, at, "a rete sana manca ${gaps.joinToString(" e ")} di un contenuto di una share")
      }
    }
    for (key in nas.of(w.brunoPrivate).media.keys) {
      if (missing(w.brunoPrivate, key, device = false).isNotEmpty()) out += v(AiInvariant.REACHES_END, at, "a rete sana manca vec di un contenuto di Bruno")
    }
    for (key in w.phoneKeys) {
      if (!a.index.hasRow(key, AiClass.VEC)) out += v(AiInvariant.REACHES_END, at, "media del solo telefono senza vettore")
      if (reading && !a.index.hasRow(key, AiClass.OCR)) out += v(AiInvariant.REACHES_END, at, "media del solo telefono senza testo")
    }
    // "Nessun errore permanente per un guasto passeggero": solo le anteprime davvero rotte sono "non supportate".
    for ((key, cls) in a.unsupported) {
      if (key !in w.unsupportedKeys) out += v(AiInvariant.REACHES_END, at, "un media segnato non supportato (${cls.wire}) per un guasto passeggero")
    }
    // 9: niente temporanei orfani, niente di gia' fatto rimasto nel buffer, i conti tornano.
    val files = w.bufferPath.listFiles().orEmpty()
    files.filter { it.name.endsWith(".tmp") }.forEach { out += v(AiInvariant.BUFFER, at, "temporaneo orfano nel buffer a lavoro finito") }
    if (a.buffer.keys().isNotEmpty()) {
      // AI-D1 corretto da P11: non e' piu' un difetto noto, e' un errore vero.
      out += v(AiInvariant.BUFFER, at, "${a.buffer.keys().size} contenuti gia' fatti ancora nel buffer")
    }
    if (files.sumOf { it.length() } != a.buffer.usedBytes.value) out += v(AiInvariant.BUFFER, at, "il buffer dice ${a.buffer.usedBytes.value} byte, sul disco ce ne sono ${files.sumOf { it.length() }}")
    out += hidden(at)
    return out
  }

  /** Invariante 10: i nascosti si calcolano e una ricerca di riferimento (dati pubblicati + bandierine del catalogo) non ne mostra. */
  private fun hidden(at: AiCheckPoint): List<AiViolation> {
    val out = ArrayList<AiViolation>()
    for (key in w.hidden - w.unsupportedKeys) {
      for (share in w.nas.shares.values.filter { key in it.media }) {
        if (share.name == w.brunoPrivate) continue
        if (!w.nas.doneIn(share.name, key, recipe.version, AiClass.VEC)) out += v(AiInvariant.HIDDEN, at, "un nascosto non e' stato calcolato (E-H1)")
      }
    }
    // La ricerca di riferimento: tutto cio' che e' pubblicato, escluse le bandierine del catalogo.
    val visible = ArrayList<ContentKey>()
    for (share in w.nas.shares.values) {
      val flags = share.media
      for (heads in share.heads.values) for (k in heads.keys) if (k.second == AiClass.VEC && flags[k.first]?.hidden == false) visible += k.first
    }
    w.stats.searches++
    visible.distinct().filter { it in w.hidden }.forEach { _ -> out += v(AiInvariant.HIDDEN, at, "un nascosto e' uscito nei risultati di una ricerca di riferimento") }
    w.stats.hiddenComputed += w.hidden.size
    return out
  }

  @Suppress("unused")
  private fun key(share: String) = share.lowercase(Locale.ROOT)
}
