package dev.pampa.mycloudgallery.core.model

/** Perche' esiste una versione (piano §1 "Versione", 5.4). */
enum class VersionKind(override val code: String) : StableCode {
  /** La prima versione conosciuta del media. Una sola per media. */
  ORIGINAL("original"),

  /** Modificata fuori dall'app: Samsung Gallery che salva sopra, un editor esterno, il PC (E-E6). */
  EDITED("edited"),

  /** Correzione fatta dall'app (data, luogo, rotazione) e scritta nel file (E-L8). */
  CORRECTED("corrected"),
}

/** Che cosa corregge una versione [VersionKind.CORRECTED]. */
enum class CorrectionField(override val code: String) : StableCode {
  DATE("date"),
  LOCATION("location"),
  ROTATION("rotation"),
  ;

  /** Se cambia i pixel: data e luogo no (cambiano solo i metadati), la rotazione si'. */
  val changesPixels: Boolean get() = this == ROTATION
}

/**
 * Una versione di un media: byte precisi (la [fingerprint]) nati in un momento preciso.
 *
 * Le versioni formano una catena tramite [parentId]: l'originale non ha genitore, ogni modifica o
 * correzione nasce dalla versione che era visibile. In app si vede la versione *corrente* del media
 * (che di norma e' l'ultima, ma torna all'originale con "Ripristina originale", E-E2); le altre restano
 * dove sono, sul NAS, recuperabili.
 *
 * "In quale posizione si trova" non e' un campo della versione: e' la relazione inversa, ogni
 * [MediaLocation] dice quale versione contiene. Cosi' una versione puo' stare in piu' posti (telefono
 * e NAS) e un posto passa a un'altra versione quando il file cambia.
 *
 * [createdAtMillis] e' quando la versione e' comparsa (data del file o ora di riferimento del NAS):
 * ordina le versioni e decide la corrente quando due catene si uniscono.
 */
data class MediaVersion(
  val id: VersionId,
  val kind: VersionKind,
  val parentId: VersionId?,
  val fingerprint: Fingerprint,
  val createdAtMillis: Long,
  val corrections: Set<CorrectionField> = emptySet(),
) {

  init {
    require((kind == VersionKind.ORIGINAL) == (parentId == null)) {
      "Solo l'originale non ha una versione genitore."
    }
    require((kind == VersionKind.CORRECTED) == corrections.isNotEmpty()) {
      "Una correzione dichiara che cosa corregge, e solo una correzione lo fa."
    }
    require(parentId != id) { "Una versione non puo' essere genitore di se stessa." }
  }

  /** Se rispetto al genitore cambiano solo i metadati (data o luogo), non i pixel. */
  val isMetadataOnlyCorrection: Boolean
    get() = kind == VersionKind.CORRECTED && corrections.none { it.changesPixels }
}
