package dev.pampa.mycloudgallery.feature.onboarding

import androidx.compose.ui.res.stringResource
import dev.pampa.mycloudgallery.core.family.contract.OldAdminFate
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.RecoveryKitRef
import dev.pampa.mycloudgallery.core.qr.contract.ScanRejection
import dev.pampa.mycloudgallery.core.qr.contract.ScanVerdict
import dev.pampa.mycloudgallery.feature.onboarding.join.ScanMode
import dev.pampa.mycloudgallery.feature.onboarding.join.ScanPage
import dev.pampa.mycloudgallery.feature.onboarding.model.AccountUi
import dev.pampa.mycloudgallery.feature.onboarding.model.AddressUi
import dev.pampa.mycloudgallery.feature.onboarding.model.HomeUi
import dev.pampa.mycloudgallery.feature.onboarding.model.RecoveryPage
import dev.pampa.mycloudgallery.feature.onboarding.model.RecoveryUi
import dev.pampa.mycloudgallery.feature.onboarding.recovery.RecoveryActions
import dev.pampa.mycloudgallery.feature.onboarding.recovery.RecoveryCodePage
import dev.pampa.mycloudgallery.feature.onboarding.recovery.RecoveryDonePage
import dev.pampa.mycloudgallery.feature.onboarding.recovery.RecoveryFatePage
import dev.pampa.mycloudgallery.feature.onboarding.recovery.RecoveryForm
import dev.pampa.mycloudgallery.feature.onboarding.recovery.RecoveryKitsPage
import dev.pampa.mycloudgallery.feature.onboarding.steps.AccountActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.AccountForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.AccountScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.AddressActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.AddressForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.NasAddressScreen
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome

/** Gli screenshot di "Recupera l'admin" e "Secondo admin" (§2.3, E-S13). */
internal object ShotCatalogRecovery {

  private val kits = listOf(
    RecoveryKitRef("marta", "kit-2026-10-12.mcgkit", createdAt = 1_791_763_200_000L),
    RecoveryKitRef("marta", "kit-2026-03-02.mcgkit", createdAt = 1_772_409_600_000L),
  )

  private fun progress(step: OnboardingStep, second: Boolean = false) =
    Shot.pathProgress(if (second) OnboardingPath.SECOND_ADMIN else OnboardingPath.RECOVER_ADMIN, step)

  private fun chrome(step: OnboardingStep, second: Boolean = false): StepChrome {
    val p = progress(step, second)
    return Samples.pathChrome(p.plan.indexOf(step) + 1, p.plan.size)
  }

  private fun kitsShot(slug: String, ui: RecoveryUi, second: Boolean = false, main: Boolean = false, tall: Boolean = true) =
    Shot("31$slug", OnboardingStep.ACCOUNT, progress(OnboardingStep.ACCOUNT, second), main = main, tall = tall) {
      RecoveryKitsPage(chrome(OnboardingStep.ACCOUNT, second), ui, RecoveryForm(), RecoveryActions())
    }

  private fun codeShot(
    slug: String,
    ui: RecoveryUi,
    form: RecoveryForm = RecoveryForm(),
    second: Boolean = false,
    main: Boolean = false,
    tall: Boolean = true,
  ) = Shot("33$slug", OnboardingStep.ACCOUNT, progress(OnboardingStep.ACCOUNT, second), main = main, tall = tall) {
    RecoveryCodePage(chrome(OnboardingStep.ACCOUNT, second), ui, form, RecoveryActions())
  }

  private val loaded = RecoveryUi(kitsLoaded = true, kits = kits, privateShare = "marta")

