package dev.pampa.mycloudgallery.core.index.contract

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode

/**
 * I codici di Diagnostica dell'indice condiviso (sezione `NAS_LIBRARY`), fissati da P0 perche' li producono P3 e P4 e li
 * cataloga P11 (`feature-diagnostics`, un test per ogni edge case della 4A). L'`IndexDiagnostics : DiagnosticsContributor`
 * e' di P4. Solo numeri ed enumerati nell'esportazione: mai percorsi, share, account, id di media o di dispositivo.
 */
object IndexDiag {
  /** Segmenti o teste rifiutati, per motivo (E-I5, E-I14, F8). */
  val INDEX_REJECTED = DiagCode("INDEX_REJECTED", setOf("E-I5", "E-I14", "C9"))

  /** Una testa piu' vecchia di quella gia' accettata, o sparita (E-I5): possibile rollback. */
  val INDEX_ROLLBACK = DiagCode("INDEX_ROLLBACK", setOf("E-I5"))

  /** Dati scritti da una versione piu' nuova dell'app: "aggiorna l'app" (E-I3). */
  val INDEX_NEWER_FORMAT = DiagCode("INDEX_NEWER_FORMAT", setOf("E-I3"))

  /** Adozioni d'identita' rifiutate (D7) e convergenze al minore (E-I18). */
  val INDEX_IDENTITY_CONFLICT = DiagCode("INDEX_IDENTITY_CONFLICT", setOf("E-I18"))

  /** Outbox non vuota: NAS pieno o quota (E-I6), oppure pubblicazione bloccata. */
  val INDEX_OUTBOX_PENDING = DiagCode("INDEX_OUTBOX_PENDING", setOf("E-I6"))

  /** D31: pubblicazione solo sulle share di prova, qui si lavora in solo locale. */
  val INDEX_LOCAL_ONLY = DiagCode("INDEX_LOCAL_ONLY", setOf("D31"))

  /** D32, E-N17: salto dell'ora del NAS, prenotazioni e pulizie sospese. */
  val INDEX_CLOCK_JUMP = DiagCode("INDEX_CLOCK_JUMP", setOf("E-N17", "D32"))

  /** E-I19: area in sola lettura per questo account, si prepara solo in locale. */
  val INDEX_AREA_READ_ONLY = DiagCode("INDEX_AREA_READ_ONLY", setOf("E-I19"))

  /** E-I8, E-R10: share negata o sparita in due giorni diversi, dati tolti dalla replica. */
  val INDEX_SHARE_DROPPED = DiagCode("INDEX_SHARE_DROPPED", setOf("E-I8", "E-R10"))

  /** E-I10, E-I12: un'area senza Preparatori attivi. */
  val INDEX_NO_PREPARERS = DiagCode("INDEX_NO_PREPARERS", setOf("E-I10", "E-I12"))

  /** Stato della replica: livello, spazio, ultimo allineamento (E-L6). */
  val INDEX_REPLICA_STATE = DiagCode("INDEX_REPLICA_STATE", setOf("E-L6"))

  /** Prenotazioni contese: lavoro senza prenotazione (E-I1). */
  val INDEX_LEASES_CONTENDED = DiagCode("INDEX_LEASES_CONTENDED", setOf("E-I1"))

  val ALL: List<DiagCode> = listOf(
    INDEX_REJECTED, INDEX_ROLLBACK, INDEX_NEWER_FORMAT, INDEX_IDENTITY_CONFLICT, INDEX_OUTBOX_PENDING, INDEX_LOCAL_ONLY,
    INDEX_CLOCK_JUMP, INDEX_AREA_READ_ONLY, INDEX_SHARE_DROPPED, INDEX_NO_PREPARERS, INDEX_REPLICA_STATE, INDEX_LEASES_CONTENDED,
  )
}
