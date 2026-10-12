package dev.pampa.mycloudgallery.feature.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import dev.pampa.mycloudgallery.core.family.contract.AreaCandidate
import dev.pampa.mycloudgallery.core.family.contract.ExcludedFolder
import dev.pampa.mycloudgallery.core.family.contract.ExclusionReason
import dev.pampa.mycloudgallery.core.family.contract.FolderProposal
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.ShareAccess
import dev.pampa.mycloudgallery.core.rules.platform.health.BackgroundHealth
import dev.pampa.mycloudgallery.feature.onboarding.join.ScannerSlot
import dev.pampa.mycloudgallery.feature.onboarding.model.FoundNasUi
import dev.pampa.mycloudgallery.feature.onboarding.model.HomeCandidateUi
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepProgressInfo

/** I dati d'esempio degli screenshot e dei test delle pagine. */
internal object Samples {

  val home = HomeCandidateUi("192.168.178.0/24", "192.168.178.1", "fritz.box", canConfirm = true)

  val homeNoUla = home.copy(canConfirm = false)

  val found = listOf(
    FoundNasUi("WDMyCloudEX2Ultra", "192.168.178.58"),
    FoundNasUi("NAS-Studio", "192.168.178.61"),
  )

  val areas = listOf(
    AreaCandidate("marta", NasResult.Ok(ShareAccess.READ_WRITE), isPublic = false, hasFamily = false),
    AreaCandidate("Public", NasResult.Ok(ShareAccess.READ_WRITE), isPublic = true, hasFamily = false),
    AreaCandidate("NAS", NasResult.Ok(ShareAccess.READ_ONLY), isPublic = true, hasFamily = false),
    AreaCandidate("Famiglia", NasResult.Ok(ShareAccess.DENIED), isPublic = false, hasFamily = false),
    AreaCandidate("Archivio", NasResult.Err(NasError.Timeout), isPublic = null, hasFamily = false),
  )

  val areasWithFamily = areas.map { if (it.share == "Public") it.copy(hasFamily = true) else it }

  val areasNoAccess = listOf(
    AreaCandidate("marta", NasResult.Ok(ShareAccess.DENIED), isPublic = false, hasFamily = false),
    AreaCandidate("Public", NasResult.Ok(ShareAccess.MISSING), isPublic = null, hasFamily = false),
  )

  val areasOneWritable = listOf(
    AreaCandidate("marta", NasResult.Ok(ShareAccess.READ_WRITE), isPublic = false, hasFamily = false),
    AreaCandidate("Public", NasResult.Ok(ShareAccess.READ_ONLY), isPublic = true, hasFamily = false),
  )

  val folders = FolderProposal(
    excluded = listOf(
      ExcludedFolder("Musica/Raccolte", ExclusionReason.MUSIC),
      ExcludedFolder("Film e serie/2024", ExclusionReason.MOVIES),
      ExcludedFolder("Foto/.thumbs", ExclusionReason.SMALL_COVERS),
      ExcludedFolder("@eaDir", ExclusionReason.SYSTEM_FOLDER),
      ExcludedFolder("Backup di WhatsApp", ExclusionReason.APP_FOLDER),
    ),
    scanned = 3_842,
    truncated = false,
  )

  val foldersTruncated = folders.copy(scanned = 5_000, truncated = true)

  val codeGroups = listOf("K7QD", "2M9X", "FA4T", "ZC5P", "3N6R", "1V8W", "9HBE")

  /** Android con tutto da sistemare: la batteria ottimizzata, l'app in sospensione, "pausa se inutilizzata" accesa. */
  val healthTodo: BackgroundHealth = BackgroundHealth.UNKNOWN.copy(
    ignoringBatteryOpt = false,
    backgroundRestricted = true,
    unusedAppRestrictions = dev.pampa.mycloudgallery.core.rules.platform.health.UnusedAppRestrictions.ENABLED,
  )

  val healthDone: BackgroundHealth = BackgroundHealth.UNKNOWN.copy(
    ignoringBatteryOpt = true,
    backgroundRestricted = false,
    unusedAppRestrictions = dev.pampa.mycloudgallery.core.rules.platform.health.UnusedAppRestrictions.DISABLED,
  )

  val healthUnknown: BackgroundHealth = BackgroundHealth.UNKNOWN

  fun chrome(step: OnboardingStep, total: Int = OnboardingStep.entries.size, banner: dev.pampa.mycloudgallery.feature.onboarding.model.OnboardingBanner? = null, warnings: List<dev.pampa.mycloudgallery.core.family.contract.OnboardingWarning> = emptyList()): StepChrome =
    StepChrome(progress = StepProgressInfo(step.number, total), onBack = {}, banner = banner, warnings = warnings)

  /** La barra del cammino per le pagine di "entra" e di "recupera": il numero e' quello nel piano di quel percorso. */
  fun pathChrome(number: Int, total: Int, warnings: List<dev.pampa.mycloudgallery.core.family.contract.OnboardingWarning> = emptyList()): StepChrome =
    StepChrome(progress = StepProgressInfo(number, total), onBack = {}, warnings = warnings)

  /** Un riquadro al posto della fotocamera (una fotocamera non gira su Robolectric). */
  val scanner: ScannerSlot = { _, _, modifier ->
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(
        text = "Mirino della fotocamera",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
