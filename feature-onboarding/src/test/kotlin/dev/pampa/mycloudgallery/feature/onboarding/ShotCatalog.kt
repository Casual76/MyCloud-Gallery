package dev.pampa.mycloudgallery.feature.onboarding

import dev.pampa.mycloudgallery.core.family.contract.FolderProposal
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingWarning
import dev.pampa.mycloudgallery.core.family.contract.PreviewQuality
import dev.pampa.mycloudgallery.core.network.contract.VpnCapability
import dev.pampa.mycloudgallery.core.network.contract.VpnCommandResult
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.feature.onboarding.model.AccountUi
import dev.pampa.mycloudgallery.feature.onboarding.model.AddressUi
import dev.pampa.mycloudgallery.feature.onboarding.model.AreasUi
import dev.pampa.mycloudgallery.feature.onboarding.model.BatteryUi
import dev.pampa.mycloudgallery.feature.onboarding.model.FoldersUi
import dev.pampa.mycloudgallery.feature.onboarding.model.HomeUi
import dev.pampa.mycloudgallery.feature.onboarding.model.KeyPage
import dev.pampa.mycloudgallery.feature.onboarding.model.KeyUi
import dev.pampa.mycloudgallery.feature.onboarding.model.LocalStepUi
import dev.pampa.mycloudgallery.feature.onboarding.model.OnboardingBanner
import dev.pampa.mycloudgallery.feature.onboarding.model.SummaryUi
import dev.pampa.mycloudgallery.feature.onboarding.model.VpnTestUi
import dev.pampa.mycloudgallery.feature.onboarding.model.VpnUi
import dev.pampa.mycloudgallery.feature.onboarding.platform.PermissionKind
import dev.pampa.mycloudgallery.feature.onboarding.platform.PermissionState
import dev.pampa.mycloudgallery.feature.onboarding.platform.PermissionsSnapshot
import dev.pampa.mycloudgallery.core.security.device.ScreenLockStatus
import dev.pampa.mycloudgallery.feature.onboarding.steps.AccountActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.AccountForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.AccountScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.AddressActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.AddressForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.AreasActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.AreasForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.AreasScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.BackupSourcesForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.BackupSourcesScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.BatteryAction
import dev.pampa.mycloudgallery.feature.onboarding.steps.BatteryActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.BatteryForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.BatteryScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.FoldersActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.FoldersForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.FoldersScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.KeyActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.KeyForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.KeyIntroPage
import dev.pampa.mycloudgallery.feature.onboarding.steps.KeyRetypePage
import dev.pampa.mycloudgallery.feature.onboarding.steps.KeyShowCodePage
import dev.pampa.mycloudgallery.feature.onboarding.steps.KeyVerifyingPage
import dev.pampa.mycloudgallery.feature.onboarding.steps.NasAddressScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.PermissionsActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.PermissionsScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.PresetForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.PresetScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.RoleAdvice
import dev.pampa.mycloudgallery.feature.onboarding.steps.RolesForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.RolesScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.RouterCheck
import dev.pampa.mycloudgallery.feature.onboarding.steps.SecurityNoticeForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.SecurityNoticeScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.SummaryScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.VpnActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.VpnForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.VpnScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.WelcomeScreen
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome

/**
 * Tutti gli screenshot dell'onboarding: ogni passo (e ogni suo stato) del percorso "crea la famiglia", poi le
 * pagine di "entra" e di "recupera" (`ShotCatalogJoin`, `ShotCatalogRecovery`).
 *
 * Gli stati principali (`main`) si fotografano anche sul tablet e con il testo grande; gli errori e le varianti su
 * telefono, chiaro e scuro; le pagine lunghe (`tall`) anche intere.
 */
internal object ShotCatalog {

  private fun chrome(step: OnboardingStep, banner: OnboardingBanner? = null, warnings: List<OnboardingWarning> = emptyList()) =
    Samples.chrome(step, banner = banner, warnings = warnings)

