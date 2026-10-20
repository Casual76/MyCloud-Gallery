package dev.pampa.mycloudgallery.feature.onboarding.model

import dev.pampa.mycloudgallery.core.family.contract.AreaCandidate
import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.family.contract.FamilyStateQr
import dev.pampa.mycloudgallery.core.family.contract.FolderProposal
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingWarning
import dev.pampa.mycloudgallery.core.family.contract.RecoveryKitRef
import dev.pampa.mycloudgallery.core.network.contract.ControlPermissionOutcome
import dev.pampa.mycloudgallery.core.network.contract.VpnCapability
import dev.pampa.mycloudgallery.core.network.contract.VpnCommandResult
import dev.pampa.mycloudgallery.core.qr.contract.ScanRejection
import dev.pampa.mycloudgallery.core.rules.platform.health.BackgroundHealth

// Lo stato che le schermate dell'onboarding mostrano. Le schermate sono senza stato proprio (tranne il
// testo che si sta digitando): ricevono uno di questi tipi e dei callback, cosi' ogni passo e ogni
// errore si disegna, si prova e si fotografa senza un ViewModel.

/** Dove sta l'utente: un passo, oppure una pagina dei percorsi "entra" e "recupera". */
internal sealed interface OnboardingDestination {

  /** Un numero che cresce lungo il cammino: serve a sapere se una transizione va avanti o indietro. */
  val order: Float

  /** Un passo dei 14. [sub] distingue le pagine dello stesso passo (le quattro della chiave, passo 12). */
  data class Step(val step: OnboardingStep, val sub: Int = 0) : OnboardingDestination {
    override val order: Float get() = step.number + sub * SUB_WEIGHT
  }

  /** Le pagine di "Entra con il QR" e "Entra senza QR": stanno fra l'indirizzo e l'account. */
  data class Join(val page: JoinPage) : OnboardingDestination {
    override val order: Float get() = JOIN_BASE + page.ordinal * SUB_WEIGHT
  }

  /** Le pagine di "Recupera l'admin" e "Secondo admin": dopo l'account. */
  data class Recovery(val page: RecoveryPage) : OnboardingDestination {
    override val order: Float get() = RECOVERY_BASE + page.ordinal * SUB_WEIGHT
  }

  /** Il dispositivo non fa piu' parte della famiglia (revocato, dismesso, stato perso): "Configura di nuovo" (ON-4). */
  data object Removed : OnboardingDestination {
    override val order: Float get() = 0f
  }

  private companion object {
    const val SUB_WEIGHT = 0.05f
    const val JOIN_BASE = 2.1f
    const val RECOVERY_BASE = 3.1f
  }
}

/** Un avviso di successo mostrato in testa al passo che segue (la conferma di cio' che e' appena andato bene). */
internal enum class OnboardingBanner(val step: OnboardingStep) {
  /** Il NAS e' stato verificato (cifratura, identita'): in testa al passo 3. */
  NAS_VERIFIED(OnboardingStep.ACCOUNT),

  /** La password e' nel deposito sicuro del telefono: in testa al passo 4. */
  CREDENTIALS_SAVED(OnboardingStep.AREAS),

  /** Chiave di famiglia e kit di recupero verificati: in testa al passo 13. */
  KIT_VERIFIED(OnboardingStep.SECURITY_NOTICE),
}

// --- Passo 2: indirizzo del NAS ---------------------------------------------------------------------

/** Cio' che il Wi-Fi attuale dice di se' (nessun dato personale: niente SSID, solo la forma della rete). */
internal data class HomeCandidateUi(
  val ipv4Subnet: String?,
  val gatewayV4: String?,
  val dnsDomain: String?,
  /** Con almeno un prefisso ULA si puo' confermare "casa"; senza no (E-S10, §2.1 passo 2). */
  val canConfirm: Boolean,
)

/** "E' la rete di casa?" */
internal sealed interface HomeUi {
  data object Loading : HomeUi

  /** Nessun Wi-Fi: solo dati mobili, o nessuna rete. */
  data object NoWifi : HomeUi

  data class Ask(val candidate: HomeCandidateUi) : HomeUi

  data class Confirmed(val candidate: HomeCandidateUi) : HomeUi

  /** L'utente ha detto "No, non e' casa". */
  data object NotHome : HomeUi
}

/** Un NAS annunciato in LAN. */
internal data class FoundNasUi(val name: String, val ip: String)

internal data class AddressUi(
  val home: HomeUi = HomeUi.Loading,
  val discovering: Boolean = false,
  val found: List<FoundNasUi> = emptyList(),
  /** La NEGOTIATE e il controllo del GUID sono in corso. */
  val checking: Boolean = false,
  val problem: OnboardingProblem? = null,
)

// --- Passo 3: account -------------------------------------------------------------------------------

internal data class AccountUi(
  /** L'unico tentativo e' in corso. */
  val submitting: Boolean = false,
  val problem: OnboardingProblem? = null,
  /** Un tentativo e' gia' fallito con l'interruttore aperto: la password va riscritta, il bottone resta spento finche' non c'e'. */
  val needsNewPassword: Boolean = false,
)

