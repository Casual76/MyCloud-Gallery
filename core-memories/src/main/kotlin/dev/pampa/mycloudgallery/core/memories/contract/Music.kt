package dev.pampa.mycloudgallery.core.memories.contract

import dev.pampa.mycloudgallery.core.model.StableCode
import kotlinx.coroutines.flow.StateFlow

// LA MUSICA DELLA PRESENTAZIONE (docs/fase9-progetto.md D13; **risposta Q3 del 27/10**, §11). Contratto di P0, senza scaricare niente:
//  - di base **nessuna musica**: la presentazione parte muta;
//  - dalle Impostazioni si sceglie una **canzone propria** (un file del telefono, dal selettore di sistema `ACTION_OPEN_DOCUMENT` con il
//    permesso di lettura persistito: resta dov'e', mai copiata sul NAS, nessun permesso nuovo) **oppure** un brano del **pacchetto di musica
//    libera** (CC0 o pubblico dominio), l'opzione preferita dall'utente;
//  - il pacchetto si installa **a richiesta**, come i modelli della Fase 6: catalogo con **impronte fissate nell'app** ([MusicPackCatalog]),
//    si scarica una volta per la famiglia e poi si prende dal NAS, sullo **scaffale dei modelli** (verifica dell'impronta prima dell'uso).
//    L'unico codice HTTPS resta quello dei modelli (`core-models/.../download/https/`): nessuna terza eccezione di rete. **Le fonti e i brani
//    precisi si fanno approvare all'utente prima di scaricarli**: finche' non c'e' l'approvazione il catalogo e' vuoto e nessun host nuovo entra
//    in `ModelHosts`. Chi lo fa: il pacchetto della presentazione (P9) per la scelta e il lettore; il catalogo e l'host con la loro approvazione.

/** La musica scelta su questo dispositivo (preferenza locale, non sincronizzata: la canzone propria e' un file di questo telefono). */
sealed interface MusicChoice {
  data object None : MusicChoice

  /** Una canzone propria: [uriToken] e' il riferimento al documento con il permesso persistito (opaco, mai nei log). */
  data class OwnSong(val uriToken: String) : MusicChoice {
    init {
      require(uriToken.isNotEmpty() && uriToken.length <= MAX_TOKEN) { "Riferimento non valido." }
    }

    override fun toString(): String = "MusicChoice.OwnSong"

    companion object {
      const val MAX_TOKEN: Int = 2_048
    }
  }

  /** Un brano del pacchetto di musica libera ([MusicPackCatalog]). */
  data class FreeTrack(val trackId: String) : MusicChoice {
    override fun toString(): String = "MusicChoice.FreeTrack"
  }
}

/** La licenza di un brano libero: solo queste (Q3: "gratuita e senza diritti"). */
enum class MusicLicense(override val code: String) : StableCode {
  CC0("cc0"),
  PUBLIC_DOMAIN("public_domain"),
}

/** Un brano del pacchetto, **fissato nell'app** (come `ModelCatalog`): dimensione e SHA-256 esatti, licenza, titolo e autore per i crediti. */
data class MusicTrackPin(
  val id: String,
  val title: String,
  val author: String,
  val license: MusicLicense,
  val bytes: Long,
  val sha256: String,
) {
  init {
    require(Regex("^[a-z0-9_]{1,32}$").matches(id)) { "Id di brano non valido." }
    require(bytes in 1..MAX_BYTES) { "Dimensione non valida." }
    require(Regex("^[0-9a-f]{64}$").matches(sha256)) { "Impronta non valida." }
  }

  companion object {
    /** Un brano e' qualche MB: oltre questo tetto e' un errore del catalogo. */
    const val MAX_BYTES: Long = 20L * 1024 * 1024
  }
}

/**
 * Il catalogo del pacchetto di musica libera (Q3). **Vuoto** finche' l'utente non approva fonti e brani (§11): aggiungere un brano vuol dire
 * fissarne qui l'impronta, con l'approvazione scritta in `docs/decisioni.md` e la riga dell'host in `docs/sicurezza.md`.
 */
object MusicPackCatalog {
  const val VERSION: Int = 0

  val TRACKS: List<MusicTrackPin> = emptyList()

  fun byId(id: String): MusicTrackPin? = TRACKS.firstOrNull { it.id == id }
}

/** Lo stato di un brano del pacchetto su questo dispositivo. */
sealed interface MusicTrackState {
  data object NotInstalled : MusicTrackState

  data class Installing(val percent: Int) : MusicTrackState {
    init {
      require(percent in 0..100) { "Percentuale fuori intervallo." }
    }
  }

  data object Ready : MusicTrackState

  /** [code] un codice stabile (impronta sbagliata, spazio, rete): il testo e' della schermata. */
  data class Failed(val code: String) : MusicTrackState
}

/** Un audio pronto da suonare: un riferimento opaco per il lettore (Media3), mai nei log. */
class MusicSource(val token: String) {
  override fun toString(): String = "MusicSource"
}

/**
 * La musica della presentazione (Q3): i brani del pacchetto e il loro stato, l'installazione a richiesta (dal NAS se c'e', altrimenti
 * scaricata e verificata come un modello), l'apertura della scelta. Un brano che non c'e' piu' (file sparito, impronta sbagliata) = null: la
 * presentazione resta muta e lo dice (D13).
 */
interface MusicLibrary {
  val tracks: StateFlow<Map<String, MusicTrackState>>

  /** Avvia l'installazione del brano [trackId] (un tocco dell'utente). Falso se il brano non e' nel catalogo o non si puo' adesso. */
  suspend fun install(trackId: String): Boolean

  suspend fun open(choice: MusicChoice): MusicSource?
}

/** La musica scelta su questo dispositivo (P9, preferenza locale). */
interface MusicSettings {
  val choice: StateFlow<MusicChoice>

  suspend fun set(choice: MusicChoice)
}