  private val noUi = LocalStepUi()

  val all: List<Shot> by lazy { steps() + ShotCatalogJoin.shots() + ShotCatalogRecovery.shots() }

  private fun steps(): List<Shot> = welcome() + address() + account() + areas() + folders() + local() + permissions() + battery() +
    vpn() + key() + closing()

  // --- 1. Benvenuto ---------------------------------------------------------------------------------

  private fun welcome() = listOf(
    Shot("01a-benvenuto", OnboardingStep.WELCOME, main = true, tall = true) {
      WelcomeScreen(StepChrome(), isSystemUser = true, busy = false, problem = null, onChoose = {}, onProblemAction = {})
    },
    Shot("01b-benvenuto-profilo-del-tablet", OnboardingStep.WELCOME, tall = true) {
      WelcomeScreen(StepChrome(), isSystemUser = false, busy = false, problem = null, onChoose = {}, onProblemAction = {})
    },
    Shot("01c-benvenuto-passo-non-disponibile", OnboardingStep.WELCOME) {
      WelcomeScreen(StepChrome(), isSystemUser = true, busy = false, problem = OnboardingProblem.STEP_NOT_AVAILABLE, onChoose = {}, onProblemAction = {})
    },
  )

  // --- 2. Indirizzo del NAS -------------------------------------------------------------------------

  private fun addressShot(slug: String, ui: AddressUi, form: AddressForm = AddressForm(), main: Boolean = false, tall: Boolean = false) =
    Shot(slug, OnboardingStep.NAS_ADDRESS, main = main, tall = tall) {
      NasAddressScreen(chrome = chrome(OnboardingStep.NAS_ADDRESS), ui = ui, form = form, actions = AddressActions())
    }

  private fun address() = listOf(
    addressShot("02a-indirizzo-cerco-la-rete", AddressUi(home = HomeUi.Loading)),
    addressShot("02b-indirizzo-e-la-rete-di-casa", AddressUi(home = HomeUi.Ask(Samples.home)), main = true),
    addressShot(
      "02c-indirizzo-scelta-del-nas",
      AddressUi(home = HomeUi.Confirmed(Samples.home), found = Samples.found),
      AddressForm(ip = "192.168.178.58", name = "WDMyCloudEX2Ultra", selected = 0),
      tall = true,
    ),
    addressShot(
      "02d-indirizzo-cerco-i-nas",
      AddressUi(home = HomeUi.Confirmed(Samples.home), discovering = true),
    ),
    addressShot(
      "02e-indirizzo-controllo-in-corso",
      AddressUi(home = HomeUi.Confirmed(Samples.home), found = Samples.found, checking = true),
      AddressForm(ip = "192.168.178.58", selected = 0),
    ),
    addressShot("02f-indirizzo-serve-il-wifi-di-casa", AddressUi(home = HomeUi.NoWifi)),
    addressShot("02g-indirizzo-non-e-casa", AddressUi(home = HomeUi.NotHome)),
    addressShot("02h-indirizzo-vpn-accesa", AddressUi(home = HomeUi.Ask(Samples.home), problem = OnboardingProblem.VPN_ACTIVE)),
    addressShot("02i-indirizzo-rete-senza-ula", AddressUi(home = HomeUi.Ask(Samples.homeNoUla))),
    addressShot(
      "02j-indirizzo-rete-locale-negata",
      AddressUi(home = HomeUi.Confirmed(Samples.home), problem = OnboardingProblem.LOCAL_NETWORK_DENIED),
      AddressForm(ip = "192.168.178.58"),
    ),
    addressShot(
      "02k-indirizzo-nas-spento",
      AddressUi(home = HomeUi.Confirmed(Samples.home), problem = OnboardingProblem.NAS_NOT_FOUND),
      AddressForm(ip = "192.168.178.99"),
      tall = true,
    ),
    addressShot(
      "02l-indirizzo-condivisione-ferma",
      AddressUi(home = HomeUi.Confirmed(Samples.home), problem = OnboardingProblem.NAS_SERVICE_DOWN),
      AddressForm(ip = "192.168.178.58"),
    ),
    addressShot(
      "02m-indirizzo-connessione-non-cifrata",
      AddressUi(home = HomeUi.Confirmed(Samples.home), problem = OnboardingProblem.NAS_INSECURE),
      AddressForm(ip = "192.168.178.58"),
    ),
    addressShot(
      "02n-indirizzo-nas-diverso",
      AddressUi(home = HomeUi.Confirmed(Samples.home), problem = OnboardingProblem.IDENTITY_MISMATCH),
      AddressForm(ip = "192.168.178.58"),
    ),
    addressShot(
      "02o-indirizzo-ip-non-valido",
      AddressUi(home = HomeUi.Confirmed(Samples.home)),
      AddressForm(ip = "nas.fritz.box"),
    ),
  )

