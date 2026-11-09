package dev.pampa.mycloudgallery.faces

import android.os.SystemClock
import dev.pampa.mycloudgallery.core.ai.compute.DeviceRoleView
import dev.pampa.mycloudgallery.core.ai.compute.IndexerAreas
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.faces.compute.FacesAreas
import dev.pampa.mycloudgallery.core.faces.contract.FacesClock
import dev.pampa.mycloudgallery.core.faces.contract.FacesPermit
import dev.pampa.mycloudgallery.core.faces.contract.FacesRequirements
import dev.pampa.mycloudgallery.core.faces.contract.FacesRunControl
import dev.pampa.mycloudgallery.core.faces.contract.FacesStop
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteGate
import dev.pampa.mycloudgallery.core.faces.contract.IndexerState
import dev.pampa.mycloudgallery.core.faces.contract.PrivateAreaState
import dev.pampa.mycloudgallery.core.faces.erase.FacesEraseIdentity
import dev.pampa.mycloudgallery.core.faces.sync.FacesSyncIdentity
import dev.pampa.mycloudgallery.core.index.contract.IndexWriteGate
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.nas.session.ShareGuard
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseStop
import dev.pampa.mycloudgallery.core.rules.RunScope
import dev.pampa.mycloudgallery.core.rules.run.StageHalt
import dev.pampa.mycloudgallery.core.rules.run.StageScope
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.nas.NasInputs
import java.util.Locale

// LE PORTE DI core-faces VERSO L'IDENTITA', GLI OROLOGI, I CANCELLI E I REQUISITI (Fase 8, P13; docs/fase8-progetto.md §1.4, D5, D30, D34-D36).
// Tutto dal registro firmato accettato, dalle scelte dell'onboarding e dagli orologi che il resto dell'app usa gia': niente di nuovo da sapere,
// niente dal NAS da solo. Mai nomi, id o generazioni nei log (D37): qui non si scrive nessun log.

/**
 * Chi sono per i dati dei volti: il dispositivo, la sua chiave del Keystore e il registro accettato. Lo stesso oggetto serve la sincronizzazione
 * ([FacesSyncIdentity]) e la cancellazione ([FacesEraseIdentity]). Senza chiave la famiglia non e' pronta: [registry] e' null e nessuno scrive.
 */
class AppFacesIdentity(private val inputs: NasInputs) : FacesSyncIdentity, FacesEraseIdentity {
  override val deviceId: String get() = inputs.deviceId() ?: NO_DEVICE

  override val signer: Signer get() = inputs.signer() ?: throw IllegalStateException("Nessuna chiave del dispositivo.")

  override fun registry(): RegistryState? = if (inputs.deviceId() == null || inputs.signer() == null) null else inputs.registry()

  override fun toString(): String = "AppFacesIdentity"

  private companion object {
    /** Un id ben formato che il registro non conosce mai (la famiglia non e' pronta: [registry] e' null e nessuno scrive). */
    val NO_DEVICE: String = Base32.encode(ByteArray(16))
  }
}

/**
 * Gli orologi dei volti (D36): l'orologio monotono che **conta anche il sonno** del telefono (`elapsedRealtimeNanos`: la freschezza di D30 non deve
 * restare "fresca" dopo ore di sonno) per durate, scadenze e conferme; l'ora del NAS per voci, teste e prenotazioni, **null** con il salto dell'ora
 * (come i dati personali) e senza ora del NAS ancora presa. Nessuna data del telefono.
 */
class AppFacesClock(
  private val inputs: NasInputs,
  private val clockBlocked: () -> Boolean,
  private val monotonic: () -> Long = SystemClock::elapsedRealtimeNanos,
) : FacesClock {
  override fun monotonicNanos(): Long = monotonic()

  override fun nasNowMillis(): Long? = if (clockBlocked()) null else inputs.nasClock.nowMillisOrNull()

  override fun toString(): String = "AppFacesClock"
}

