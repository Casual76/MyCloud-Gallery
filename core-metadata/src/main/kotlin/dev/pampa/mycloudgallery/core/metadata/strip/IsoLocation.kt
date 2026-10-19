package dev.pampa.mycloudgallery.core.metadata.strip

// Fase 5B, P11 (docs/fase5-progetto.md D26, E-X5, L41; docs/decisioni.md "Fase 5B: P0-B": lo scrittore dei video usa IsoBmff e non l'Mp4Scout
// di core-prepare, che core-metadata non vede): le scatole della POSIZIONE di un MP4 o MOV. Si camminano i riquadri senza leggere i dati
// (header da 16 byte, `mdat` saltati), si trovano quelli che portano una posizione e li si **rinomina in `free` azzerandone il contenuto**:
// stessa dimensione, nessuna ricodifica, nessun offset che cambia. Gli stessi riquadri, in un file gia' ripulito, non compaiono piu': e'
// il ricontrollo.
//
// Dove sta una posizione in un MP4/MOV:
//  - `moov/udta/©xyz` (Android, Samsung, Pixel: stringa ISO 6709), `loci` (3GPP) e qualche `gps ` di videocamere;
//  - `moov/meta` di QuickTime (iPhone): la tabella `keys` con `com.apple.quicktime.location.*` e le voci di `ilst` che puntano a quelle chiavi;
//  - l'XMP in un riquadro `uuid` (BE7ACFCB-97A9-42E8-9C71-999491E3AFAC) o `XMP_`;
//  - tracce di metadati a tempo con i punti GPS (`gpmd` di GoPro, `camm` di Google): **non si possono togliere** senza ricodificare il
//    contenitore, quindi la condivisione si ferma (si fallisce chiusi).
// Il lavoro e' un PIANO (cosa rinominare, cosa riscrivere sul posto, cosa blocca) calcolato dai soli header; poi lo si applica. Il piano di un
// file ripulito e' vuoto: lo stesso codice serve per cercare e per ricontrollare, e i test dimostrano ogni rilevamento su fixture diverse.

internal object IsoLocation {

  /** Un riquadro: tipo (un carattere per byte), posizione assoluta, intestazione (8 o 16) e fine. */
  class Box(val type: String, val start: Long, val headerSize: Int, val end: Long) {
    val contentStart: Long get() = start + headerSize
    val contentLength: Long get() = end - contentStart
  }

  /** Cio' che il piano fa a un file. */
  sealed interface Op {
    /** Il riquadro diventa `free` e il suo contenuto zeri: stessa dimensione. */
    class Neutralize(val box: Box) : Op

    /** Byte riscritti sul posto (stessa lunghezza): il nome di una chiave, un pacchetto XMP ripulito. */
    class Overwrite(val at: Long, val bytes: ByteArray) : Op
  }

  /** Perche' non si puo' togliere (o non si sa se c'e'): la condivisione si ferma. */
  enum class Blocker(val code: String) {
    /** Una traccia di metadati a tempo con punti GPS. */
    LOCATION_TRACK("location_track"),

    /** Troppi riquadri, troppo annidati o un contenuto troppo grande per esaminarlo in sicurezza: nel dubbio, mai. */
    TOO_COMPLEX("too_complex"),

    /** Un pacchetto XMP con proprieta' GPS che non si sa riscrivere sul posto (UTF-16). */
    XMP_UNSUPPORTED("xmp_unsupported"),
  }

  class Plan {
    val ops: MutableList<Op> = ArrayList()
    val blockers: MutableSet<Blocker> = LinkedHashSet()

    /** Niente da togliere e niente che blocchi: il file non ha (piu') una posizione in questi riquadri. */
    val isClean: Boolean get() = ops.isEmpty() && blockers.isEmpty()
  }

  /** I riquadri che per definizione portano una posizione. */
  private val LOCATION_TYPES: Set<String> = setOf("©xyz", "loci", "gps ", "GPS ", "©gps", "©GPS", "gpsa", "©loc")

  /** I formati di campione delle tracce che portano punti GPS. */
  private val LOCATION_SAMPLE_ENTRIES: Set<String> = setOf("camm", "gpmd", "gps ", "GPS ", "nmea")

  /** L'UUID dell'XMP nei riquadri `uuid`. */
  private val XMP_UUID: ByteArray = byteArrayOf(
    0xBE.toByte(), 0x7A, 0xCF.toByte(), 0xCB.toByte(), 0x97.toByte(), 0xA9.toByte(), 0x42, 0xE8.toByte(),
    0x9C.toByte(), 0x71, 0x99.toByte(), 0x94.toByte(), 0x91.toByte(), 0xE3.toByte(), 0xAF.toByte(), 0xAC.toByte(),
  )

  private const val MAX_DEPTH = 8
  private const val MAX_BOXES_PER_LEVEL = 4096
  private const val MAX_BODY = 4 * 1024 * 1024
  private const val MAX_NEUTRALIZED = 64L * 1024 * 1024
  private const val FREE = "free"

