package dev.pampa.mycloudgallery.core.nas.contract

import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration

/** Le fasi del test di connessione, nell'ordine in cui si eseguono (§2.6, §3.1). */
enum class Stage {
  /** Il cancello di rete consente il percorso? */
  ROUTE,
  TCP,

  /** Dialetto 3.1.1 con cifratura offerta, scarto dell'orologio. */
  NEGOTIATE,

  /** Il GUID del NAS e' quello memorizzato (D3). */
  GUID,

  /** Saltata se l'interruttore e' aperto: il test non autentica mai con l'interruttore aperto. */
  AUTHENTICATE,

  /** Cifratura effettiva, niente guest ne' anonimo, firma richiesta. */
  SECURITY_CHECK,
  SHARE,
  READ,
}

enum class StageOutcome { PASSED, FAILED, SKIPPED, NOT_RUN }

data class StageResult(
  val stage: Stage,
  val outcome: StageOutcome,
  val elapsed: Duration,
  val error: NasError? = null,
)

/**
 * Cosa ha detto la NEGOTIATE (senza autenticare): dialetto, cifratura offerta, GUID, scarto d'orologio. E'
 * anche cio' che l'onboarding memorizza al passo 2.
 */
data class NegotiationFacts(
  val dialect: SmbDialect,
  val encryptionOffered: Boolean,
  val serverGuid: String,
  val timeOffsetMs: Long,
)

data class ConnectionReport(
  val stages: List<StageResult>,
  val negotiation: NegotiationFacts?,
) {
  /** La prima fase fallita, se c'e'. */
  val firstFailure: StageResult? get() = stages.firstOrNull { it.outcome == StageOutcome.FAILED }

  val passed: Boolean get() = stages.isNotEmpty() && stages.all { it.outcome == StageOutcome.PASSED || it.outcome == StageOutcome.SKIPPED }
}

/**
 * Cosa provare.
 *
 * @property candidate durante l'onboarding: il Wi-Fi da cui si e' letto il candidato a "casa" (percorso
 *   dedicato, [dev.pampa.mycloudgallery.core.network.contract.NasRouteGate.onboardingRoute]). Null = percorso corrente.
 * @property expectedGuid il GUID da confrontare; null al primo contatto (lo si legge e lo si memorizza).
 */
data class TestTarget(
  val address: NasAddress,
  val user: String?,
  val share: String?,
  val expectedGuid: String?,
  val candidate: HomeCandidate? = null,
)

/**
 * Il test di connessione per fasi (Diagnostica e onboarding): percorso, TCP, NEGOTIATE, GUID,
 * autenticazione, controllo di sicurezza, share, lettura. Con [credentials] nulle si ferma prima
 * dell'autenticazione (e non autentica mai con l'interruttore aperto).
 */
interface ConnectionTester {
  suspend fun run(target: TestTarget, credentials: NasCredentials?, stages: Set<Stage> = Stage.entries.toSet()): ConnectionReport
}

/** Cosa si puo' fare in una share (passo 4, E-O2, E-O3). */
enum class ShareAccess { MISSING, DENIED, READ_ONLY, READ_WRITE }

/**
 * La prova di una share: esiste, si legge, si scrive. La scrittura si verifica aprendo la cartella con
 * `FILE_ADD_FILE`, **senza creare file**. Gli errori di rete arrivano come [NasResult.Err] (non come
 * `MISSING`: una share irraggiungibile non e' una share assente).
 */
interface ShareProbe {
  suspend fun probe(share: String): NasResult<ShareAccess>
}

/** Una voce di una cartella del NAS (sola lettura). */
data class DirEntry(val name: String, val isDirectory: Boolean, val size: Long, val modifiedAt: Long)

/** Una pagina di elenco; [truncated] se c'erano piu' voci del limite. */
data class DirPage(val entries: List<DirEntry>, val truncated: Boolean)

/** Sfoglia una share in sola lettura, a pagine (`Directory.iterator`). [dir] null = radice. */
interface NasBrowser {
  suspend fun list(dir: SharePath?, limit: Int): NasResult<DirPage>
}

/**
 * Pulizia dei **propri** temporanei rimasti a meta' (`.mycloudgallery/tmp/<deviceId>-*`). Nient'altro:
 * nessun dato letto dal NAS puo' far cancellare file altrui (E-S11).
 */
interface NasHousekeeping {
  suspend fun purgeOwnStaleTemps(deviceId: String, olderThan: Duration): NasResult<Int>
}
