package dev.pampa.mycloudgallery.core.metadata.strip

// Correzioni 5B (R1, docs/review/5b-fase5b.md): un MP4 o MOV (o la parte video di una motion photo) senza posizione, per **elenco di cio' che si
// tiene**. Prima si toglievano i riquadri della posizione che si conoscevano: una traccia di metadati con un formato fuori elenco (DJI `djmd`, Apple
// `mebx`, Sony `rtmd`, i sottotitoli di una dashcam) usciva com'era. Adesso:
//  - in cima al file restano `ftyp`, `moov` e `mdat`; `free`, `skip` e `wide` restano ma vuoti; ogni altro riquadro (`udta`, `meta`, `uuid`,
//    sconosciuti) diventa `free` con il contenuto azzerato, e cosi' la coda che non e' un riquadro;
//  - in `moov` restano `mvhd` e le sole tracce **audio e video** (`hdlr` = `soun` o `vide`); ogni altra traccia (metadati a tempo, sottotitoli,
//    timecode, `mebx`, `camm`, `gpmd`...) diventa `free` azzerata, e cosi' `udta`, `meta`, `iods` e ogni riquadro sconosciuto;
//  - dentro una traccia tenuta resta solo cio' che serve a riprodurla (`tkhd`, `edts/elst`, `mdia` con `mdhd`, `hdlr`, `minf` con l'intestazione,
//    `dinf/dref` e le tabelle dei campioni); il resto diventa `free` azzerato;
//  - in `mdat` restano **solo i byte dei campioni delle tracce tenute** (calcolati dalle loro tabelle `stsc`, `stsz`/`stz2`, `stco`/`co64`): tutto il
//    resto si azzera, cosi' non restano i campioni delle tracce tolte (le coordinate binarie di DJI, `camm`, GoPro) ne' i blocchi GPS che alcune
//    dashcam scrivono in `mdat` senza una traccia (Novatek).
// **Nessun byte cambia posto** (un riquadro tolto diventa `free` della stessa dimensione): gli indirizzi dei campioni restano validi e non si
// ricodifica niente. Se il file non si puo' ricostruire cosi' (niente `moov` o piu' di uno, nessuna traccia audio o video, un file frammentato,
// tabelle che puntano fuori da `mdat`, oltre i tetti) si risponde con il motivo e la condivisione si ferma: si fallisce chiusi.

/** Perche' un video non si ricostruisce senza posizione. Solo codici. */
internal enum class IsoStop(val code: String) {
  /** Nessun `moov` (registrazione interrotta), o piu' di uno. */
  NO_MOVIE("no_movie"),

  /** Nessuna traccia audio o video: dopo la pulizia non resterebbe un video. */
  NO_AV_TRACK("no_av_track"),

  /** Un MP4 frammentato (`moof`, `mvex`): i frammenti non si ricostruiscono qui. */
  FRAGMENTED("fragmented"),

  /** Una struttura che non torna: riquadri che escono dal contenitore, tabelle dei campioni rotte o che puntano fuori da `mdat`. */
  BROKEN("broken"),

  /** Oltre i tetti (riquadri, annidamento, campioni). */
  TOO_COMPLEX("too_complex"),
}

/** Un riquadro: tipo (un carattere per byte), inizio, intestazione (8 o 16) e fine. */
internal class IsoBox(val type: String, val start: Long, val headerSize: Int, val end: Long) {
  val contentStart: Long get() = start + headerSize
  val contentLength: Long get() = end - contentStart
}

internal object IsoClean {

  private const val MAX_BOXES = 100_000
  private const val MAX_DEPTH = 12
  private const val MAX_CHUNKS = 4_000_000
  private const val MAX_SAMPLES = 8_000_000
  private const val MAX_TABLE_BYTES = 64 * 1024 * 1024
  private const val FREE = "free"

  /** Cio' che resta in cima al file com'e'. */
  private val TOP_KEEP = setOf("ftyp", "moov", "mdat")

  /** Riempimento: resta, ma vuoto (una dashcam Novatek scrive la posizione dentro `free`). */
  private val PADDING = setOf("free", "skip", "wide")

