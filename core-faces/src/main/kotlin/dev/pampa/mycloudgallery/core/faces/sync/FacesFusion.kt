package dev.pampa.mycloudgallery.core.faces.sync

import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.PeopleKey
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.SnapshotMark
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState

// LA FUSIONE DEI CONTRIBUTI DEI DISPOSITIVI DELL'ACCOUNT (Fase 8, P3; docs/fase8-progetto.md §2.4 "Fusione dei rilevamenti", "Voci people",
// D21). Tre regole, ognuna un ORDINE TOTALE sui valori della stessa chiave, quindi una fusione commutativa, associativa e idempotente (lo prova
// `FacesFusionPropertyTest` su 10.000 storie di 4 dispositivi): qualunque ordine di arrivo e qualunque raggruppamento danno lo stesso stato
// su ogni dispositivo.
// - corpi `faces`, per chiave di contenuto: la ricetta piu' alta, poi il dispositivo minore, poi l'impronta minore (`FaceBody.winner`);
// - voci `people`, per chiave: l'ora del NAS piu' alta, poi l'autore minore, poi il valore (`PeopleRecord.winner`, come `PersonalMerge`);
// - istantanee: la `seq` piu' alta, poi il dispositivo minore (D21), poi la testa piu' nuova e l'impronta minore (una sola scelta anche se
//   uno stesso custode avesse pubblicato due volte la stessa `seq`).
// Le voci `people` si giudicano prima voce per voce ([PeopleRules]): un autore che non e' un dispositivo dell'account, o che e' revocato o
// perso, o un'ora troppo nel futuro, scartano la voce e non il segmento.

/** Un'istantanea candidata (D21): chi l'ha pubblicata, il suo segno e la testa che la porta. */
data class SnapshotCandidate(val device: String, val mark: SnapshotMark, val headVersion: Long, val headHash: String) {
  override fun toString(): String = "SnapshotCandidate(seq=${mark.seq}, v$headVersion)"
}

/** Il verdetto su una voce `people` letta: valida, o perche' no (codici stabili del resoconto). */
enum class PeopleVerdict(val code: String) {
  VALID("valid"),

  /** L'autore non e' un dispositivo approvato o dismesso dell'account (un altro account, uno sconosciuto). */
  AUTHOR_NOT_ALLOWED("people_author_not_allowed"),

  /** L'autore e' revocato o perso: nessuna sua voce vale, nemmeno ripubblicata da un dispositivo fidato (formato §4.6). */
  AUTHOR_REVOKED("people_author_revoked"),

  /** Ora oltre 2 minuti nel futuro rispetto all'ora del NAS: la voce aspetta (potrebbe valere al giro dopo). */
  FROM_FUTURE("people_from_future"),
}

/** Chi puo' essere l'autore di una voce `people` (D12, D13): lo dice il registro accettato, mai un dato letto dal NAS. */
class PeopleRules(registry: RegistryState, account: String, val nasNow: Long) {
  val allowed: Set<String> = FacesSyncGuard.accountDevices(registry, account).toSet()
  val barred: Set<String> = FacesSyncGuard.barred(registry)

  fun verdict(author: String, at: Long): PeopleVerdict = when {
    author in barred -> PeopleVerdict.AUTHOR_REVOKED
    author !in allowed -> PeopleVerdict.AUTHOR_NOT_ALLOWED
    at > nasNow + TrustLimits.MAX_FUTURE_SKEW_MILLIS -> PeopleVerdict.FROM_FUTURE
    else -> PeopleVerdict.VALID
  }

  override fun toString(): String = "PeopleRules(${allowed.size} autori, ${barred.size} esclusi)"
}

/** Lo stato fuso di uno o piu' contributi: voci `people` (una per chiave), corpi vincenti (uno per contenuto), l'istantanea vincente. */
class FusedFaces(
  val people: Map<PeopleKey, PeopleRecord>,
  val bodies: Map<ContentKey, FaceBody>,
  val snapshot: SnapshotCandidate?,
) {
  override fun toString(): String = "FusedFaces(${people.size} voci, ${bodies.size} corpi, istantanea=${snapshot != null})"

  companion object {
    val EMPTY = FusedFaces(emptyMap(), emptyMap(), null)
  }
}

object FacesFusion {

  /** L'istantanea vincente fra due (D21): `seq` piu' alta, poi il dispositivo minore, poi la testa piu' nuova, poi l'impronta minore. */
  fun better(a: SnapshotCandidate, b: SnapshotCandidate): SnapshotCandidate = when {
    a.mark.seq != b.mark.seq -> if (a.mark.seq > b.mark.seq) a else b
    a.device != b.device -> if (a.device < b.device) a else b
    a.headVersion != b.headVersion -> if (a.headVersion > b.headVersion) a else b
    a.headHash != b.headHash -> if (a.headHash < b.headHash) a else b
    // Stesso dispositivo, stessa testa: la base non cambia la scelta (la porta la testa stessa).
    else -> if (a.mark.base <= b.mark.base) a else b
  }

  fun winner(candidates: Iterable<SnapshotCandidate>): SnapshotCandidate? = candidates.reduceOrNull(::better)

  /** Il corpo vincente per ogni contenuto fra [a] e [b] (`FaceBody.winner`). */
  fun bodies(a: Map<ContentKey, FaceBody>, b: Map<ContentKey, FaceBody>): Map<ContentKey, FaceBody> {
    val out = HashMap(a)
    for ((key, body) in b) out[key] = out[key]?.let { FaceBody.winner(it, body) } ?: body
    return out
  }

  /** La fusione di due stati: per ogni chiave il vincitore delle tre regole. Commutativa, associativa, idempotente. */
  fun join(a: FusedFaces, b: FusedFaces): FusedFaces = FusedFaces(
    PeopleRecord.merge(a.people, b.people.values),
    bodies(a.bodies, b.bodies),
    winner(listOfNotNull(a.snapshot, b.snapshot)),
  )

  /** Lo stato di un solo contributo (le voci gia' giudicate valide). */
  fun of(people: Collection<PeopleRecord>, bodies: Collection<FaceBody>, snapshot: SnapshotCandidate?): FusedFaces = FusedFaces(
    PeopleRecord.merge(emptyMap(), people),
    // Piu' corpi dello stesso contenuto nello stesso contributo: vince il migliore anche qui.
    bodies.groupBy { it.contentKey }.mapValues { (_, list) -> list.reduce(FaceBody::winner) },
    snapshot,
  )
}
