package dev.pampa.mycloudgallery.feature.update.ui

import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.foundation.UpdateChannel
import dev.pampa.mycloudgallery.feature.update.CheckState
import dev.pampa.mycloudgallery.feature.update.UpdatesState
import dev.pampa.mycloudgallery.feature.update.install.InstallStep
import dev.pampa.mycloudgallery.feature.update.policy.UpdateProblem
import dev.pampa.mycloudgallery.feature.update.source.UpdateSourceConfig
import java.io.File

/** Gli stati di esempio della pagina e dell'avviso, per le prove e gli screenshot. */
internal object UpdateSamples {
  const val SIZE: Long = 115_343_360L

  val offer = AvailableAppUpdate(
    version = "1.1.0",
    changelog = "### Novità\n- **Album** più veloci da aprire.\n- La mappa si ricorda dove eri.\n- Corretto il backup dei video lunghi e delle foto in serie.",
    releaseTag = "stable-mycloud-gallery-v1.1.0",
    apkAsset = "mycloud-gallery-1.1.0.apk",
    downloadUrl = UpdateSourceConfig.downloadUrl("stable-mycloud-gallery-v1.1.0", "mycloud-gallery-1.1.0.apk"),
    sizeBytes = SIZE,
    sha256 = "a".repeat(64),
  )

  private val base = UpdatesState(installedVersion = "1.0.0")
  private val file = File("mycloud-gallery-1.1.0.apk")

  val idle = base
  val upToDate = base.copy(check = CheckState.UpToDate)
  val checking = base.copy(check = CheckState.Checking)
  val unavailable = base.copy(check = CheckState.Unavailable)
  val found = base.copy(check = CheckState.Found(offer))
  val foundBeta = base.copy(channel = UpdateChannel.BETA, check = CheckState.Found(offer.copy(version = "1.1.0-beta.03")))
  val metered = found.copy(askMetered = true)
  val downloading = found.copy(install = InstallStep.Downloading(0.42f, (SIZE * 0.42).toLong(), SIZE))
  val verifying = found.copy(install = InstallStep.Verifying)
  val installing = found.copy(install = InstallStep.Installing)
  val ready = found.copy(install = InstallStep.Ready(file))
  val interrupted = found.copy(install = InstallStep.Ready(file, interrupted = true))
  val awaiting = found.copy(install = InstallStep.AwaitingUser)
  val permission = found.copy(install = InstallStep.NeedsPermission)
  val signature = found.copy(install = InstallStep.Failed(UpdateProblem.OTHER_SIGNATURE))
  val noSpace = found.copy(install = InstallStep.Failed(UpdateProblem.NO_SPACE))
  val unusable = base.copy(check = CheckState.Unusable(offer.copy(sha256 = ""), UpdateProblem.CHECKSUM_MISSING))
  val justUpdated = UpdatesState(installedVersion = "1.1.0", check = CheckState.UpToDate, justUpdated = "1.1.0")
}