  /** Un file frammentato: non si ricostruisce. */
  private val FRAGMENTS = setOf("moof", "mfra", "styp", "sidx", "ssix", "prft", "emsg", "mvex")

  /** Le sole tracce che restano. */
  private val AV_HANDLERS = setOf("vide", "soun")

  /** Cio' che resta dentro ogni riquadro di una traccia tenuta. I tipi che sono anche chiavi sono contenitori (si guarda dentro). */
  private val KEEP_INSIDE: Map<String, Set<String>> = mapOf(
    // `tapt` (QuickTime): le dimensioni di presentazione dell'immagine, solo numeri.
    "trak" to setOf("tkhd", "tapt", "edts", "mdia"),
    "edts" to setOf("elst"),
    "mdia" to setOf("mdhd", "hdlr", "minf"),
    "minf" to setOf("vmhd", "smhd", "hdlr", "dinf", "stbl"),
    "dinf" to setOf("dref"),
    "stbl" to setOf(
      "stsd", "stts", "ctts", "cslg", "stss", "stps", "sdtp", "stsc", "stsz", "stz2", "stco", "co64", "sgpd", "sbgp", "subs", "saiz", "saio",
      "stsh", "padb", "stdp",
    ),
  )

  private class Fail(val stop: IsoStop) : Exception(null, null, false, false)

  /** Cio' che si fara' al file: riquadri da rendere `free`, contenuti da azzerare. Si decide tutto prima di scrivere. */
  private class Plan {
    val neutralize = ArrayList<IsoBox>()
    val zero = ArrayList<LongArray>()
  }

  /**
   * Pulisce sul posto la regione `[from, to)` di [file] (tutto il file, o la parte video di una motion photo: gli indirizzi dei campioni contano
   * da [from]). Null = fatto; altrimenti il motivo, e il file non e' stato toccato.
   */
  fun clean(file: RawFile, from: Long, to: Long): IsoStop? = try {
    val plan = plan(file, from, minOf(to, file.length))
    apply(file, plan)
    null
  } catch (e: Fail) {
    e.stop
  }

  private fun plan(file: RawFile, from: Long, to: Long): Plan {
    val plan = Plan()
    val counter = IntArray(1)
    val top = children(file, from, to, counter, topLevel = true)
    val boxesEnd = top.lastOrNull()?.end ?: from
    if (top.any { it.type in FRAGMENTS }) throw Fail(IsoStop.FRAGMENTED)
    val moov = top.filter { it.type == "moov" }
    if (moov.size != 1) throw Fail(IsoStop.NO_MOVIE)
    val mdats = top.filter { it.type == "mdat" }
    for (box in top) {
      when {
        box.type in PADDING -> zero(plan, box.contentStart, box.end)
        box.type !in TOP_KEEP -> plan.neutralize += box
      }
    }
    // La coda che non e' un riquadro (un trailer, riempimento): si azzera.
    if (boxesEnd < to) zero(plan, boxesEnd, to)
    val ranges = movie(file, moov.single(), from, plan, counter)
    keepOnlySamples(mdats, ranges, plan)
    return plan
  }

  /** `moov`: `mvhd` e le tracce audio e video restano; tutto il resto diventa `free`. Restituisce i byte dei campioni delle tracce tenute. */
  private fun movie(file: RawFile, moov: IsoBox, base: Long, plan: Plan, counter: IntArray): List<LongArray> {
    val ranges = ArrayList<LongArray>()
    var kept = 0
    for (child in children(file, moov.contentStart, moov.end, counter)) {
      when (child.type) {
        "mvhd" -> Unit
        "mvex" -> throw Fail(IsoStop.FRAGMENTED)
        "trak" -> if (handlerOf(file, child, counter) in AV_HANDLERS) {
          val stbl = prune(file, child, plan, counter, depth = 1) ?: throw Fail(IsoStop.BROKEN)
          ranges += samples(file, stbl, base, counter)
          kept++
        } else {
          plan.neutralize += child
        }
        else -> plan.neutralize += child
      }
    }
    if (kept == 0) throw Fail(IsoStop.NO_AV_TRACK)
    return ranges
  }

