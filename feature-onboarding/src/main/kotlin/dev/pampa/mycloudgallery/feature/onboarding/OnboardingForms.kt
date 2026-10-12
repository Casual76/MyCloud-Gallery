package dev.pampa.mycloudgallery.feature.onboarding

import dev.pampa.mycloudgallery.feature.onboarding.join.JoinNameForm
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinPasswordForm
import dev.pampa.mycloudgallery.feature.onboarding.join.ManualJoinForm
import dev.pampa.mycloudgallery.feature.onboarding.recovery.RecoveryForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.AccountForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.AddressForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.AreasForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.BackupSourcesForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.BatteryForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.FoldersForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.KeyForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.PresetForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.RolesForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.SecurityNoticeForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.VpnForm

/**
 * Tutto cio' che l'utente sta scrivendo o scegliendo, un modulo per pagina. Sta nel ViewModel perche' un cambio di
 * pagina (anche all'indietro) non faccia perdere cio' che era gia' scritto, e perche' una rotazione non cancelli un
 * indirizzo a meta'.
 *
 * Le **password** sono qui solo finche' la loro pagina e' in scena: ogni pagina che ne ha una le svuota quando esce
 * dalla composizione (`DisposableEffect`), e non finiscono mai in uno stato salvato (`rememberSaveable`): una rotazione
 * le cancella, ed e' voluto.
 */
internal class OnboardingForms {
  val address = AddressForm()
  val account = AccountForm()
  val areas = AreasForm()
  val folders = FoldersForm()
  val backupSources = BackupSourcesForm()
  val roles = RolesForm(indexer = true, preparer = true)
  val preset = PresetForm()
  val battery = BatteryForm()
  val vpn = VpnForm()
  val key = KeyForm()
  val securityNotice = SecurityNoticeForm()
  val manualJoin = ManualJoinForm()
  val joinPassword = JoinPasswordForm()
  val joinName = JoinNameForm()
  val recovery = RecoveryForm()
}