// --- Passo 4: aree ----------------------------------------------------------------------------------

internal data class AreasUi(
  val loading: Boolean = true,
  val candidates: List<AreaCandidate> = emptyList(),
  val submitting: Boolean = false,
  val problem: OnboardingProblem? = null,
)

// --- Passo 5: cartelle ------------------------------------------------------------------------------

internal data class FoldersUi(
  val loading: Boolean = true,
  val proposal: FolderProposal? = null,
  val submitting: Boolean = false,
  val problem: OnboardingProblem? = null,
)

// --- Passi 6-8: scelte locali -----------------------------------------------------------------------

internal data class LocalStepUi(
  val submitting: Boolean = false,
  val problem: OnboardingProblem? = null,
)

// --- Passo 10: batteria -----------------------------------------------------------------------------

internal data class BatteryUi(
  /** Quello che Android dice adesso; null mentre si legge. */
  val health: BackgroundHealth? = null,
  val submitting: Boolean = false,
  val problem: OnboardingProblem? = null,
)

// --- Passo 11: VPN ----------------------------------------------------------------------------------

/** La prova "accendi e spegni" osservata (§2.1 passo 11). */
internal sealed interface VpnTestUi {
  data object Idle : VpnTestUi

  /** [up] = true mentre si aspetta l'accensione, false mentre si aspetta lo spegnimento. */
  data class Running(val up: Boolean) : VpnTestUi

  data class Done(val up: VpnCommandResult, val down: VpnCommandResult?) : VpnTestUi
}

internal data class VpnUi(
  val capability: VpnCapability? = null,
  val test: VpnTestUi = VpnTestUi.Idle,
  val submitting: Boolean = false,
  val problem: OnboardingProblem? = null,
  /**
   * Com'e' finita l'ultima richiesta del permesso di WireGuard (verifica funzionale, RE-1): null = non ancora chiesto (o concesso). Un
   * rifiuto resta visibile con il modo di riprovare, il dialogo o la scheda dell'app.
   */
  val permission: ControlPermissionOutcome? = null,
)

// --- Passo 12: chiave di famiglia e kit -------------------------------------------------------------

/** Le pagine del passo 12: ognuna e' una pagina vera (transizione laterale), non un riquadro che cambia. */
internal enum class KeyPage { Intro, ShowCode, Retype, Verifying }

internal data class KeyUi(
  val page: KeyPage = KeyPage.Intro,
  /** Si sta generando la chiave (sulla pagina "Intro"). */
  val busy: Boolean = false,
  /** Il codice di recupero, in gruppi. Presente solo fra la generazione e la fine del passo. */
  val groups: List<String> = emptyList(),
  /** L'impronta della famiglia, da scrivere sul foglio con il codice (revisione 2, F3). */
  val fingerprint: String? = null,
  val problem: OnboardingProblem? = null,
)

// --- Passi 13-14 ------------------------------------------------------------------------------------

internal data class SummaryUi(
  val submitting: Boolean = false,
  val problem: OnboardingProblem? = null,
)

// --- Ingresso con invito ----------------------------------------------------------------------------

/** Le pagine di "Entra con il QR" e "Entra senza QR". */
internal enum class JoinPage { Scan, Manual, Summary, Password, Name, Awaiting, Approved }

internal data class JoinUi(
  val page: JoinPage = JoinPage.Scan,
  /** Un passo di rete o di scrittura e' in corso (controllo della casa e del NAS, password, richiesta). */
  val busy: Boolean = false,
  val invite: FamilyInvite? = null,
  /** Il motivo dell'ultimo QR rifiutato: sta sotto il mirino. */
  val rejection: ScanRejection? = null,
  val suggestedName: String = "",
  /** Il codice da far confrontare all'admin e il QR della chiave (`MCGK1:`), a richiesta pubblicata. */
  val code: String? = null,
  val deviceKeyQr: String? = null,
  /** Lo "stato della famiglia" scansionato (M7), se c'e': alza la versione minima del registro. */
  val familyState: FamilyStateQr? = null,
  /** Si sta scansionando lo "stato della famiglia" invece dell'invito. */
  val scanningFamilyState: Boolean = false,
  val problem: OnboardingProblem? = null,
  /** Dopo un errore di password: la password va riscritta (un solo tentativo, E-R8). */
  val needsNewPassword: Boolean = false,
  /**
   * Senza QR: "e' la rete di casa?" (ON-1), prima di contattare il NAS. Null dove non si chiede (con il QR, o rifacendo la
   * richiesta: la casa e' gia' confermata).
   */
  val home: HomeUi? = null,
)

// --- Recupero dell'admin e secondo admin ------------------------------------------------------------

/**
 * Le pagine del recupero, nell'ordine: kit, scelta sul vecchio admin (solo recupero), codice, esito. In fondo la guida
 * "ricominciare da zero", per chi non ha piu' ne' kit ne' codice (ON-6).
 */
internal enum class RecoveryPage { Kits, Fate, Code, Done, StartOver }