  /** Dentro una traccia tenuta: cio' che non e' in [KEEP_INSIDE] diventa `free`. Restituisce lo `stbl` della traccia. */
  private fun prune(file: RawFile, box: IsoBox, plan: Plan, counter: IntArray, depth: Int): IsoBox? {
    if (depth > MAX_DEPTH) throw Fail(IsoStop.TOO_COMPLEX)
    val allowed = KEEP_INSIDE[box.type] ?: return null
    var stbl: IsoBox? = if (box.type == "stbl") box else null
    for (child in children(file, box.contentStart, box.end, counter)) {
      when {
        child.type !in allowed -> plan.neutralize += child
        child.type in KEEP_INSIDE -> prune(file, child, plan, counter, depth + 1)?.let { stbl = it }
      }
    }
    return stbl
  }

  /** Il tipo della traccia: `hdlr` dentro `mdia`, 8 byte dopo l'inizio del contenuto. Null se non c'e' (una traccia cosi' non resta). */
  private fun handlerOf(file: RawFile, trak: IsoBox, counter: IntArray): String? {
    val mdia = children(file, trak.contentStart, trak.end, counter).firstOrNull { it.type == "mdia" } ?: return null
    val hdlr = children(file, mdia.contentStart, mdia.end, counter).firstOrNull { it.type == "hdlr" } ?: return null
    if (hdlr.contentLength < 12) return null
    return file.read(hdlr.contentStart + 8, 4).fourcc(0)
  }

  // --- I campioni ------------------------------------------------------------------------------------------------------------------

  /**
   * I byte dei campioni di una traccia, chunk per chunk: `[inizio, fine)` assoluti nel file ([base] + l'indirizzo di `stco`/`co64`, + la somma delle
   * dimensioni dei campioni del chunk secondo `stsc` e `stsz`/`stz2`).
   */
  private fun samples(file: RawFile, stbl: IsoBox, base: Long, counter: IntArray): List<LongArray> {
    val kids = children(file, stbl.contentStart, stbl.end, counter)
    val stsc = table(file, kids.firstOrNull { it.type == "stsc" } ?: throw Fail(IsoStop.BROKEN))
    val sizes = sampleSizes(file, kids)
    val chunkBox = kids.firstOrNull { it.type == "stco" } ?: kids.firstOrNull { it.type == "co64" } ?: throw Fail(IsoStop.BROKEN)
    val offsets = chunkOffsets(table(file, chunkBox), chunkBox.type == "co64")
    // stsc: versione (4), numero (4), poi voci da 12 byte: primo chunk (da 1), campioni per chunk, descrizione.
    val runs = u32(stsc, 4)
    if (runs < 0 || 8 + runs * 12 > stsc.size) throw Fail(IsoStop.BROKEN)
    val out = ArrayList<LongArray>(offsets.size)
    var sample = 0L
    var run = 0
    for (chunk in 1..offsets.size) {
      while (run + 1 < runs && u32(stsc, 8 + (run + 1) * 12) <= chunk) run++
      val perChunk = if (runs == 0L) 0L else u32(stsc, 8 + run * 12 + 4)
      if (perChunk < 0 || perChunk > MAX_SAMPLES) throw Fail(IsoStop.BROKEN)
      var bytes = 0L
      for (k in 0 until perChunk) {
        if (sample >= sizes.count) throw Fail(IsoStop.BROKEN)
        bytes += sizes.at(sample)
        sample++
      }
      if (bytes > 0) {
        val start = base + offsets[chunk - 1]
        out += longArrayOf(start, start + bytes)
      }
    }
    return out
  }

