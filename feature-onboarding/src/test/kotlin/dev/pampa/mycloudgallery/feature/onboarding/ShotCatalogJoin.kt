package dev.pampa.mycloudgallery.feature.onboarding

import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingWarning
import dev.pampa.mycloudgallery.core.qr.contract.ScanRejection
import dev.pampa.mycloudgallery.core.qr.contract.ScanVerdict
import dev.pampa.mycloudgallery.feature.onboarding.join.AwaitingApprovalScreen
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinApprovedPage
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinManualPage
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinNameActions
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinNameForm
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinNamePage
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinPasswordActions
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinPasswordForm
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinPasswordPage
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinSummaryActions
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinSummaryPage
import dev.pampa.mycloudgallery.feature.onboarding.join.ManualJoinActions
import dev.pampa.mycloudgallery.feature.onboarding.join.ManualJoinForm
import dev.pampa.mycloudgallery.feature.onboarding.join.ScanMode
import dev.pampa.mycloudgallery.feature.onboarding.join.ScanPage
import dev.pampa.mycloudgallery.feature.onboarding.model.JoinPage
import dev.pampa.mycloudgallery.feature.onboarding.model.JoinUi

/** Gli screenshot di "Entra con il QR" e "Entra senza QR" (§2.2, lato nuovo dispositivo). */
internal object ShotCatalogJoin {

  private val invite = sampleInvite()
  private val inviteLabel = "12 ottobre 2026, 14:32"
  private fun progress(step: OnboardingStep, path: OnboardingPath = OnboardingPath.JOIN_QR) = Shot.pathProgress(path, step)

  /** Il numero di un passo nel piano di quel percorso: la barra dice "Passo 2 di 9". */
  private fun chrome(step: OnboardingStep, warnings: List<OnboardingWarning> = emptyList()) =
    Samples.pathChrome(Shot.pathProgress(OnboardingPath.JOIN_QR, step).plan.indexOf(step) + 1, Shot.pathProgress(OnboardingPath.JOIN_QR, step).plan.size, warnings)

  private fun scanShot(slug: String, rejection: ScanRejection? = null, mode: ScanMode = ScanMode.Invite, main: Boolean = false) =
    Shot("20$slug", OnboardingStep.NAS_ADDRESS, progress(OnboardingStep.NAS_ADDRESS), main = main) {
      ScanPage(
        chrome = chrome(OnboardingStep.NAS_ADDRESS),
        mode = mode,
        rejection = rejection,
        scanner = Samples.scanner,
        onScanned = { ScanVerdict.Ignore },
        onManual = if (mode == ScanMode.Invite) ({}) else null,
        onCancel = {},
      )
    }

  private fun manualShot(slug: String, ui: JoinUi = JoinUi(page = JoinPage.Manual), form: ManualJoinForm = ManualJoinForm(), main: Boolean = false) =
    Shot("21$slug", OnboardingStep.NAS_ADDRESS, progress(OnboardingStep.NAS_ADDRESS, OnboardingPath.JOIN_MANUAL), main = main, tall = true) {
      JoinManualPage(chrome(OnboardingStep.NAS_ADDRESS), ui, form, ManualJoinActions())
    }

  private fun summaryShot(slug: String, ui: JoinUi = JoinUi(page = JoinPage.Summary, invite = invite), main: Boolean = false, tall: Boolean = true) =
    Shot("22$slug", OnboardingStep.NAS_ADDRESS, progress(OnboardingStep.NAS_ADDRESS), main = main, tall = tall) {
      JoinSummaryPage(chrome(OnboardingStep.NAS_ADDRESS), ui, invite, inviteLabel, JoinSummaryActions())
    }

  private fun passwordShot(slug: String, ui: JoinUi, form: JoinPasswordForm = JoinPasswordForm(), main: Boolean = false) =
    Shot("23$slug", OnboardingStep.ACCOUNT, progress(OnboardingStep.ACCOUNT), main = main) {
      JoinPasswordPage(chrome(OnboardingStep.ACCOUNT), ui, "marta", form, JoinPasswordActions())
    }

  private fun nameShot(slug: String, ui: JoinUi, form: JoinNameForm, main: Boolean = false) =
    Shot("24$slug", OnboardingStep.ACCOUNT, progress(OnboardingStep.ACCOUNT), main = main) {
      JoinNamePage(chrome(OnboardingStep.ACCOUNT), ui, form, JoinNameActions())
    }

