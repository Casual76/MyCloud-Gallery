package dev.pampa.mycloudgallery.feature.albums.contract

import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteBlock
import dev.pampa.mycloudgallery.core.albums.contract.ShareBlock
import dev.pampa.mycloudgallery.core.albums.contract.ShareExclusion
import dev.pampa.mycloudgallery.core.albums.contract.TitleProblem

// I MESSAGGI DELLE SCHERMATE (Fase 7, P10): cio' che una schermata dice quando un'azione non e' andata come l'utente si aspettava. Sono dati
// (nessun testo): la schermata li traduce, i test li confrontano. Mai un titolo, un nome o un percorso dentro (D37).

/** Perche' una scrittura o una condivisione non si puo' fare adesso (il foglio e i messaggi lo dicono con una frase sola per motivo). */
enum class WhyBlocked {
  /** D30: gli album condivisi, sulle cartelle vere, arrivano dopo le prove ("Arriva dopo le prove"). */
  AFTER_TESTS,

  /** L'area e' in sola lettura per questo account (E-SH19). */
  READ_ONLY,

  /** Le scritture sul NAS sono ferme (congelamento, "Ferma le scritture", salto dell'ora, dispositivo non fidato): riprendono da sole. */
  WRITES_PAUSED,

  /** Nessun NAS o nessuna area comune. */
  NO_NAS,

  /** L'album supera i 20.000 elementi (il foglio lo dice: non si puo' condividere cosi' grande). */
  TOO_MANY_ITEMS,

  /** L'album non e' piu' attivo (chiuso, eliminato, o non c'e'). */
  NOT_ACTIVE,
  ;

  companion object {
    fun of(block: ShareBlock): WhyBlocked = when (block) {
      ShareBlock.TEST_SHARES_ONLY -> AFTER_TESTS
      ShareBlock.WRITES_BLOCKED -> WRITES_PAUSED
      ShareBlock.READ_ONLY -> READ_ONLY
      ShareBlock.NO_NAS -> NO_NAS
      ShareBlock.TOO_MANY_ITEMS -> TOO_MANY_ITEMS
      ShareBlock.ALBUM_NOT_ACTIVE -> NOT_ACTIVE
    }

    fun of(block: AlbumWriteBlock): WhyBlocked = when (block) {
      AlbumWriteBlock.TEST_SHARES_ONLY, AlbumWriteBlock.FENCE -> AFTER_TESTS
      AlbumWriteBlock.READ_ONLY -> READ_ONLY
      AlbumWriteBlock.NO_NAS -> NO_NAS
      AlbumWriteBlock.FROZEN, AlbumWriteBlock.EMERGENCY_STOP, AlbumWriteBlock.CLOCK_JUMP, AlbumWriteBlock.NOT_TRUSTED -> WRITES_PAUSED
    }
  }
}

/** Un messaggio sotto l'intestazione di una pagina, finche' l'utente non lo chiude. */
sealed interface AlbumMessage {
  /** Il titolo non va bene (vuoto, lungo, con caratteri non ammessi); null = un argomento che non ha senso per l'album. */
  data class InvalidTitle(val problem: TitleProblem?) : AlbumMessage

  /** La tabella dei permessi non lo permette a me, a questo livello. */
  data object NotAllowed : AlbumMessage

  data class Blocked(val why: WhyBlocked) : AlbumMessage

  /** L'album (o l'elemento) non c'e' piu'. */
  data object NotFound : AlbumMessage

  /** Nessuno dei media si puo' aggiungere: [excluded] dice perche', per motivo. */
  data class NothingToAdd(val excluded: Map<ShareExclusion, Int>) : AlbumMessage

  /** Una regola di uno smart non sta in una voce dei dati personali: l'editor chiede di restringerla. */
  data object RulesTooLong : AlbumMessage

  /** Un'azione di scelta (copertina, esclusione) non ha trovato l'elemento nell'album. */
  data object NotInAlbum : AlbumMessage
}
