package dev.pampa.mycloudgallery.core.model

// Fase 8 (`docs/fase8-progetto.md` §1.2, §2.4, D3, D21, D23): come si nomina un volto e la scelta dell'account. Puri: li usano il
// formato (P3), il raggruppamento (P5), le persone (P9), le schermate (feature-people, che non vede core-trust) e la voce `faces` dei
// dati personali (core-personal, P7). Nessun valore nei log (D37).

/**
 * Un volto per **posto** (D23): contenuto, fotogramma e riquadro. Le correzioni dell'utente ("E' …", "Non e' …", "Ignora", la
 * copertina) parlano di posti, non di indici: sopravvivono a un ricalcolo con un'altra ricetta (indici diversi, stessi posti). Un volto
 * attuale **corrisponde** a un localizzatore se ha lo stesso contenuto e lo stesso fotogramma e i riquadri si sovrappongono con
 * IoU >= [MATCH_IOU] ([matches]).
 *
 * Nei file (voci `people`, §2.4) e' il testo [encode]: `<chiave di contenuto in base32>:<fotogramma>:<x0>.<y0>.<x1>.<y1>`, numeri in
 * decimale senza zeri davanti, al piu' [MAX_CHARS] caratteri; [parse] accetta solo quella forma (una sola scrittura per ogni posto).
 */
data class FaceLocator(val contentKey: ContentKey, val frame: Int, val box: FaceBox) {
  init {
    require(frame in 0..MAX_FRAME) { "Fotogramma fuori intervallo." }
  }

  /** Lo stesso volto, anche se ricalcolato: stesso contenuto, stesso fotogramma, riquadri con IoU >= [MATCH_IOU]. */
  fun matches(contentKey: ContentKey, frame: Int, box: FaceBox): Boolean =
    contentKey == this.contentKey && frame == this.frame && box.iou(this.box) >= MATCH_IOU

  fun matches(other: FaceLocator): Boolean = matches(other.contentKey, other.frame, other.box)

  fun encode(): String = "${FaceBase32.encode(contentKey.bytes())}:$frame:${box.x0}.${box.y0}.${box.x1}.${box.y1}"

  /** D37: mai un posto (chiave di contenuto, riquadro) nei log. */
  override fun toString(): String = "FaceLocator"

  companion object {
    /** D23: la soglia dell'abbinamento fra un localizzatore e un volto ricalcolato. */
    const val MATCH_IOU: Double = 0.5

    /** I fotogrammi: 0 per le foto, 0..8 l'indice nel blob `frames` (copertina + 8). */
    const val MAX_FRAME: Int = 8

    const val MAX_CHARS: Int = 128

    private const val KEY_BYTES = 32
    private val NUMBER = Regex("^(0|[1-9][0-9]{0,4})$")

    fun parse(text: String): FaceLocator? {
      if (text.length > MAX_CHARS) return null
      val parts = text.split(':')
      if (parts.size != 3) return null
      val key = FaceBase32.decodeExact(parts[0], KEY_BYTES) ?: return null
      val frame = parts[1].takeIf { NUMBER.matches(it) }?.toInt() ?: return null
      val coords = parts[2].split('.')
      if (coords.size != 4 || coords.any { !NUMBER.matches(it) }) return null
      val c = coords.map { it.toInt() }
      if (frame > MAX_FRAME || c.any { it > FaceBox.MAX } || c[0] >= c[2] || c[1] >= c[3]) return null
      val hex = key.joinToString("") { "%02x".format(it.toInt() and 0xff) }
      return FaceLocator(ContentKey(hex), frame, FaceBox(c[0], c[1], c[2], c[3]))
    }
  }
}

/**
 * Un volto per **indice** dentro un corpo `faces` (§2.4, riferimento a 42 byte delle istantanee): contenuto, fotogramma, indice del volto
 * nel corpo e le prime 8 byte dell'impronta del corpo usato ([bodyDigest]). Se il corpo vincente per quel contenuto cambia (ricetta piu'
 * alta, altro dispositivo, §2.4 "Fusione dei rilevamenti"), il riferimento non torna piu' e il volto e' "nuovo" per il custode (D21).
 */
data class FaceRef(val contentKey: ContentKey, val frame: Int, val index: Int, val bodyDigest: Long) : Comparable<FaceRef> {
  init {
    require(frame in 0..FaceLocator.MAX_FRAME) { "Fotogramma fuori intervallo." }
    require(index in 0 until MAX_FACES) { "Indice del volto fuori intervallo." }
  }

  /** L'ordine dei riferimenti (§2.5: "in ordine di riferimento"): chiave, fotogramma, indice, impronta del corpo. Deterministico ovunque. */
  override fun compareTo(other: FaceRef): Int {
    contentKey.hex.compareTo(other.contentKey.hex).let { if (it != 0) return it }
    frame.compareTo(other.frame).let { if (it != 0) return it }
    index.compareTo(other.index).let { if (it != 0) return it }
    return bodyDigest.compareTo(other.bodyDigest)
  }

  /** D37: mai un riferimento nei log. */
  override fun toString(): String = "FaceRef"

  companion object {
    /** Volti per corpo `faces` (§2.4, A6). */
    const val MAX_FACES: Int = 64
  }
}

/**
 * La scelta **dell'account** su Persone (D3), come la dice la voce `faces` dei dati personali (`SETTING`, campo `faces`): il codec e'
 * `FacesSettingCodec` di core-personal, la regola che la applica e' `FacesSwitch.reconcile` di core-faces. Voce assente = spenta (il
 * codec restituisce null). [Unknown] = una forma che questa versione non capisce (un'app piu' nuova su un altro dispositivo, E-F12):
 * niente calcolo, niente pubblicazione e **nessuna cancellazione**, "Aggiorna l'app".
 */
sealed interface FacesSetting {
  /** Attiva per l'account con la generazione [generation] e la versione [textVersion] del testo della pagina accettato. */
  data class On(val generation: FaceGeneration, val textVersion: Int) : FacesSetting {
    init {
      require(textVersion > 0) { "Versione del testo non valida." }
    }

    override fun toString(): String = "FacesSetting.On(v$textVersion)"
  }

  /** Spenta dopo "Disattiva e cancella" della generazione [generation] (D29): ogni dispositivo cancella i dati di quella generazione. */
  data class Off(val generation: FaceGeneration) : FacesSetting {
    override fun toString(): String = "FacesSetting.Off"
  }

  /** Forma sconosciuta: non si interpreta e non si tocca niente (E-F12). */
  data object Unknown : FacesSetting

  /** La generazione nominata dalla voce, se la voce ne nomina una. */
  val generationOrNull: FaceGeneration?
    get() = when (this) {
      is On -> generation
      is Off -> generation
      Unknown -> null
    }
}