/**
 * Il cancello delle scritture dei volti (§1.4, D34, D35): la recinzione D34 (`FeatureFlags.facesSyncMayUse`, accesa **anche in release** fino al
 * consenso Q5), la recinzione D33 delle prove sul campo (`ShareFence`, **solo debug**: [guard] null in release) e i cancelli delle teste dell'indice
 * ([index]: emergenza, fiducia, congelamento, salto dell'ora, sola lettura). Non quello di F4-D31 (l'indice "solo locale"): i dati dei volti hanno la
 * loro recinzione e il loro consenso. Senza NAS o senza account: [FacesWriteBlock.NO_NAS]. Vale per la pubblicazione, le prenotazioni e la
 * cancellazione sul NAS (con il congelamento questa **aspetta**, quella locale no).
 */
class AppFacesWriteGate(
  private val flags: FeatureFlags,
  private val guard: ShareGuard?,
  private val user: () -> String?,
  private val index: () -> IndexWriteGate?,
) : FacesWriteGate {
  override suspend fun blockFor(share: String): FacesWriteBlock? {
    if (!flags.facesSyncMayUse(share)) return FacesWriteBlock.TEST_SHARES_ONLY
    val account = user() ?: return FacesWriteBlock.NO_NAS
    if (guard != null && !guard.allows(account, share)) return FacesWriteBlock.FENCE
    val gate = index() ?: return FacesWriteBlock.NO_NAS
    return when (gate.blockFor(share)) {
      // F4-D31 (l'indice solo locale sulle share vere) non e' la recinzione dei volti: per loro decide D34, sopra.
      null, WriteBlock.LOCAL_ONLY -> null
      WriteBlock.FROZEN -> FacesWriteBlock.FROZEN
      WriteBlock.CLOCK_JUMP -> FacesWriteBlock.CLOCK_JUMP
      WriteBlock.READ_ONLY_AREA -> FacesWriteBlock.READ_ONLY
      WriteBlock.EMERGENCY_STOP -> FacesWriteBlock.EMERGENCY_STOP
      WriteBlock.NOT_TRUSTED -> FacesWriteBlock.NOT_TRUSTED
    }
  }

  override fun toString(): String = "AppFacesWriteGate"
}

/** Lo stesso cancello come lo vuole la cancellazione stretta di core-nasops (D35): ogni blocco ha il suo arresto dichiarato. */
fun FacesWriteGate.asEraseGate(): suspend (String) -> FacesEraseStop? = { share ->
  when (blockFor(share)) {
    null -> null
    FacesWriteBlock.FROZEN -> FacesEraseStop.FROZEN
    FacesWriteBlock.EMERGENCY_STOP -> FacesEraseStop.EMERGENCY_STOP
    FacesWriteBlock.CLOCK_JUMP -> FacesEraseStop.CLOCK_JUMP
    FacesWriteBlock.NOT_TRUSTED -> FacesEraseStop.NOT_TRUSTED
    FacesWriteBlock.READ_ONLY -> FacesEraseStop.READ_ONLY
    FacesWriteBlock.TEST_SHARES_ONLY, FacesWriteBlock.FENCE -> FacesEraseStop.TEST_SHARES_ONLY
    FacesWriteBlock.NO_PRIVATE_AREA -> FacesEraseStop.NOT_PRIVATE_AREA
    FacesWriteBlock.NO_NAS -> FacesEraseStop.UNREACHABLE
  }
}

/**
 * I requisiti dell'attivazione e la recinzione (D5, D34), dal registro e dalle scelte dell'onboarding. L'area privata dichiarata deve esistere ed
 * essere **diversa** dall'area comune (L23); gli indicizzatori dell'account sono quelli con il ruolo negli stati firmati degli ultimi 7 giorni
 * (F6-D14, [IndexerAreas.FRESH_MILLIS]) piu' questo dispositivo col ruolo di adesso. Un familiare non e' mai un indicizzatore dell'account.
 */