  // --- 3. Account -----------------------------------------------------------------------------------

  private fun accountShot(
    slug: String,
    ui: AccountUi = AccountUi(),
    form: AccountForm = AccountForm(),
    banner: OnboardingBanner? = OnboardingBanner.NAS_VERIFIED,
    main: Boolean = false,
    tall: Boolean = false,
  ) = Shot(slug, OnboardingStep.ACCOUNT, main = main, tall = tall) {
    AccountScreen(chrome = chrome(OnboardingStep.ACCOUNT, banner = banner), ui = ui, form = form, actions = AccountActions())
  }

  private fun account() = listOf(
    accountShot("03a-account-vuoto", main = true),
    accountShot("03b-account-compilato", form = AccountForm("marta", "una-password-molto-lunga")),
    accountShot("03c-account-password-corta", form = AccountForm("marta", "corta1")),
    accountShot("03d-account-password-visibile", form = AccountForm("marta", "una-password-molto-lunga", reveal = true)),
    accountShot("03e-account-admin-rifiutato", form = AccountForm("Admin", "una-password-molto-lunga")),
    accountShot("03f-account-nome-non-valido", form = AccountForm("marta rossi", "una-password-molto-lunga")),
    accountShot("03g-account-accesso-in-corso", AccountUi(submitting = true), AccountForm("marta", "una-password-molto-lunga")),
    accountShot(
      "03h-account-password-errata",
      AccountUi(problem = OnboardingProblem.AUTH_FAILED, needsNewPassword = true),
      AccountForm("marta"),
      banner = null,
    ),
    accountShot(
      "03i-account-bloccato",
      AccountUi(problem = OnboardingProblem.ACCOUNT_LOCKED, needsNewPassword = true),
      AccountForm("marta"),
      banner = null,
    ),
    accountShot(
      "03j-account-disattivato",
      AccountUi(problem = OnboardingProblem.ACCOUNT_DISABLED, needsNewPassword = true),
      AccountForm("marta"),
      banner = null,
    ),
    accountShot(
      "03k-account-password-scaduta",
      AccountUi(problem = OnboardingProblem.PASSWORD_EXPIRED, needsNewPassword = true),
      AccountForm("marta"),
      banner = null,
    ),
    accountShot(
      "03l-account-accesso-limitato",
      AccountUi(problem = OnboardingProblem.ACCOUNT_RESTRICTED, needsNewPassword = true),
      AccountForm("marta"),
      banner = null,
    ),
    accountShot(
      "03m-account-deposito-sicuro-assente",
      AccountUi(problem = OnboardingProblem.CREDENTIALS_NOT_SAVED),
      AccountForm("marta", "una-password-molto-lunga"),
      banner = null,
    ),
    accountShot(
      "03n-account-connessione-non-cifrata",
      AccountUi(problem = OnboardingProblem.NAS_INSECURE),
      AccountForm("marta", "una-password-molto-lunga"),
      banner = null,
    ),
  )

  // --- 4. Aree --------------------------------------------------------------------------------------