  /** Il piano per la regione `[from, to)` di un file (tutto il file, o la parte video di una motion photo). */
  fun plan(file: RawFile, from: Long, to: Long): Plan {
    val plan = Plan()
    walk(file, from, minOf(to, file.length), 0, plan, topLevel = true)
    return plan
  }

  /** Applica il piano: rinomina in `free` e azzera, riscrive sul posto. Nessun byte cambia posto, la lunghezza e' la stessa. */
  fun apply(file: RawFile, plan: Plan) {
    for (op in plan.ops) {
      when (op) {
        is Op.Neutralize -> {
          file.write(op.box.start + 4, FREE.toByteArray(Charsets.ISO_8859_1))
          file.zero(op.box.contentStart, op.box.contentLength)
        }
        is Op.Overwrite -> file.write(op.at, op.bytes)
      }
    }
  }

  // --- La camminata ----------------------------------------------------------------------------------------------------------

  private fun walk(file: RawFile, from: Long, to: Long, depth: Int, plan: Plan, topLevel: Boolean = false) {
    if (depth > MAX_DEPTH) {
      plan.blockers += Blocker.TOO_COMPLEX
      return
    }
    var pos = from
    var count = 0
    while (pos + 8 <= to) {
      if (++count > MAX_BOXES_PER_LEVEL) {
        plan.blockers += Blocker.TOO_COMPLEX
        return
      }
      val box = readBox(file, pos, to)
      if (box == null) {
        // In fondo a un file una coda che non e' un riquadro (il trailer di Samsung, riempimento) non e' un errore: ma non deve nascondere un XMP.
        if (topLevel) checkTrailer(file, pos, to, plan)
        return
      }
      visit(file, box, depth, plan)
      pos = box.end
    }
  }

  private fun visit(file: RawFile, box: Box, depth: Int, plan: Plan) {
    when {
      box.type in LOCATION_TYPES -> neutralize(box, plan)
      box.type == "moov" || box.type == "udta" -> walk(file, box.contentStart, box.end, depth + 1, plan)
      box.type == "trak" -> visitTrack(file, box, depth, plan)
      box.type == "meta" -> visitMeta(file, box, depth, plan)
      box.type == "uuid" -> visitUuid(file, box, plan)
      box.type == "XMP_" -> scrubXmp(file, box.contentStart, box.contentLength, plan)
    }
  }

  private fun neutralize(box: Box, plan: Plan) {
    if (box.contentLength > MAX_NEUTRALIZED) plan.blockers += Blocker.TOO_COMPLEX else plan.ops += Op.Neutralize(box)
  }

  private fun visitUuid(file: RawFile, box: Box, plan: Plan) {
    if (box.contentLength < XMP_UUID.size) return
    val id = file.read(box.contentStart, XMP_UUID.size)
    if (!id.contentEquals(XMP_UUID)) return
    scrubXmp(file, box.contentStart + XMP_UUID.size, box.contentLength - XMP_UUID.size, plan)
  }

  private fun scrubXmp(file: RawFile, at: Long, length: Long, plan: Plan) {
    if (length <= 0) return
    if (length > MAX_BODY) {
      plan.blockers += Blocker.TOO_COMPLEX
      return
    }
    val packet = file.read(at, length.toInt())
    val result = XmpGpsScrub.scrub(packet)
    when {
      result.unsupported -> plan.blockers += Blocker.XMP_UNSUPPORTED
      result.removed > 0 -> plan.ops += Op.Overwrite(at, result.bytes)
    }
  }

  /** Una traccia: se il suo formato di campione porta punti GPS non si toglie; `udta` e `meta` della traccia si visitano come gli altri. */
  private fun visitTrack(file: RawFile, track: Box, depth: Int, plan: Plan) {
    if (depth + 1 > MAX_DEPTH) {
      plan.blockers += Blocker.TOO_COMPLEX
      return
    }
    for (child in children(file, track.contentStart, track.end, plan)) {
      when (child.type) {
        "udta", "meta" -> visit(file, child, depth + 1, plan)
        "mdia" -> inspectMedia(file, child, plan)
      }
    }
  }

  private fun inspectMedia(file: RawFile, media: Box, plan: Plan) {
    val minf = children(file, media.contentStart, media.end, plan).firstOrNull { it.type == "minf" } ?: return
    val stbl = children(file, minf.contentStart, minf.end, plan).firstOrNull { it.type == "stbl" } ?: return
    val stsd = children(file, stbl.contentStart, stbl.end, plan).firstOrNull { it.type == "stsd" } ?: return
    // stsd: versione e flag (4), numero di voci (4), poi la prima voce: lunghezza (4) e formato (4).
    if (stsd.contentLength < 16) return
    val entry = file.read(stsd.contentStart + 8, 8)
    if (entry.size < 8) return
    if (entry.fourcc(4) in LOCATION_SAMPLE_ENTRIES) plan.blockers += Blocker.LOCATION_TRACK
  }