class AppFacesRequirements(
  private val inputs: NasInputs,
  private val flags: FeatureFlags,
  /** I ruoli dei dispositivi approvati (stati firmati) piu' questo dispositivo; null finche' gli stati non si sono letti. */
  private val roles: () -> List<DeviceRoleView>?,
  private val indexerHere: () -> Boolean,
  private val nasNow: () -> Long?,
) : FacesRequirements {

  override suspend fun privateArea(): PrivateAreaState {
    val private = inputs.shares().firstOrNull { it.area == NasArea.PRIVATE }?.share ?: return PrivateAreaState.Missing
    val common = commonShare()
    return if (common != null && common.equals(private, ignoreCase = true)) PrivateAreaState.SameAsCommon else PrivateAreaState.Declared(private)
  }

  override suspend fun indexers(): IndexerState {
    val account = (inputs.deviceId()?.let { id -> inputs.registry()?.registry?.device(id)?.nasUser } ?: inputs.account())?.lowercase(Locale.ROOT)
    val now = nasNow()
    val live = if (account == null || now == null) 0 else roles().orEmpty().count { view ->
      view.indexer && view.account.lowercase(Locale.ROOT) == account && now - view.lastSeenNasMillis in 0..IndexerAreas.FRESH_MILLIS
    }
    // `roles` conta gia' questo dispositivo col ruolo di adesso quando l'ora del NAS c'e'; senza, lo si somma qui (la scelta di attivare vale subito).
    val here = indexerHere()
    return IndexerState(accountIndexers = if (here && now == null) live + 1 else live, thisDevice = here, canBecomeIndexer = inputs.deviceId() != null)
  }

  override suspend fun localOnly(share: String): Boolean = !flags.facesSyncMayUse(share)

  private fun commonShare(): String? =
    inputs.registry()?.registry?.config?.commonShare ?: inputs.shares().firstOrNull { it.area == NasArea.COMMON }?.share

  override fun toString(): String = "AppFacesRequirements"
}

/** [FacesAreas]: la share dell'area comune dal registro accettato (D14, D16); l'area privata e' quella dichiarata di [FacesRequirements]. */
class AppFacesAreas(private val inputs: NasInputs) : FacesAreas {
  override suspend fun commonShare(): String? =
    inputs.registry()?.registry?.config?.commonShare ?: inputs.shares().firstOrNull { it.area == NasArea.COMMON }?.share
}

/**
 * Le regole di `AI_COMPUTE` come le vede il calcolo dei volti (E-A5, E-A17): legate alla tappa in corso del lavoro ([bind]); fuori da una tappa
 * (nessuna regola da chiedere) **non si lavora** ([FacesStop.RULES]), cosi' il calcolo non parte mai fuori dal protocollo delle tappe.
 */
class StageFacesControl : FacesRunControl {
  @Volatile private var current: StageScope<*>? = null

  /** Dentro la tappa [scope] le regole sono le sue; alla fine tornano a "ferme". */
  suspend fun <T> bind(scope: StageScope<*>, block: suspend () -> T): T {
    current = scope
    try {
      return block()
    } finally {
      current = null
    }
  }

  /**
   * CA-6: il NAS si puo' leggere adesso? Lo dicono le regole della tappa in corso: con `Run(LOCAL_BUFFER_ONLY)` (fuori casa, NAS spento, rete non fidata: E-A6) si
   * lavora solo su cio' che e' gia' in locale e **non si prova nemmeno a scaricare** (prima ogni media ritentava lo scaricamento e il motore vedeva "nessuna
   * anteprima": la pagina diceva «In attesa delle anteprime» invece di «In attesa del NAS»). Fuori da una tappa niente gira, quindi niente si scarica.
   */
  fun mayFetch(): Boolean = current?.let { it.runScope != RunScope.LOCAL_BUFFER_ONLY } ?: false

  override suspend fun permit(): FacesPermit {
    val scope = current ?: return FacesPermit.Stop(FacesStop.RULES)
    if (!scope.proceed()) return FacesPermit.Stop(if (scope.halt is StageHalt.Rules) FacesStop.RULES else FacesStop.BUDGET)
    // Piano col telefono caldo e (review generale, OB-3) con l'app in uso: lotti piccoli e pause.
    return FacesPermit.Go(reducedPace = scope.pace.slow)
  }
}