  private fun areasShot(
    slug: String,
    ui: AreasUi,
    form: AreasForm = AreasForm(),
    banner: OnboardingBanner? = null,
    warnings: List<OnboardingWarning> = emptyList(),
    main: Boolean = false,
    tall: Boolean = false,
  ) = Shot(slug, OnboardingStep.AREAS, main = main, tall = tall) {
    AreasScreen(chrome = chrome(OnboardingStep.AREAS, banner = banner, warnings = warnings), ui = ui, form = form, actions = AreasActions())
  }

  private fun areas() = listOf(
    areasShot("04a-aree-controllo-in-corso", AreasUi(loading = true), banner = OnboardingBanner.CREDENTIALS_SAVED),
    areasShot(
      "04b-aree-scelta",
      AreasUi(loading = false, candidates = Samples.areas),
      AreasForm(commonShare = "Public", privateShare = "marta"),
      banner = OnboardingBanner.CREDENTIALS_SAVED,
      warnings = listOf(OnboardingWarning.SHORT_PASSWORD, OnboardingWarning.ACCOUNT_SEES_OTHER_PRIVATE_SHARES),
      main = true,
      tall = true,
    ),
    areasShot("04c-aree-da-scegliere", AreasUi(loading = false, candidates = Samples.areas), tall = true),
    areasShot("04d-aree-nessuna-accessibile", AreasUi(loading = false, candidates = Samples.areasNoAccess)),
    areasShot(
      "04e-aree-comune-non-scrivibile",
      AreasUi(loading = false, candidates = Samples.areasOneWritable, problem = OnboardingProblem.COMMON_AREA_NOT_WRITABLE),
      AreasForm(privateShare = "marta"),
      tall = true,
    ),
    areasShot(
      "04f-aree-famiglia-gia-esistente",
      AreasUi(loading = false, candidates = Samples.areasWithFamily, problem = OnboardingProblem.FAMILY_ALREADY_EXISTS),
      AreasForm(commonShare = "Public", privateShare = "marta"),
    ),
    areasShot(
      "04g-aree-invio-in-corso",
      AreasUi(loading = false, candidates = Samples.areas, submitting = true),
      AreasForm(commonShare = "Public", privateShare = "marta"),
      tall = true,
    ),
    areasShot(
      "04h-aree-scrittura-fallita",
      AreasUi(loading = false, candidates = Samples.areas, problem = OnboardingProblem.NAS_WRITE_FAILED),
      AreasForm(commonShare = "Public", privateShare = "marta"),
      tall = true,
    ),
  )

  // --- 5. Cartelle ----------------------------------------------------------------------------------

  private fun foldersShot(
    slug: String,
    ui: FoldersUi,
    form: FoldersForm = FoldersForm(),
    warnings: List<OnboardingWarning> = emptyList(),
    main: Boolean = false,
    tall: Boolean = false,
  ) = Shot(slug, OnboardingStep.FOLDERS, main = main, tall = tall) {
    FoldersScreen(chrome = chrome(OnboardingStep.FOLDERS, warnings = warnings), ui = ui, form = form, actions = FoldersActions())
  }

  private fun folders() = listOf(
    foldersShot("05a-cartelle-ricerca-in-corso", FoldersUi(loading = true)),
    foldersShot(
      "05b-cartelle-proposte",
      FoldersUi(loading = false, proposal = Samples.folders),
      FoldersForm(Samples.folders.excluded.take(4).map { it.path }.toSet()),
      warnings = listOf(OnboardingWarning.SHARE_IS_PUBLIC),
      main = true,
      tall = true,
    ),
    foldersShot("05c-cartelle-niente-da-escludere", FoldersUi(loading = false, proposal = FolderProposal.EMPTY.copy(scanned = 812))),
    foldersShot(
      "05d-cartelle-limite-raggiunto",
      FoldersUi(loading = false, proposal = Samples.foldersTruncated),
      FoldersForm(Samples.foldersTruncated.excluded.map { it.path }.toSet()),
      tall = true,
    ),
    foldersShot(
      "05e-cartelle-scrittura-fallita",
      FoldersUi(loading = false, proposal = Samples.folders, problem = OnboardingProblem.NAS_WRITE_FAILED),
      FoldersForm(Samples.folders.excluded.map { it.path }.toSet()),
      tall = true,
    ),
  )

