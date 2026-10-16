package dev.pampa.mycloudgallery.core.model

/**
 * I flag di una cella della griglia, in una maschera di bit: e' cio' che i badge e i filtri leggono
 * dall'indice della timeline senza unire versioni, metadati e dati personali (D2, D15).
 *
 * La maschera e' un **campo derivato** di `media` (`cell_flags`), calcolato da
 * [dev.pampa.mycloudgallery.core.model.domain.TimelineRules.cellFlags] e riscritto dal catalogo nella
 * stessa transazione che cambia cio' da cui dipende. Finisce anche nell'istantanea su file della
 * timeline: i **bit sono stabili** come i codici di [StableCode] (si aggiungono, non si cambiano, non si
 * riusano; `CellFlagsTest`).
 */
object CellFlags {

  /** La versione corrente non e' l'originale (modifica o correzione): badge "modificata". */
  const val EDITED: Int = 1 shl 0

  /** La data di scatto e' una stima (nome del file, data del file): "stimata" (E-M8). */
  const val DATE_ESTIMATED: Int = 1 shl 1

  /** META ha rinunciato (file corrotto, E-M10): "non leggibile", mai bloccante. */
  const val UNREADABLE: Int = 1 shl 2

  /** Ha un luogo (intestazioni con i luoghi, info). */
  const val HAS_PLACE: Int = 1 shl 3

  /** Motion photo: si anima tenendo premuto. */
  const val MOTION: Int = 1 shl 4

  /** Panorama: la griglia giustificata lo tiene largo. */
  const val PANORAMA: Int = 1 shl 5

  /** Ultra HDR: il viewer passa allo spazio colore HDR. */
  const val ULTRA_HDR: Int = 1 shl 6

  /** Preferito (dato personale, copiato qui solo per la cella). */
  const val FAVORITE: Int = 1 shl 7

  /**
   * Membro di un gruppo (raffica, RAW+JPEG) che **non** lo rappresenta: fuori dall'indice, la griglia
   * mostra solo la copertina (D20).
   */
  const val GROUP_MEMBER: Int = 1 shl 8

  /** Rappresenta un gruppo nell'indice (la copertina, o chi la sostituisce se non e' visibile). */
  const val GROUP_COVER: Int = 1 shl 9

  // --- Fase 4: presenza (D18, `docs/fase4-progetto.md` §1.3). Bit fissati da P0, calcolati da P2 (TimelineRules) ----

  /** Ha almeno una posizione **presente** sul telefono. */
  const val ON_PHONE: Int = 1 shl 10

  /** Ha almeno una posizione **presente** sul NAS (area privata o comune, con l'account di questo dispositivo). */
  const val ON_NAS: Int = 1 shl 11

  /** "Al sicuro": copia nell'area privata riletta e verificata qui (`SafetyRules`, D8: mai da dati remoti). */
  const val SAFE: Int = 1 shl 12

  /**
   * "Da verificare" (E-L9, D21): copertina di un gruppo `PROBABLE_DUPLICATE` (stesso video su telefono e NAS con
   * dimensione e `qh1` uguali, hash completo non ancora calcolato). Non e' nei tre bit del §1.3: senza, il badge
   * [PresenceBadge.TO_VERIFY] del §1.5 non si ricaverebbe dai soli flag della cella (scostamento di P0).
   */
  const val TO_VERIFY: Int = 1 shl 13

  // --- Fase 5: backup e copie simili (`docs/fase5-progetto.md` D15, D36). Bit fissati da P0, calcolati da P1 e P15 ------------

  /**
   * Un errore del backup che chiede l'utente (D15): illeggibile, verifica fallita tre volte (E-B18), sparito prima del backup
   * (E-B4), copia privata non piu' integra (E-B8). Derivato dal catalogo nella stessa transazione che registra il problema (P1).
   * "In coda", "in caricamento" e "in verifica" **non** sono bit: cambierebbero a ogni file e farebbero ricostruire l'indice della
   * timeline migliaia di volte durante il primo backup (D15).
   */
  const val BACKUP_PROBLEM: Int = 1 shl 14

  /**
   * La cella e' la **copertina di un gruppo di copie simili** (D36, `MediaGroupKind.SIMILAR_COPIES`): lo stesso scatto con byte
   * diversi mostrato una volta sola, con il segno "copie" e il numero (`groupSizeAt`). Con [GROUP_COVER] lo dice anche una raffica:
   * questo bit e' cio' che distingue le due pile senza una colonna in piu' nell'istantanea (la cella oggi sa di essere una raffica
   * solo da [GROUP_COVER] + dimensione del gruppo, e il "da verificare" di `PROBABLE_DUPLICATE` ha gia' un bit suo, [TO_VERIFY]).
   */
  const val SIMILAR_COPIES: Int = 1 shl 15

  /** Tutti i flag con il loro nome, per i test di stabilita' e la Diagnostica (solo numeri). */
  val ALL: Map<String, Int> = linkedMapOf(
    "EDITED" to EDITED,
    "DATE_ESTIMATED" to DATE_ESTIMATED,
    "UNREADABLE" to UNREADABLE,
    "HAS_PLACE" to HAS_PLACE,
    "MOTION" to MOTION,
    "PANORAMA" to PANORAMA,
    "ULTRA_HDR" to ULTRA_HDR,
    "FAVORITE" to FAVORITE,
    "GROUP_MEMBER" to GROUP_MEMBER,
    "GROUP_COVER" to GROUP_COVER,
    "ON_PHONE" to ON_PHONE,
    "ON_NAS" to ON_NAS,
    "SAFE" to SAFE,
    "TO_VERIFY" to TO_VERIFY,
    "BACKUP_PROBLEM" to BACKUP_PROBLEM,
    "SIMILAR_COPIES" to SIMILAR_COPIES,
  )

  fun has(flags: Int, flag: Int): Boolean = flags and flag == flag
}

/**
 * Il badge di presenza di una cella (`docs/fase4-progetto.md` §1.5): **cosa** dire, ricavato dai soli [CellFlags].
 * **Come** dirlo (icona, colore, posizione) e' del filone grafico (`GalleryBadge`).
 *
 * Uno solo per cella, in quest'ordine: [PROBLEM] (Fase 5, D15: un problema del backup chiede l'utente e non si nasconde mai
 * dietro "al sicuro"), [SAFE] (la cosa che conta di piu'), [TO_VERIFY], [BOTH], [PHONE_ONLY], [NAS_ONLY]. `null` quando la
 * cella non ha bit di presenza (libreria sintetica, o prima che P2 li calcoli).
 */
enum class PresenceBadge {
  PHONE_ONLY,
  NAS_ONLY,
  BOTH,
  SAFE,
  TO_VERIFY,

  /** Fase 5 (D15, §1.5 di `docs/fase5-progetto.md`): [CellFlags.BACKUP_PROBLEM]. Il glifo e' del catalogo G2 (P5). */
  PROBLEM,
  ;

  companion object {
    fun of(flags: Int): PresenceBadge? {
      val phone = CellFlags.has(flags, CellFlags.ON_PHONE)
      val nas = CellFlags.has(flags, CellFlags.ON_NAS)
      return when {
        CellFlags.has(flags, CellFlags.BACKUP_PROBLEM) -> PROBLEM
        CellFlags.has(flags, CellFlags.SAFE) -> SAFE
        CellFlags.has(flags, CellFlags.TO_VERIFY) -> TO_VERIFY
        phone && nas -> BOTH
        phone -> PHONE_ONLY
        nas -> NAS_ONLY
        else -> null
      }
    }
  }
}
