package dev.pampa.mycloudgallery.feature.update.ui

import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.foundation.UpdateChannel
import dev.pampa.mycloudgallery.feature.update.CheckState
import dev.pampa.mycloudgallery.feature.update.UpdatesState
import dev.pampa.mycloudgallery.feature.update.install.InstallStep
import dev.pampa.mycloudgallery.feature.update.policy.UpdateProblem
import dev.pampa.mycloudgallery.feature.update.source.UpdateSourceConfig

/** Gli stati di esempio della pagina e dell'avviso, per le prove e gli screenshot. */
internal object UpdateSamples {
  const val SIZE: Long = 115_343_360L

  val offer = AvailableAppUpdate(
    version = "1.1.0",
    changelog = "### Novità\n- **Album** più veloci da aprire.\n- La mappa si ricorda dove eri.\n- Corretto il backup dei video lunghi.",
    releaseTag = "stable-mycloud-gallery-v1.1.0",
    apkAsset = "mycloud-gallery-1.1.0.apk",
    downloadUrl = UpdateSourceConfig.downloadUrl("stable-mycloud-gallery-v1.1.0", "mycloud-gallery-1.1.0.apk"),
    sizeBytes = SIZE,
    sha256 = "a".repeat(64),
  )

  private val base = UpdatesState(installedVersion = "1.0.0")

  val idle = base
  val upToDate = base.copy(check = CheckState.UpToDate)
  val checking = base.copy(check = CheckState.Checking)
  val unavailable = base.copy(check = CheckState.Unavailable)
  val found = base.copy(check = CheckState.Found(offer))
  val foundBeta = base.copy(channel = UpdateChannel.BETA, check = CheckState.Found(offer.copy(version = "1.1.0-beta.3")))
  val metered = found.copy(askMetered = true)
  val downloading = found.copy(install = InstallStep.Downloading(0.42f, (SIZE * 0.42).toLong(), SIZE))
  val verifying = found.copy(install = InstallStep.Verifying)
  val awaiting = found.copy(install = InstallStep.AwaitingUser)
  val permission = found.copy(install = InstallStep.NeedsPermission)
  val signature = found.copy(install = InstallStep.Failed(UpdateProblem.OTHER_SIGNATURE))
  val unusable = base.copy(check = CheckState.Unusable(offer.copy(sha256 = ""), UpdateProblem.CHECKSUM_MISSING))
}