  fun shots(): List<Shot> = listOf(
    scanShot("a-ingresso-scansione", main = true),
    scanShot("b-ingresso-qr-di-wireguard", ScanRejection.WIREGUARD_SECRET),
    scanShot("c-ingresso-invito-di-un-altra-famiglia", ScanRejection.OTHER_FAMILY),
    scanShot("d-ingresso-qr-non-nostro", ScanRejection.NOT_OURS),
    scanShot("e-ingresso-invito-scaduto", ScanRejection.EXPIRED),
    scanShot("f-ingresso-qr-rovinato", ScanRejection.MALFORMED),
    scanShot("g-ingresso-stato-della-famiglia", mode = ScanMode.FamilyState),
    manualShot("a-manuale-vuoto", main = true),
    manualShot(
      "b-manuale-compilato",
      form = ManualJoinForm("192.168.178.58", "marta", "famiglia-rossi", "K7QD 2M9X FA4T ZC5P", "Tablet — profilo Mamma"),
    ),
    manualShot(
      "c-manuale-stato-letto",
      JoinUi(page = JoinPage.Manual, familyState = sampleFamilyState()),
      ManualJoinForm("192.168.178.58", "marta", "famiglia-rossi", "K7QD 2M9X FA4T ZC5P", "Tablet — profilo Mamma"),
    ),
    manualShot(
      "d-manuale-dati-non-validi",
      form = ManualJoinForm("nas.fritz.box", "admin", "famiglia-rossi", "K7QD 2M9X FA4T ZC5P", "Tablet"),
    ),
    manualShot(
      "e-manuale-controllo-in-corso",
      JoinUi(page = JoinPage.Manual, busy = true),
      ManualJoinForm("192.168.178.58", "marta", "famiglia-rossi", "K7QD 2M9X FA4T ZC5P", "Tablet"),
    ),
    manualShot(
      "f-manuale-nas-spento",
      JoinUi(page = JoinPage.Manual, problem = OnboardingProblem.NAS_NOT_FOUND),
      ManualJoinForm("192.168.178.58", "marta", "famiglia-rossi", "K7QD 2M9X FA4T ZC5P", "Tablet"),
    ),
    summaryShot("a-riepilogo-invito", main = true),
    summaryShot("b-riepilogo-controllo-in-corso", JoinUi(page = JoinPage.Summary, invite = invite, busy = true)),
    summaryShot("c-riepilogo-non-a-casa", JoinUi(page = JoinPage.Summary, invite = invite, problem = OnboardingProblem.NOT_AT_HOME), tall = false),
    summaryShot("d-riepilogo-nas-diverso", JoinUi(page = JoinPage.Summary, invite = invite, problem = OnboardingProblem.IDENTITY_MISMATCH)),
    summaryShot("e-riepilogo-invito-scaduto", JoinUi(page = JoinPage.Summary, invite = invite, problem = OnboardingProblem.INVITE_EXPIRED), tall = false),
    summaryShot("f-riepilogo-altra-famiglia", JoinUi(page = JoinPage.Summary, invite = invite, problem = OnboardingProblem.INVITE_OTHER_FAMILY), tall = false),
    summaryShot("g-riepilogo-registro-non-fidato", JoinUi(page = JoinPage.Summary, invite = invite, problem = OnboardingProblem.REGISTRY_UNTRUSTED), tall = false),
    summaryShot("h-riepilogo-serve-aggiornamento", JoinUi(page = JoinPage.Summary, invite = invite, problem = OnboardingProblem.NEEDS_APP_UPDATE), tall = false),
    summaryShot("i-riepilogo-invito-non-valido", JoinUi(page = JoinPage.Summary, invite = invite, problem = OnboardingProblem.INVITE_INVALID), tall = false),
    summaryShot("j-riepilogo-nas-spento", JoinUi(page = JoinPage.Summary, invite = invite, problem = OnboardingProblem.NAS_NOT_FOUND)),
    summaryShot("k-riepilogo-connessione-non-cifrata", JoinUi(page = JoinPage.Summary, invite = invite, problem = OnboardingProblem.NAS_INSECURE)),
    passwordShot("a-password", JoinUi(page = JoinPage.Password, invite = invite), main = true),
    passwordShot("b-password-compilata", JoinUi(page = JoinPage.Password, invite = invite), JoinPasswordForm("una-password-molto-lunga")),
    passwordShot("c-password-in-corso", JoinUi(page = JoinPage.Password, invite = invite, busy = true), JoinPasswordForm("una-password-molto-lunga")),
    passwordShot(
      "d-password-errata",
      JoinUi(page = JoinPage.Password, invite = invite, problem = OnboardingProblem.AUTH_FAILED, needsNewPassword = true),
    ),
    passwordShot(
      "e-password-account-bloccato",
      JoinUi(page = JoinPage.Password, invite = invite, problem = OnboardingProblem.ACCOUNT_LOCKED, needsNewPassword = true),
    ),
    passwordShot(
      "f-password-account-disattivato",
      JoinUi(page = JoinPage.Password, invite = invite, problem = OnboardingProblem.ACCOUNT_DISABLED, needsNewPassword = true),
    ),
    passwordShot(
      "g-password-scaduta",
      JoinUi(page = JoinPage.Password, invite = invite, problem = OnboardingProblem.PASSWORD_EXPIRED, needsNewPassword = true),
    ),
    passwordShot(
      "h-password-accesso-limitato",
      JoinUi(page = JoinPage.Password, invite = invite, problem = OnboardingProblem.ACCOUNT_RESTRICTED, needsNewPassword = true),
    ),
    nameShot("a-nome", JoinUi(page = JoinPage.Name, invite = invite, suggestedName = "Tablet — profilo Mamma"), JoinNameForm("Tablet — profilo Mamma"), main = true),
    nameShot("b-nome-invio-in-corso", JoinUi(page = JoinPage.Name, invite = invite, busy = true), JoinNameForm("Tablet — profilo Mamma")),
    nameShot(
      "c-nome-richiesta-non-inviata",
      JoinUi(page = JoinPage.Name, invite = invite, problem = OnboardingProblem.NAS_WRITE_FAILED),
      JoinNameForm("Tablet — profilo Mamma"),
    ),
    Shot("25a-ingresso-attesa-approvazione", OnboardingStep.ACCOUNT, progress(OnboardingStep.ACCOUNT), main = true, tall = true) {
      AwaitingApprovalScreen(code = "K7QD-2M9X-FA4T", deviceKeyQr = "MCGK1:AAAA")
    },
    Shot("25b-ingresso-attesa-solo-codice", OnboardingStep.ACCOUNT, progress(OnboardingStep.ACCOUNT), tall = true) {
      AwaitingApprovalScreen(code = "K7QD-2M9X-FA4T", deviceKeyQr = null)
    },
    Shot("26a-ingresso-approvato", OnboardingStep.SUMMARY, progress(OnboardingStep.SUMMARY), main = true) {
      JoinApprovedPage(chrome = Samples.pathChrome(9, 9).copy(progress = null), onFinish = {})
    },
  )
}