  // --- 6-8. Scelte locali ---------------------------------------------------------------------------

  private fun local() = listOf(
    Shot("06a-sorgenti", OnboardingStep.BACKUP_SOURCES, main = true) {
      BackupSourcesScreen(chrome(OnboardingStep.BACKUP_SOURCES), noUi, BackupSourcesForm(), { _, _ -> }, {})
    },
    Shot("06b-sorgenti-nessuna", OnboardingStep.BACKUP_SOURCES) {
      BackupSourcesScreen(chrome(OnboardingStep.BACKUP_SOURCES), noUi, BackupSourcesForm(camera = false, screenshots = false), { _, _ -> }, {})
    },
    Shot("07a-ruoli-telefono", OnboardingStep.ROLES, main = true) {
      RolesScreen(chrome(OnboardingStep.ROLES), noUi, RolesForm(true, true), RoleAdvice.forDevice(isTablet = false), { _, _ -> }, {})
    },
    Shot("07b-ruoli-tablet", OnboardingStep.ROLES) {
      RolesScreen(chrome(OnboardingStep.ROLES), noUi, RolesForm(false, true), RoleAdvice.forDevice(isTablet = true), { _, _ -> }, {})
    },
    Shot("08a-preset-notte", OnboardingStep.PRESET, main = true, tall = true) {
      PresetScreen(chrome(OnboardingStep.PRESET), noUi, PresetForm(Preset.NIGHT, PreviewQuality.STANDARD), { _, _ -> }, {})
    },
    Shot("08b-preset-massimo-qualita-alta", OnboardingStep.PRESET, tall = true) {
      PresetScreen(chrome(OnboardingStep.PRESET), noUi, PresetForm(Preset.MAXIMUM, PreviewQuality.HIGH), { _, _ -> }, {})
    },
    Shot("08c-preset-bilanciato-compatta", OnboardingStep.PRESET, tall = true) {
      PresetScreen(chrome(OnboardingStep.PRESET), noUi, PresetForm(Preset.BALANCED, PreviewQuality.COMPACT), { _, _ -> }, {})
    },
  )

  // --- 9. Permessi ----------------------------------------------------------------------------------

  private fun snapshot(
    notifications: PermissionState,
    media: PermissionState,
    location: PermissionState,
    localNetwork: PermissionState = PermissionState.NOT_NEEDED,
    requested: Set<PermissionKind> = emptySet(),
    lock: ScreenLockStatus = ScreenLockStatus.SECURE,
  ) = PermissionsSnapshot(
    states = mapOf(
      PermissionKind.NOTIFICATIONS to notifications,
      PermissionKind.MEDIA to media,
      PermissionKind.MEDIA_LOCATION to location,
      PermissionKind.LOCAL_NETWORK to localNetwork,
    ),
    requested = requested,
    screenLock = lock,
  )

  private fun permissionsShot(slug: String, snapshot: PermissionsSnapshot, main: Boolean = false, tall: Boolean = false) =
    Shot(slug, OnboardingStep.PERMISSIONS, main = main, tall = tall) {
      PermissionsScreen(chrome(OnboardingStep.PERMISSIONS), noUi, snapshot, PermissionsActions())
    }