  /**
   * `meta`: in QuickTime e' un contenitore senza versione, in ISO un `FullBox` (4 byte di versione e flag prima dei figli). Dentro cerca le
   * chiavi di posizione (`keys`) e le voci di `ilst` che le richiamano, oltre ai riquadri di posizione veri e propri.
   */
  private fun visitMeta(file: RawFile, meta: Box, depth: Int, plan: Plan) {
    if (depth + 1 > MAX_DEPTH) {
      plan.blockers += Blocker.TOO_COMPLEX
      return
    }
    if (meta.contentLength < 8) return
    val head = file.read(meta.contentStart, 8)
    val quickTime = head.size >= 8 && head.u32be(0) >= 8 && isFourcc(head, 4)
    val childrenStart = meta.contentStart + if (quickTime) 0 else 4
    val kids = children(file, childrenStart, meta.end, plan)
    val locationIndexes = HashSet<Long>()
    for (kid in kids) if (kid.type == "keys") collectLocationKeys(file, kid, locationIndexes, plan)
    for (kid in kids) {
      when {
        kid.type in LOCATION_TYPES -> neutralize(kid, plan)
        kid.type == "ilst" -> {
          for (item in children(file, kid.contentStart, kid.end, plan)) {
            val head4 = file.read(item.start + 4, 4)
            val index = if (head4.size == 4) head4.u32be(0) else -1L
            if (item.type in LOCATION_TYPES || index in locationIndexes) neutralize(item, plan)
          }
        }
        kid.type == "uuid" -> visitUuid(file, kid, plan)
        kid.type == "XMP_" -> scrubXmp(file, kid.contentStart, kid.contentLength, plan)
      }
    }
  }

  /** La tabella `keys` di QuickTime: versione/flag (4), numero (4), poi voci di lunghezza (4), spazio dei nomi (4) e nome. */
  private fun collectLocationKeys(file: RawFile, keys: Box, indexes: MutableSet<Long>, plan: Plan) {
    if (keys.contentLength < 8) return
    if (keys.contentLength > MAX_BODY) {
      plan.blockers += Blocker.TOO_COMPLEX
      return
    }
    val body = file.read(keys.contentStart, keys.contentLength.toInt())
    if (body.size < 8) return
    val count = body.u32be(4)
    var p = 8
    var index = 1L
    while (index <= count && p + 8 <= body.size) {
      val size = body.u32be(p)
      if (size < 8 || p + size > body.size) break
      val nameStart = p + 8
      val nameLength = (size - 8).toInt()
      val name = String(body, nameStart, nameLength, Charsets.ISO_8859_1).lowercase()
      if (name.contains("location") || name.contains("gps")) {
        indexes += index
        // Il nome della chiave diventa un nome neutro della stessa lunghezza: nessun resto di "location" nel file.
        plan.ops += Op.Overwrite(keys.contentStart + nameStart, ByteArray(nameLength) { 'x'.code.toByte() })
      }
      p += size.toInt()
      index++
    }
  }

  /** In fondo a un file: un pacchetto XMP dentro una coda che non e' un riquadro si cerca lo stesso (in testo, entro un tetto). */
  private fun checkTrailer(file: RawFile, from: Long, to: Long, plan: Plan) {
    val length = to - from
    if (length <= 0) return
    if (length > MAX_BODY) return
    val tail = file.read(from, length.toInt())
    if (XmpGpsScrub.hasGps(tail)) plan.blockers += Blocker.XMP_UNSUPPORTED
  }

  // --- Riquadri --------------------------------------------------------------------------------------------------------------

  /** I figli di `[from, to)`, al piu' [MAX_BOXES_PER_LEVEL]; un riquadro che non torna ferma la lista. */
  private fun children(file: RawFile, from: Long, to: Long, plan: Plan): List<Box> {
    val out = ArrayList<Box>()
    var pos = from
    while (pos + 8 <= to) {
      if (out.size >= MAX_BOXES_PER_LEVEL) {
        plan.blockers += Blocker.TOO_COMPLEX
        break
      }
      val box = readBox(file, pos, to) ?: break
      out += box
      pos = box.end
    }
    return out
  }

  /** L'intestazione di un riquadro a [pos] dentro `[.., to)`; null se non torna (troppo corta, dimensione sbagliata, esce dal contenitore). */
  fun readBox(file: RawFile, pos: Long, to: Long): Box? {
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
    return Box(type, pos, header, pos + size)
  }

  /** Quattro byte che sembrano un tipo di riquadro: lettere, cifre, spazio, punteggiatura o il `©` di QuickTime. */
  private fun isFourcc(b: ByteArray, at: Int): Boolean {
    if (at + 4 > b.size) return false
    for (i in 0 until 4) {
      val c = b.u8(at + i)
      if (!(c in 0x20..0x7E || c == 0xA9)) return false
    }
    return true
  }

  /** I tipi di riquadro che mancano a un video che si riproduce: serve alla verifica che un file ripulito abbia ancora `moov`. */
  fun hasMovie(file: RawFile, from: Long, to: Long): Boolean {
    var pos = from
    var count = 0
    while (pos + 8 <= to && count++ < MAX_BOXES_PER_LEVEL) {
      val box = readBox(file, pos, to) ?: return false
      if (box.type == "moov" || box.type == "moof") return true
      pos = box.end
    }
    return false
  }
}
