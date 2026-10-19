package dev.pampa.mycloudgallery.core.backup.free

import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.VersionRole
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasAreaLookup
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.nas.ops.backup.VersionPaths
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// Fase 5B, P9 (docs/fase5-progetto.md D22, E-D1, E-D15, §1.4 "5B"): l'ELENCO DELLE COPIE DI TUTTE LE VERSIONI per "Ovunque". La 4B mette
// nel cestino del NAS le copie del media che il viewer conosce; dalla 5B un media ha anche le versioni precedenti in `Versioni/` (D17), e
// "Ovunque" deve prenderle tutte, **elencate nel foglio** (E-D15). Questo file le elenca dal catalogo locale; `:app` (P14) le passa come oggi
// al piano `Trash` di core-nasops con la conferma dell'utente (D2: core-backup non toglie niente dal NAS). Nessuna lettura del NAS.

/** In quale area sta una copia di "Ovunque": il foglio lo dice ("lo vedono anche i familiari", "solo tu"). */
enum class EverywhereArea(override val code: String) : StableCode {
  PRIVATE("private"),
  COMMON("common"),

  /** Una share dell'account di area non dichiarata: si elenca comunque (la decide il NAS, E-D11). */
  UNKNOWN("unknown"),
}

/**
 * Una copia sul NAS di una versione del media: cio' che il piano `Trash` della 4B vuole (share, percorso, timbro) e cio' che il foglio
 * mostra (ruolo, attuale, area, `Versioni/`). Percorso e share sono dati dell'utente: mai nei log.
 */
class EverywhereCopy(
  val versionId: VersionId,
  val role: VersionRole,
  val isCurrent: Boolean,
  val area: EverywhereArea,
  val location: LocationId,
  val share: String,
  val path: SharePath,
  val stamp: NasStamp,
  /** Sta in `Versioni/` (D17): una versione precedente archiviata. */
  val inVersionsFolder: Boolean,
) {
  override fun toString(): String = "EverywhereCopy(${role.code}${if (isCurrent) ", attuale" else ""}, ${area.code}, ${stamp.size} B)"
}

/** Perche' "Ovunque" non elenca niente per un media. */
enum class EverywhereBlock(override val code: String) : StableCode {
  /** Il media non c'e' (piu') nel catalogo. */
  NOT_FOUND("not_found"),

  /** D13: in debug con un account di prova, un media con una copia del telefono fuori da `mcg-prova` non si tocca. */
  OUTSIDE_TEST_FENCE("outside_test_fence"),

  /** Nessun account noto. */
  NOT_AVAILABLE("not_available"),
}

/**
 * Le copie di un media per "Ovunque" (E-D15): tutte le copie **presenti** sul NAS dell'account, di **ogni** versione (l'attuale, l'originale,
 * le intermedie), e le copie del telefono. [unlisted] conta le copie che il catalogo conosce ma che non si possono passare al cestino (un
 * percorso che non e' di un media): restano dove sono e il foglio lo dice.
 */
class EverywherePlan(
  val mediaId: MediaId,
  val nasCopies: List<EverywhereCopy>,
  val phoneCopies: List<PhoneRef>,
  val unlisted: Int,
  val blocked: EverywhereBlock? = null,
) {
  /** Quante versioni hanno almeno una copia elencata. */
  val versions: Int get() = nasCopies.map { it.versionId }.distinct().size

  /** Le versioni precedenti (non l'attuale) elencate: il foglio le nomina ("anche 2 versioni precedenti"). */
  val previousVersions: Int get() = nasCopies.filter { !it.isCurrent }.map { it.versionId }.distinct().size

  override fun toString(): String = "EverywherePlan(${nasCopies.size} copie, $versions versioni, ${phoneCopies.size} sul telefono)"
}

/**
 * L'elenco di "Ovunque" (D22, E-D15) dal catalogo locale. Solo le posizioni **dell'account** ([account]): le aree private altrui non si
 * vedono e non si toccano. Con la recinzione di D13 un media con una copia del telefono fuori da `mcg-prova` non si elenca affatto.
 */
class EverywhereCopies(
  private val catalog: FreeSpaceCatalog,
  private val account: () -> AccountId?,
  private val areas: () -> NasAreaLookup,
  private val fence: BackupFence? = null,
) {

  /** Le copie di [mediaId]. */
  suspend fun list(mediaId: MediaId): EverywherePlan = list(listOf(mediaId)).single()

  /** Le copie di ogni media di [ids], nello stesso ordine. */
  suspend fun list(ids: List<MediaId>): List<EverywherePlan> {
    val me = account()
    val lookup = areas()
    val found = catalog.media(ids.toSet()).associateBy { it.id }
    return ids.map { id ->
      if (me == null) return@map EverywherePlan(id, emptyList(), emptyList(), 0, EverywhereBlock.NOT_AVAILABLE)
      val fm = found[id] ?: return@map EverywherePlan(id, emptyList(), emptyList(), 0, EverywhereBlock.NOT_FOUND)
      val media = fm.media
      val phones = media.locations.filter { it.isPresent && it.isPhone }
      val outside = fence != null && phones.any { !fence.allowsPhoneFolder(me.value, (it.location as Location.Phone).relativePath) }
      if (outside) return@map EverywherePlan(id, emptyList(), emptyList(), 0, EverywhereBlock.OUTSIDE_TEST_FENCE)
      var unlisted = 0
      val copies = media.locations.filter { it.isPresent && it.isNas }.mapNotNull { loc ->
        val nas = loc.location as Location.Nas
        if (nas.account != me) return@mapNotNull null
        val path = FreeSpaceEvaluator.mediaPathOf(loc)
        if (path == null) {
          unlisted++
          return@mapNotNull null
        }
        val version = media.version(loc.versionId)
        EverywhereCopy(
          versionId = loc.versionId,
          role = if (version.kind == VersionKind.ORIGINAL) VersionRole.ORIGINAL else VersionRole.EDITED,
          isCurrent = loc.versionId == media.currentVersionId,
          area = when (lookup.areaOf(nas.account, nas.shareKey)) {
            NasArea.PRIVATE -> EverywhereArea.PRIVATE
            NasArea.COMMON -> EverywhereArea.COMMON
            null -> EverywhereArea.UNKNOWN
          },
          location = loc.id,
          share = nas.share,
          path = path,
          stamp = nas.stampOf(loc.stamp),
          inVersionsFolder = VersionPaths.isInVersions(path),
        )
      }
      // L'attuale prima (il solito percorso), poi le versioni dalla piu' vecchia; dentro una versione l'area privata prima.
      val order = media.versions.sortedWith(compareBy({ it.createdAtMillis }, { it.id.value })).map { it.id }
      val sorted = copies.sortedWith(
        compareBy<EverywhereCopy>({ !it.isCurrent }, { order.indexOf(it.versionId) }, { it.area.ordinal }, { it.location.value }),
      )
      val refs = phones.mapNotNull { loc ->
        val phone = loc.location as Location.Phone
        val msId = phone.mediaStoreId ?: return@mapNotNull null
        val msVersion = phone.mediaStoreVersion ?: return@mapNotNull null
        PhoneRef(phone.volume, msId, msVersion)
      }
      EverywherePlan(id, sorted, refs, unlisted)
    }
  }
}