  private fun permissions() = listOf(
    permissionsShot(
      "09a-permessi-da-concedere",
      snapshot(PermissionState.DENIED, PermissionState.DENIED, PermissionState.DENIED),
      main = true,
      tall = true,
    ),
    permissionsShot(
      "09b-permessi-tutti-concessi",
      snapshot(PermissionState.GRANTED, PermissionState.GRANTED, PermissionState.GRANTED),
    ),
    permissionsShot(
      "09c-permessi-negati-modalita-ridotta",
      snapshot(
        PermissionState.DENIED,
        PermissionState.DENIED,
        PermissionState.DENIED,
        requested = setOf(PermissionKind.NOTIFICATIONS, PermissionKind.MEDIA, PermissionKind.MEDIA_LOCATION),
      ),
      tall = true,
    ),
    permissionsShot(
      "09d-permessi-foto-selezionate",
      snapshot(PermissionState.GRANTED, PermissionState.PARTIAL, PermissionState.DENIED, requested = setOf(PermissionKind.MEDIA)),
      tall = true,
    ),
    permissionsShot(
      "09e-permessi-senza-blocco-schermo",
      snapshot(PermissionState.GRANTED, PermissionState.GRANTED, PermissionState.GRANTED, lock = ScreenLockStatus.NOT_SECURE),
      tall = true,
    ),
    permissionsShot(
      "09f-permessi-senza-notifiche",
      snapshot(PermissionState.DENIED, PermissionState.GRANTED, PermissionState.GRANTED, requested = setOf(PermissionKind.NOTIFICATIONS)),
    ),
    permissionsShot(
      "09g-permessi-rete-locale-negata",
      snapshot(PermissionState.GRANTED, PermissionState.GRANTED, PermissionState.GRANTED, localNetwork = PermissionState.DENIED),
    ),
  )

  // --- 10. Batteria ---------------------------------------------------------------------------------

  private fun batteryShot(slug: String, ui: BatteryUi, form: BatteryForm = BatteryForm(), main: Boolean = false, tall: Boolean = false) =
    Shot(slug, OnboardingStep.BATTERY, main = main, tall = tall) {
      BatteryScreen(chrome(OnboardingStep.BATTERY), ui, form, BatteryActions())
    }

  private fun battery() = listOf(
    batteryShot("10a-batteria-da-fare", BatteryUi(health = Samples.healthTodo), main = true, tall = true),
    batteryShot("10b-batteria-tutto-verificato", BatteryUi(health = Samples.healthDone), BatteryForm(setOf(BatteryAction.NEVER_SLEEP)), tall = true),
    batteryShot("10c-batteria-non-verificabile", BatteryUi(health = Samples.healthUnknown), tall = true),
    batteryShot("10d-batteria-lettura-in-corso", BatteryUi(health = null), tall = true),
    batteryShot(
      "10e-batteria-a-meta",
      BatteryUi(health = Samples.healthDone.copy(unusedAppRestrictions = dev.pampa.mycloudgallery.core.rules.platform.health.UnusedAppRestrictions.ENABLED)),
      tall = true,
    ),
  )

  // --- 11. VPN --------------------------------------------------------------------------------------

  private fun vpnShot(slug: String, ui: VpnUi, form: VpnForm = VpnForm(), main: Boolean = false, tall: Boolean = true) =
    Shot(slug, OnboardingStep.VPN, main = main, tall = tall) {
      VpnScreen(chrome(OnboardingStep.VPN), ui, form, VpnActions())
    }