  /** Le dimensioni dei campioni: `stsz` (una per tutti, o una tabella) o `stz2` (4, 8 o 16 bit). */
  private class Sizes(val count: Long, private val fixed: Long, private val table: ByteArray?, private val bits: Int) {
    fun at(i: Long): Long {
      if (table == null) return fixed
      return when (bits) {
        32 -> u32(table, 12 + (i * 4).toInt())
        16 -> ((table.u8(12 + (i * 2).toInt()) shl 8) or table.u8(12 + (i * 2).toInt() + 1)).toLong()
        8 -> table.u8(12 + i.toInt()).toLong()
        else -> {
          val b = table.u8(12 + (i / 2).toInt())
          (if (i % 2 == 0L) b shr 4 else b and 0x0F).toLong()
        }
      }
    }
  }

  private fun sampleSizes(file: RawFile, kids: List<IsoBox>): Sizes {
    kids.firstOrNull { it.type == "stsz" }?.let { box ->
      val t = table(file, box)
      if (t.size < 12) throw Fail(IsoStop.BROKEN)
      val fixed = u32(t, 4)
      val count = u32(t, 8)
      if (count > MAX_SAMPLES) throw Fail(IsoStop.TOO_COMPLEX)
      if (fixed != 0L) return Sizes(count, fixed, null, 0)
      if (12 + count * 4 > t.size) throw Fail(IsoStop.BROKEN)
      return Sizes(count, 0, t, 32)
    }
    kids.firstOrNull { it.type == "stz2" }?.let { box ->
      val t = table(file, box)
      if (t.size < 12) throw Fail(IsoStop.BROKEN)
      val bits = t.u8(7)
      val count = u32(t, 8)
      if (count > MAX_SAMPLES) throw Fail(IsoStop.TOO_COMPLEX)
      if (bits != 4 && bits != 8 && bits != 16) throw Fail(IsoStop.BROKEN)
      if (12 + (count * bits + 7) / 8 > t.size) throw Fail(IsoStop.BROKEN)
      return Sizes(count, 0, t, bits)
    }
    throw Fail(IsoStop.BROKEN)
  }

  /** stco/co64: versione (4), numero (4), poi gli indirizzi (4 o 8 byte). */
  private fun chunkOffsets(t: ByteArray, wide: Boolean): LongArray {
    if (t.size < 8) throw Fail(IsoStop.BROKEN)
    val count = u32(t, 4)
    if (count > MAX_CHUNKS) throw Fail(IsoStop.TOO_COMPLEX)
    val width = if (wide) 8 else 4
    if (8 + count * width > t.size) throw Fail(IsoStop.BROKEN)
    return LongArray(count.toInt()) { k -> if (wide) t.u64be(8 + k * 8) else u32(t, 8 + k * 4) }
  }

  private fun table(file: RawFile, box: IsoBox): ByteArray {
    if (box.contentLength > MAX_TABLE_BYTES) throw Fail(IsoStop.TOO_COMPLEX)
    val data = file.read(box.contentStart, box.contentLength.toInt())
    if (data.size.toLong() != box.contentLength) throw Fail(IsoStop.BROKEN)
    return data
  }

  /**
   * In ogni `mdat` restano solo i byte dei campioni delle tracce tenute: tutto il resto si azzera. Un campione che non sta dentro un `mdat` vuol
   * dire tabelle che non tornano (o un file costruito apposta): ci si ferma.
   */
  private fun keepOnlySamples(mdats: List<IsoBox>, ranges: List<LongArray>, plan: Plan) {
    val byMdat = mdats.associateWith { ArrayList<LongArray>() }
    for (range in ranges) {
      val home = mdats.firstOrNull { range[0] >= it.contentStart && range[1] <= it.end } ?: throw Fail(IsoStop.BROKEN)
      byMdat.getValue(home) += range
    }
    for ((mdat, inside) in byMdat) {
      inside.sortBy { it[0] }
      var cursor = mdat.contentStart
      for (range in inside) {
        if (range[0] > cursor) zero(plan, cursor, range[0])
        if (range[1] > cursor) cursor = range[1]
      }
      if (cursor < mdat.end) zero(plan, cursor, mdat.end)
    }
  }

  private fun zero(plan: Plan, from: Long, to: Long) {
    if (to > from) plan.zero += longArrayOf(from, to)
  }

