package dev.pampa.mycloudgallery.feature.backup.free

import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceExclusion
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceItem
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpacePlan
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryFreeSpace
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.VersionId
import java.util.UUID

/** I dati di prova di "Libera spazio" (P12): i media, i piani e il servizio di riferimento di core-backup con i guasti scelti dal test. */
internal object FreeSpaceSamples {

  /** Oggi come `aaaammgg` per il servizio finto (lo stesso del suo default). */
  const val TODAY = 20_261_019

  fun uuid(n: Long): String = UUID(0x12C0FFEEL, n).toString()

  fun mediaId(n: Int) = MediaId(uuid(n.toLong()))

  fun versionId(n: Int) = VersionId(uuid(1_000_000L + n))

  fun phone(n: Int) = PhoneRef("external_primary", 10_000L + n, "1")

  /** Un elemento liberabile: [n]-esimo, di [size] byte, con la copia privata gia' decisa dal catalogo. */
  fun item(n: Int, size: Long = 4_000_000L, video: Boolean = false, day: Int = 20_240_315): FreeSpaceItem = FreeSpaceItem(
    mediaId = mediaId(n),
    versionId = versionId(n),
    phone = phone(n),
    mime = if (video) "video/mp4" else "image/jpeg",
    size = size,
    safeCopy = NasStamp(size, 1_700_000_000_000L + n, 500L + n),
    isVideo = video,
    localDay = day,
  )

  /** Il servizio di riferimento con [count] candidati vecchi di mesi. */
  fun space(count: Int = 5, clock: FakeBackupClock = FakeBackupClock(), size: Long = 4_000_000L): InMemoryFreeSpace =
    InMemoryFreeSpace(clock = clock, todayLocalDay = TODAY).also { space ->
      for (n in 1..count) space.candidates += item(n, size = size, video = n % 3 == 0)
    }

  /** Un piano pronto per le schermate e gli screenshot, senza passare dal servizio (cosi' i numeri sono quelli del progetto: 1.240 media). */
  fun plan(
    items: Int = 1_240,
    itemSize: Long = 15_000_000L,
    excluded: Map<FreeSpaceExclusion, Int> = emptyMap(),
    permanent: Boolean = false,
    needsGuardian: Int = 0,
    recentDays: Int = 30,
  ): FreeSpacePlan = FreeSpacePlan(
    filter = FreeSpaceFilter(excludeRecentDays = recentDays),
    permanent = permanent,
    items = (1..items).map { item(it, itemSize, video = it % 4 == 0) },
    excluded = excluded,
    needsGuardian = needsGuardian,
  )

  /** Gli esclusi di una Libreria vera: ogni motivo del §2.5 con un numero diverso. */
  val excludedSample: Map<FreeSpaceExclusion, Int> = linkedMapOf(
    FreeSpaceExclusion.NOT_VERIFIED to 12,
    FreeSpaceExclusion.VERSION_NOT_SAFE to 2,
    FreeSpaceExclusion.IN_PROGRESS_OR_ERROR to 3,
    FreeSpaceExclusion.RECENT to 214,
  )
}