  private fun vpn() = listOf(
    vpnShot("11a-vpn-guida", VpnUi(), main = true),
    vpnShot("11b-vpn-wireguard-mancante", VpnUi(capability = VpnCapability.WIREGUARD_MISSING), VpnForm(remoteControl = true)),
    vpnShot("11c-vpn-permesso-mancante", VpnUi(capability = VpnCapability.PERMISSION_MISSING), VpnForm(remoteControl = true)),
    vpnShot("11d-vpn-pronta-alla-prova", VpnUi(capability = VpnCapability.READY), VpnForm(remoteControl = true, tunnelName = "Casa")),
    vpnShot(
      "11e-vpn-prova-in-corso",
      VpnUi(capability = VpnCapability.READY, test = VpnTestUi.Running(up = true)),
      VpnForm(remoteControl = true, tunnelName = "Casa"),
    ),
    vpnShot(
      "11f-vpn-prova-riuscita",
      VpnUi(capability = VpnCapability.READY, test = VpnTestUi.Done(VpnCommandResult.Confirmed, VpnCommandResult.Confirmed)),
      VpnForm(remoteControl = true, tunnelName = "Casa"),
    ),
    vpnShot(
      "11g-vpn-non-controllabile",
      VpnUi(capability = VpnCapability.READY, test = VpnTestUi.Done(VpnCommandResult.NotObserved, null)),
      VpnForm(remoteControl = true, tunnelName = "Casa"),
    ),
    vpnShot(
      "11h-vpn-rete-sparita",
      VpnUi(capability = VpnCapability.READY, test = VpnTestUi.Done(VpnCommandResult.Confirmed, VpnCommandResult.LostConnectivity)),
      VpnForm(remoteControl = true, tunnelName = "Casa"),
    ),
    vpnShot(
      "11i-vpn-da-configurare",
      VpnUi(capability = VpnCapability.NOT_CONFIGURED),
      VpnForm(remoteControl = true),
    ),
  )

  // --- 12. Chiave di famiglia e kit -----------------------------------------------------------------

  private val keyChrome get() = chrome(OnboardingStep.FAMILY_KEY)

  private fun key() = listOf(
    Shot("12a-chiave-cosa-sono", OnboardingStep.FAMILY_KEY, main = true, tall = true) {
      KeyIntroPage(keyChrome, KeyUi(), KeyActions())
    },
    Shot("12b-chiave-generazione-in-corso", OnboardingStep.FAMILY_KEY) {
      KeyIntroPage(keyChrome, KeyUi(busy = true), KeyActions())
    },
    Shot("12c-chiave-il-codice", OnboardingStep.FAMILY_KEY, main = true, tall = true) {
      KeyShowCodePage(keyChrome.copy(onBack = null), KeyUi(KeyPage.ShowCode, groups = Samples.codeGroups), KeyForm(), KeyActions())
    },
    Shot("12d-chiave-il-codice-scritto", OnboardingStep.FAMILY_KEY, tall = true) {
      KeyShowCodePage(keyChrome.copy(onBack = null), KeyUi(KeyPage.ShowCode, groups = Samples.codeGroups), KeyForm(acknowledged = true), KeyActions())
    },
    Shot("12e-chiave-riscrivilo-vuoto", OnboardingStep.FAMILY_KEY) {
      KeyRetypePage(keyChrome, KeyUi(KeyPage.Retype, groups = Samples.codeGroups), KeyForm(), KeyActions())
    },
    Shot("12f-chiave-riscrivilo-a-meta", OnboardingStep.FAMILY_KEY) {
      KeyRetypePage(keyChrome, KeyUi(KeyPage.Retype, groups = Samples.codeGroups), KeyForm(typed = "K7QD2M9XFA4TZC"), KeyActions())
    },
    Shot("12g-chiave-riscrivilo-completo", OnboardingStep.FAMILY_KEY) {
      KeyRetypePage(keyChrome, KeyUi(KeyPage.Retype, groups = Samples.codeGroups), KeyForm(typed = Samples.codeGroups.joinToString("")), KeyActions())
    },
    Shot("12h-chiave-codice-diverso", OnboardingStep.FAMILY_KEY) {
      KeyRetypePage(
        keyChrome,
        KeyUi(KeyPage.Retype, groups = Samples.codeGroups, problem = OnboardingProblem.RECOVERY_CODE_MISMATCH),
        KeyForm(typed = "K7QD2M9XFA4TZC5P3N6R1V8W9HBF"),
        KeyActions(),
      )
    },
    Shot("12i-chiave-verifica-in-corso", OnboardingStep.FAMILY_KEY, main = true) {
      KeyVerifyingPage(keyChrome.copy(onBack = null))
    },
    Shot("12j-chiave-kit-non-verificabile", OnboardingStep.FAMILY_KEY) {
      KeyRetypePage(
        keyChrome,
        KeyUi(KeyPage.Retype, groups = Samples.codeGroups, problem = OnboardingProblem.RECOVERY_KIT_UNVERIFIED),
        KeyForm(typed = Samples.codeGroups.joinToString("")),
        KeyActions(),
      )
    },
    Shot("12k-chiave-scrittura-fallita", OnboardingStep.FAMILY_KEY) {
      KeyRetypePage(
        keyChrome,
        KeyUi(KeyPage.Retype, groups = Samples.codeGroups, problem = OnboardingProblem.NAS_WRITE_FAILED),
        KeyForm(typed = Samples.codeGroups.joinToString("")),
        KeyActions(),
      )
    },
    Shot("12l-chiave-nas-pieno", OnboardingStep.FAMILY_KEY) {
      KeyRetypePage(
        keyChrome,
        KeyUi(KeyPage.Retype, groups = Samples.codeGroups, problem = OnboardingProblem.NAS_FULL),
        KeyForm(typed = Samples.codeGroups.joinToString("")),
        KeyActions(),
      )
    },
    Shot("12m-chiave-famiglia-gia-esistente", OnboardingStep.FAMILY_KEY) {
      KeyIntroPage(keyChrome, KeyUi(problem = OnboardingProblem.FAMILY_ALREADY_EXISTS), KeyActions())
    },
    Shot("12n-chiave-generazione-non-riuscita", OnboardingStep.FAMILY_KEY, tall = true) {
      KeyIntroPage(keyChrome, KeyUi(problem = OnboardingProblem.STEP_NOT_AVAILABLE), KeyActions())
    },
  )