  private fun apply(file: RawFile, plan: Plan) {
    for (box in plan.neutralize) {
      file.write(box.start + 4, FREE.toByteArray(Charsets.ISO_8859_1))
      file.zero(box.contentStart, box.contentLength)
    }
    for (range in plan.zero) file.zero(range[0], range[1] - range[0])
  }

  // --- Riquadri --------------------------------------------------------------------------------------------------------------------

  /**
   * I figli di `[from, to)`. Dentro un contenitore un riquadro che non torna e' una struttura rotta; in cima al file e' la fine dei riquadri
   * (una coda, che [plan] azzera).
   */
  private fun children(file: RawFile, from: Long, to: Long, counter: IntArray, topLevel: Boolean = false): List<IsoBox> {
    val out = ArrayList<IsoBox>()
    var pos = from
    while (pos + 8 <= to) {
      if (++counter[0] > MAX_BOXES) throw Fail(IsoStop.TOO_COMPLEX)
      val box = readBox(file, pos, to)
      if (box == null) {
        if (topLevel) break else throw Fail(IsoStop.BROKEN)
      }
      out += box
      pos = box.end
    }
    if (!topLevel && pos != to && to - pos >= 8) throw Fail(IsoStop.BROKEN)
    return out
  }

  /** L'intestazione di un riquadro a [pos] dentro `[.., to)`; null se non torna (troppo corta, dimensione sbagliata, esce dal contenitore). */
  fun readBox(file: RawFile, pos: Long, to: Long): IsoBox? {
    if (pos < 0 || pos + 8 > to) return null
    val h = file.read(pos, 16)
    if (h.size < 8) return null
    var size = h.u32be(0)
    val type = h.fourcc(4)
    var header = 8
    if (size == 1L) {
      if (h.size < 16 || pos + 16 > to) return null
      size = h.u64be(8)
      header = 16
    } else if (size == 0L) {
      size = to - pos
    }
    if (size < header || size > to - pos) return null
    return IsoBox(type, pos, header, pos + size)
  }

  private fun u32(b: ByteArray, i: Int): Long = if (i < 0 || i + 4 > b.size) -1L else b.u32be(i)
}

/** Una regione `[start, end)` di un file che e' un MP4 incorporato (la parte video di una motion photo). */
internal class IsoRegion(val start: Long, val end: Long)

internal object EmbeddedIso {

  private const val BLOCK = 1 shl 20
  private const val MAX_FTYP = 1024L

  /**
   * Il primo MP4 incorporato in `[from, to)`: un `ftyp` con un riquadro davanti di dimensione plausibile, seguito da riquadri che si susseguono
   * (almeno `moov` o `mdat`). Il video di una motion photo comincia cosi', qualunque sia il formato dichiarato dal produttore (Google, MicroVideo,
   * il trailer SEF di Samsung): non serve capire come lo annuncia.
   */
  fun first(file: RawFile, from: Long, to: Long): IsoRegion? {
    var base = from
    while (base < to) {
      val block = file.read(base, minOf(to - base, (BLOCK + 8).toLong()).toInt())
      if (block.size < 8) break
      var i = 4
      while (i + 4 <= block.size && i < BLOCK + 4) {
        if (block[i] == 'f'.code.toByte() && block[i + 1] == 't'.code.toByte() && block[i + 2] == 'y'.code.toByte() && block[i + 3] == 'p'.code.toByte()) {
          region(file, base + i - 4, to)?.let { return it }
        }
        i++
      }
      base += BLOCK
    }
    return null
  }

  private fun region(file: RawFile, start: Long, to: Long): IsoRegion? {
    val first = IsoClean.readBox(file, start, to) ?: return null
    if (first.type != "ftyp" || first.end - first.start !in 12..MAX_FTYP) return null
    var end = first.end
    var movie = false
    var count = 0
    while (end + 8 <= to && count++ < 4096) {
      val box = IsoClean.readBox(file, end, to) ?: break
      if (box.type == "moov" || box.type == "mdat" || box.type == "moof") movie = true
      end = box.end
    }
    return if (movie) IsoRegion(start, end) else null
  }
}
