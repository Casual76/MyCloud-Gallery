package dev.pampa.mycloudgallery.feature.backup.versions

import dev.pampa.mycloudgallery.core.backup.contract.NasVersionPlan
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.RestoreAvailability
import dev.pampa.mycloudgallery.core.backup.contract.VersionArea
import dev.pampa.mycloudgallery.core.backup.contract.VersionEntry
import dev.pampa.mycloudgallery.core.backup.contract.VersionPlace
import dev.pampa.mycloudgallery.core.backup.contract.VersionRole
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.VersionId
import java.util.UUID

/** I dati di prova delle versioni e delle correzioni (P12). */
internal object VersionSamples {

  fun uuid(n: Long): String = UUID(0x7E510AAL, n).toString()

  val media = MediaId(uuid(1))

  fun versionId(n: Int) = VersionId(uuid(100L + n))

  val phoneRow = PhoneRef("external_primary", 4_242L, "1")

  /** L'ora del NAS in cui e' comparsa la versione [n]: 15 ottobre 2026, ore 12:32 UTC piu' i giorni. */
  private fun savedAt(days: Int): Long = 1_792_067_520_000L + days * 86_400_000L

  /** L'originale: in `Versioni/`, controllato. */
  fun original(safe: Boolean = true, current: Boolean = false) = VersionEntry(
    versionId = versionId(1),
    role = VersionRole.ORIGINAL,
    isCurrent = current,
    size = 4_812_000L,
    savedAtNasMillis = savedAt(0),
    places = if (current) setOf(VersionPlace.NAS_USUAL_PATH) else setOf(VersionPlace.NAS_VERSIONS_FOLDER),
    safe = safe,
  )

  /** Una versione intermedia: in `Versioni/`. */
  fun intermediate(n: Int = 2, safe: Boolean = true) = VersionEntry(
    versionId = versionId(n),
    role = VersionRole.EDITED,
    isCurrent = false,
    size = 4_100_000L + n,
    savedAtNasMillis = savedAt(n),
    places = setOf(VersionPlace.NAS_VERSIONS_FOLDER),
    safe = safe,
  )

  /** Quella di adesso: al solito percorso e sul telefono. */
  fun current(n: Int = 9, safe: Boolean = true, onPhone: Boolean = true) = VersionEntry(
    versionId = versionId(n),
    role = VersionRole.EDITED,
    isCurrent = true,
    size = 3_200_000L,
    savedAtNasMillis = savedAt(n),
    places = if (onPhone) setOf(VersionPlace.PHONE, VersionPlace.NAS_USUAL_PATH) else setOf(VersionPlace.NAS_USUAL_PATH),
    safe = safe,
  )

  fun plan(
    versions: List<VersionEntry> = listOf(original(), intermediate(), current()),
    restore: RestoreAvailability = RestoreAvailability.AVAILABLE,
    area: VersionArea = VersionArea.OWN_PRIVATE,
    media: MediaId = this.media,
  ) = NasVersionPlan(media, versions, area, restore)
}
