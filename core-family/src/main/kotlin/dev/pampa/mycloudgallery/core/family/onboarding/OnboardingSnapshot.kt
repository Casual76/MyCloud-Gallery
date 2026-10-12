package dev.pampa.mycloudgallery.core.family.onboarding

import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.PreviewQuality
import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.rules.Preset

/**
 * Il NAS come l'onboarding lo ha trovato al passo 2.
 *
 * @property guid il GUID della NEGOTIATE, memorizzato prima di mandare qualsiasi password (D3, E-R7).
 */
data class NasFacts(val ip: String, val name: String?, val port: Int, val guid: String) {

  fun address(): NasAddress = NasAddress(ip, name, port)

  /** Mai l'indirizzo ne' il GUID nei log (§4.2). */
  override fun toString(): String = "NasFacts(port=$port)"

  companion object {
    fun of(address: NasAddress, guid: String): NasFacts = NasFacts(address.ip, address.name, address.port, guid)
  }
}

/**
 * Le scelte dell'utente ai passi locali. Si salvano con l'avanzamento e si consegnano a chi le applica
 * ([OnboardingChoicesSink]); il preset e la qualita' le applica l'onboarding stesso a `RuleSettingsStore`.
 */
data class OnboardingChoices(
  val camera: Boolean = true,
  val screenshots: Boolean = true,
  val indexer: Boolean = false,
  val preparer: Boolean = false,
  val preset: Preset = Preset.NIGHT,
  val quality: PreviewQuality = PreviewQuality.STANDARD,
  val remoteControl: Boolean = false,
  val tunnelName: String? = null,
  val externalBackupDone: Boolean = false,
  val routerChecklistDone: Boolean = false,
)

/**
 * Fin dove e' arrivato il passo 12 (e' l'unico passo in piu' tempi). Si salva solo da [KIT_VERIFIED]: prima,
 * il codice di recupero sta soltanto in memoria (e sul foglio dell'utente) e un'interruzione ricomincia da (a)
 * con una chiave e un codice nuovi.
 */
enum class FamilyKeyStage {
  /** (d) fatto: il kit e' sul NAS e si e' riaperto con il codice digitato. Si riprende da (e). */
  KIT_VERIFIED,

  /** (e) fatto: il registro di fondazione e' sul NAS e riletto con l'ancora. */
  FOUNDED,
}

/** La famiglia fondata (o in fondazione) da questo dispositivo. [keyPublic] e' la chiave pubblica nella forma di `core-trust`. */
data class FoundationRecord(val stage: FamilyKeyStage, val familyId: String, val keyPublic: String) {
  override fun toString(): String = "FoundationRecord($stage)"
}

/**
 * L'ingresso di un dispositivo (QR o manuale) a buon punto.
 *
 * @property registered la richiesta di registrazione e' sul NAS (da qui il dispositivo e' "in attesa").
 * @property requestedAt ora del NAS della richiesta (per `SetupState.AwaitingApproval.since`).
 */
data class JoinRecord(
  val familyId: String,
  val manual: Boolean,
  val minRegistryVersion: Long,
  val registered: Boolean,
  val requestedAt: Long?,
) {
  override fun toString(): String = "JoinRecord(manual=$manual, registered=$registered)"
}

/**
 * Tutto cio' che l'onboarding ricorda tra un passo e l'altro (E-O9): l'avanzamento, le scelte, e dove e'
 * arrivato ogni percorso. Si salva ad **ogni** passo (DataStore in `noBackupFilesDir`) e **non contiene mai
 * segreti**: ne' password, ne' codice di recupero, ne' chiavi (stanno nel Keystore). Resta dopo la fine
 * dell'onboarding perche' i passi saltati ("Da completare") si riprendono da li'.
 *
 * @property finished l'utente ha premuto "Vai alla Libreria" con tutto l'essenziale a posto.
 * @property resuming si sta riprendendo un passo saltato: finito quello si passa al prossimo da completare, non al seguente.
 * @property deferrable E-O1: l'ultimo tentativo al passo 2 non ha trovato il NAS, e "Continua piu' tardi" e' ammesso.
 * @property homeConfirmed il Wi-Fi che l'utente ha confermato come "casa" (passo 2, o ingresso manuale).
 */
data class OnboardingSnapshot(
  val progress: OnboardingProgress,
  val finished: Boolean = false,
  val finishedAt: Long? = null,
  val resuming: Boolean = false,
  val deferrable: Boolean = false,
  val homeConfirmed: HomeCandidate? = null,
  val nas: NasFacts? = null,
  val nasUser: String? = null,
  val commonShare: String? = null,
  val privateShare: String? = null,
  val excluded: Set<String> = emptySet(),
  val choices: OnboardingChoices = OnboardingChoices(),
  val foundation: FoundationRecord? = null,
  val join: JoinRecord? = null,
) {
  val path: OnboardingPath? get() = progress.path

  /** Mai utente, indirizzi, nomi di share o percorsi in un log (§4.2): solo dove si e'. */
  override fun toString(): String = "OnboardingSnapshot(path=$path, current=${progress.current}, finished=$finished)"

  companion object {
    val EMPTY = OnboardingSnapshot(OnboardingProgress.START)
  }
}