internal data class RecoveryUi(
  val page: RecoveryPage = RecoveryPage.Kits,
  /** Secondo admin: il primo resta approvato e la scelta sul vecchio admin non c'e'. */
  val secondAdmin: Boolean = false,
  val busy: Boolean = false,
  val kits: List<RecoveryKitRef> = emptyList(),
  val kitsLoaded: Boolean = false,
  /** L'area privata in cui cercare `recovery/`: di partenza quella con il nome dell'utente. */
  val privateShare: String = "",
  val selectedKit: Int = 0,
  val familyState: FamilyStateQr? = null,
  val scanningFamilyState: Boolean = false,
  val rejection: ScanRejection? = null,
  val problem: OnboardingProblem? = null,
  /** Il codice digitato non apre il kit: si puo' riprovare quante volte si vuole (e' offline). */
  val wrongCode: Boolean = false,
  /** Nessun kit nell'area privata (letta senza errori: un errore e' [problem], ON-9). */
  val noKits: Boolean = false,
  /** La guida "ricominciare da zero" e' aperta da questa pagina: "indietro" ci torna. */
  val startOverReturn: RecoveryPage = RecoveryPage.Kits,
  /** La cartella della famiglia sul NAS come la scrive Esplora file, se si conoscono indirizzo e area comune. */
  val startOverPath: String? = null,
)

// --- Dispositivo fuori dalla famiglia (ON-4) --------------------------------------------------------

/** Perche' il dispositivo non fa piu' parte della famiglia. */
internal enum class RemovalReason {
  /** L'admin l'ha revocato. */
  REVOKED,

  /** L'admin l'ha dismesso (non piu' in uso). */
  RETIRED,

  /** Lo stato della famiglia salvato non c'e' piu' o non si verifica. */
  OTHER,
}

internal data class RemovalUi(
  val reason: RemovalReason,
  /** "Configura di nuovo" e' in corso. */
  val busy: Boolean = false,
  val problem: OnboardingProblem? = null,
  /** Il foglio di conferma e' aperto (R7): "Configura di nuovo" distrugge chiavi, e non parte con un tocco solo. */
  val confirming: Boolean = false,
  /** Questo dispositivo tiene la chiave di famiglia (e' un admin): il foglio dice cosa si perde e come si recupera (R7). */
  val holdsKey: Boolean = false,
)

// --- Lo stato intero --------------------------------------------------------------------------------

internal data class OnboardingUiState(
  /** Falso in un profilo secondario del tablet (E-P19): la schermata di benvenuto lo spiega. */
  val isSystemUser: Boolean = true,
  /** Avvisi non bloccanti arrivati dai passi gia' fatti, ancora da mostrare. */
  val warnings: Set<OnboardingWarning> = emptySet(),
  val banner: OnboardingBanner? = null,
  /** Un'azione generica (scelta del percorso, salto, indietro) e' in corso. */
  val welcomeBusy: Boolean = false,
  val welcomeProblem: OnboardingProblem? = null,
  val address: AddressUi = AddressUi(),
  val account: AccountUi = AccountUi(),
  val areas: AreasUi = AreasUi(),
  val folders: FoldersUi = FoldersUi(),
  val backupSources: LocalStepUi = LocalStepUi(),
  val roles: LocalStepUi = LocalStepUi(),
  val preset: LocalStepUi = LocalStepUi(),
  val permissions: LocalStepUi = LocalStepUi(),
  val battery: BatteryUi = BatteryUi(),
  val vpn: VpnUi = VpnUi(),
  val key: KeyUi = KeyUi(),
  val securityNotice: LocalStepUi = LocalStepUi(),
  val summary: SummaryUi = SummaryUi(),
  val join: JoinUi? = null,
  val recovery: RecoveryUi? = null,
  /** Il dispositivo non fa piu' parte della famiglia (ON-4): al posto del vecchio riepilogo. */
  val removal: RemovalUi? = null,
)

/** Il problema che la pagina [destination] mostra adesso, se ce n'e' uno. */
internal fun OnboardingUiState.activeProblem(destination: OnboardingDestination): OnboardingProblem? = when (destination) {
  OnboardingDestination.Removed -> removal?.problem
  is OnboardingDestination.Join -> join?.problem
  is OnboardingDestination.Recovery -> recovery?.problem
  is OnboardingDestination.Step -> when (destination.step) {
    OnboardingStep.WELCOME -> welcomeProblem
    OnboardingStep.NAS_ADDRESS -> address.problem
    OnboardingStep.ACCOUNT -> account.problem
    OnboardingStep.AREAS -> areas.problem
    OnboardingStep.FOLDERS -> folders.problem
    OnboardingStep.BACKUP_SOURCES -> backupSources.problem
    OnboardingStep.ROLES -> roles.problem
    OnboardingStep.PRESET -> preset.problem
    OnboardingStep.PERMISSIONS -> permissions.problem
    OnboardingStep.BATTERY -> battery.problem
    OnboardingStep.VPN -> vpn.problem
    OnboardingStep.FAMILY_KEY -> key.problem
    OnboardingStep.SECURITY_NOTICE -> securityNotice.problem
    OnboardingStep.SUMMARY -> summary.problem
  }
}