  // --- 13-14. Avviso e riepilogo --------------------------------------------------------------------

  private fun closing() = listOf(
    Shot("13a-avviso-backup-esterno", OnboardingStep.SECURITY_NOTICE, main = true, tall = true) {
      SecurityNoticeScreen(
        chrome(OnboardingStep.SECURITY_NOTICE, banner = OnboardingBanner.KIT_VERIFIED),
        noUi,
        SecurityNoticeForm(),
        { _, _ -> },
        {},
      )
    },
    Shot("13b-avviso-backup-esterno-a-meta", OnboardingStep.SECURITY_NOTICE, tall = true) {
      SecurityNoticeScreen(
        chrome(OnboardingStep.SECURITY_NOTICE),
        noUi,
        SecurityNoticeForm(externalBackup = true, router = setOf(RouterCheck.NO_PORT_FORWARDING, RouterCheck.REMOTE_ACCESS_OFF)),
        { _, _ -> },
        {},
      )
    },
    Shot("14a-riepilogo", OnboardingStep.SUMMARY, main = true, tall = true) {
      SummaryScreen(
        chrome = chrome(OnboardingStep.SUMMARY),
        ui = SummaryUi(),
        progress = Shot.foundProgress(OnboardingStep.SUMMARY).let { it.copy(completed = it.completed + OnboardingStep.SECURITY_NOTICE, skipped = it.skipped + OnboardingStep.VPN) },
        elapsedSeconds = 252,
        onFinish = {},
        onProblemAction = {},
      )
    },
    Shot("14b-riepilogo-tutto-fatto", OnboardingStep.SUMMARY, tall = true) {
      SummaryScreen(
        chrome = chrome(OnboardingStep.SUMMARY, warnings = listOf(OnboardingWarning.NO_SCREEN_LOCK)),
        ui = SummaryUi(),
        progress = Shot.foundProgress(OnboardingStep.SUMMARY).let { it.copy(skipped = emptySet(), completed = OnboardingStep.entries.toSet() - OnboardingStep.SUMMARY) },
        elapsedSeconds = 41,
        onFinish = {},
        onProblemAction = {},
      )
    },
    Shot("14c-riepilogo-in-corso", OnboardingStep.SUMMARY) {
      SummaryScreen(chrome(OnboardingStep.SUMMARY), SummaryUi(submitting = true), Shot.foundProgress(OnboardingStep.SUMMARY), null, {}, {})
    },
  )
}