  fun shots(): List<Shot> = listOf(
    Shot("30a-recupero-indirizzo-del-nas", OnboardingStep.NAS_ADDRESS, progress(OnboardingStep.NAS_ADDRESS), main = true, tall = true) {
      NasAddressScreen(
        chrome = chrome(OnboardingStep.NAS_ADDRESS),
        ui = AddressUi(home = HomeUi.Confirmed(Samples.home), found = Samples.found),
        form = AddressForm(ip = "192.168.178.58", selected = 0),
        actions = AddressActions(),
        title = stringResource(R.string.address_title_admin),
      )
    },
    Shot("30b-recupero-account-dell-admin", OnboardingStep.ACCOUNT, progress(OnboardingStep.ACCOUNT), main = true) {
      AccountScreen(
        chrome = chrome(OnboardingStep.ACCOUNT),
        ui = AccountUi(),
        form = AccountForm("marta"),
        actions = AccountActions(),
        title = stringResource(R.string.account_title_admin),
      )
    },
    kitsShot("a-recupero-cerco-i-kit", RecoveryUi(privateShare = "marta"), tall = false),
    kitsShot("b-recupero-kit-trovati", loaded, main = true),
    kitsShot("c-recupero-nessun-kit", RecoveryUi(kitsLoaded = true, noKits = true, privateShare = "marta")),
    kitsShot("d-secondo-admin-kit-trovati", loaded.copy(secondAdmin = true), second = true, main = true),
    kitsShot("e-recupero-un-solo-kit", loaded.copy(kits = kits.take(1))),
    Shot("32a-recupero-vecchio-admin", OnboardingStep.ACCOUNT, progress(OnboardingStep.ACCOUNT), main = true) {
      RecoveryFatePage(chrome(OnboardingStep.ACCOUNT), RecoveryForm(), RecoveryActions())
    },
    Shot("32b-recupero-vecchio-admin-perso", OnboardingStep.ACCOUNT, progress(OnboardingStep.ACCOUNT)) {
      RecoveryFatePage(chrome(OnboardingStep.ACCOUNT), RecoveryForm(fate = OldAdminFate.LOST), RecoveryActions())
    },
    Shot("32c-recupero-vecchio-admin-revocato", OnboardingStep.ACCOUNT, progress(OnboardingStep.ACCOUNT)) {
      RecoveryFatePage(chrome(OnboardingStep.ACCOUNT), RecoveryForm(fate = OldAdminFate.REVOKED), RecoveryActions())
    },
    codeShot("a-recupero-codice", loaded.copy(page = RecoveryPage.Code), main = true),
    codeShot("b-recupero-codice-scritto", loaded.copy(page = RecoveryPage.Code), RecoveryForm(code = "K7QD 2M9X FA4T ZC5P 3N6R 1V8W 9HBE")),
    codeShot(
      "c-recupero-codice-nascosto",
      loaded.copy(page = RecoveryPage.Code),
      RecoveryForm(code = "K7QD2M9XFA4TZC5P3N6R1V8W9HBE", reveal = false),
    ),
    codeShot(
      "d-recupero-codice-sbagliato",
      loaded.copy(page = RecoveryPage.Code, wrongCode = true),
      RecoveryForm(code = "K7QD2M9XFA4TZC5P3N6R1V8W9HBF"),
    ),
    codeShot(
      "e-recupero-apertura-in-corso",
      loaded.copy(page = RecoveryPage.Code, busy = true),
      RecoveryForm(code = "K7QD2M9XFA4TZC5P3N6R1V8W9HBE"),
    ),
    codeShot(
      "f-recupero-stato-della-famiglia-letto",
      loaded.copy(page = RecoveryPage.Code, familyState = sampleFamilyState()),
      RecoveryForm(code = "K7QD2M9XFA4TZC5P3N6R1V8W9HBE"),
    ),
    codeShot(
      "g-recupero-kit-non-fidato",
      loaded.copy(page = RecoveryPage.Code, problem = OnboardingProblem.REGISTRY_UNTRUSTED),
      RecoveryForm(code = "K7QD2M9XFA4TZC5P3N6R1V8W9HBE"),
    ),
    codeShot(
      "h-secondo-admin-codice",
      loaded.copy(page = RecoveryPage.Code, secondAdmin = true),
      RecoveryForm(code = "K7QD 2M9X"),
      second = true,
    ),
    Shot("34a-recupero-stato-della-famiglia-scansione", OnboardingStep.ACCOUNT, progress(OnboardingStep.ACCOUNT), main = true) {
      ScanPage(chrome(OnboardingStep.ACCOUNT), ScanMode.FamilyState, null, Samples.scanner, { ScanVerdict.Ignore }, null, {})
    },
    Shot("34b-recupero-stato-della-famiglia-rifiutato", OnboardingStep.ACCOUNT, progress(OnboardingStep.ACCOUNT)) {
      ScanPage(chrome(OnboardingStep.ACCOUNT), ScanMode.FamilyState, ScanRejection.OTHER_FAMILY, Samples.scanner, { ScanVerdict.Ignore }, null, {})
    },
    Shot("35a-recupero-fatto", OnboardingStep.ACCOUNT, progress(OnboardingStep.ACCOUNT), main = true, tall = true) {
      RecoveryDonePage(chrome(OnboardingStep.ACCOUNT).copy(progress = null), loaded.copy(page = RecoveryPage.Done), OldAdminFate.LOST, RecoveryActions())
    },
    Shot("35b-recupero-fatto-vecchio-revocato", OnboardingStep.ACCOUNT, progress(OnboardingStep.ACCOUNT), tall = true) {
      RecoveryDonePage(
        chrome(OnboardingStep.ACCOUNT).copy(progress = null),
        loaded.copy(page = RecoveryPage.Done, familyState = sampleFamilyState()),
        OldAdminFate.REVOKED,
        RecoveryActions(),
      )
    },
    Shot("35c-secondo-admin-fatto", OnboardingStep.ACCOUNT, progress(OnboardingStep.ACCOUNT, second = true), main = true, tall = true) {
      RecoveryDonePage(
        chrome(OnboardingStep.ACCOUNT, second = true).copy(progress = null),
        loaded.copy(page = RecoveryPage.Done, secondAdmin = true),
        null,
        RecoveryActions(),
      )
    },
  )
}
