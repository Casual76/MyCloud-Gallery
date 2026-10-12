package dev.pampa.mycloudgallery.core.database

import androidx.room.withTransaction
import dev.pampa.mycloudgallery.core.database.entity.EmbeddingEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaAliasEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaLocationEntity
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.CorrectionField
import dev.pampa.mycloudgallery.core.model.EmbeddingVector
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.FingerprintMatch
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.LocationKey
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.Media
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaLocation
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.MediaVersion
import dev.pampa.mycloudgallery.core.model.NasAreaLookup
import dev.pampa.mycloudgallery.core.model.NasShare
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import dev.pampa.mycloudgallery.core.model.Presence
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.model.WorkState
import dev.pampa.mycloudgallery.core.model.domain.ContentCandidate
import dev.pampa.mycloudgallery.core.model.domain.KnownLocation
import dev.pampa.mycloudgallery.core.model.domain.LinkContext
import dev.pampa.mycloudgallery.core.model.domain.LinkDecision
import dev.pampa.mycloudgallery.core.model.domain.LinkingRules
import dev.pampa.mycloudgallery.core.model.domain.LocationRules
import dev.pampa.mycloudgallery.core.model.domain.MergePlan
import dev.pampa.mycloudgallery.core.model.domain.MergeRefusal
import dev.pampa.mycloudgallery.core.model.domain.MergeRules
import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.model.domain.SafetyReport
import dev.pampa.mycloudgallery.core.model.domain.SafetyRules
import dev.pampa.mycloudgallery.core.model.domain.VersionPlan
import dev.pampa.mycloudgallery.core.model.domain.VersionRules
import dev.pampa.mycloudgallery.core.model.domain.WorkRules

/**
 * Il catalogo dei media: applica **in una transazione** le decisioni delle regole di dominio
 * (`core-model/domain`) al database. E' l'unico punto che scrive il grafo media/versioni/posizioni,
 * cosi' i campi derivati (`presence`, `safety`, `sort_time`) e gli stati di lavoro restano coerenti
 * con cio' che dicono le regole.
 *
 * Le regole decidono, il catalogo esegue: qui non c'e' logica su "che cosa e' lo stesso media".
 *
 * [localAccount] e' l'account NAS di questo dispositivo (o profilo): il proprietario dei media del
 * telefono e il riferimento per "al sicuro". [clock] e' l'ora di riferimento (piano §1: dell'ora del NAS
 * quando c'e').
 */
class MediaCatalog(
  private val database: GalleryDatabase,
  private val localAccount: AccountId,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  private val mediaDao = database.mediaDao()
  private val versionDao = database.versionDao()
  private val locationDao = database.locationDao()
  private val shareDao = database.nasShareDao()
  private val workDao = database.workStateDao()
  private val embeddingDao = database.embeddingDao()
  private val personalDao = database.personalDao()
  private val mergeDao = database.mergeDao()

  /** Che cosa fare quando le regole chiedono l'hash completo e chi chiama non puo' calcolarlo. */
  enum class WhenUndecided {
    /** Non toccare nulla: si riprovera' con l'hash completo. */
    WAIT,

    /** Creare un media separato (nel dubbio non si unisce); si unira' quando le impronte lo proveranno. */
    KEEP_SEPARATE,
  }

  /** Esito di [record]: la decisione applicata e, se c'e', dove e' finita l'osservazione. */
  data class RecordOutcome(
    val decision: LinkDecision,
    val mediaId: MediaId?,
    val locationId: LocationId?,
  )

  sealed interface MergeOutcome {
    data class Merged(val survivor: MediaId, val loser: MediaId) : MergeOutcome

    data class Refused(val reason: MergeRefusal) : MergeOutcome

    data object NotFound : MergeOutcome
  }

  // --- Osservazioni delle scansioni ------------------------------------------------------------------

  /** Registra cio' che una scansione ha visto in un posto (piano 3.1, 4.2). */
  suspend fun record(observation: Observation, whenUndecided: WhenUndecided = WhenUndecided.WAIT): RecordOutcome =
    database.withTransaction {
      val decided = LinkingRules.decide(observation, loadContext(observation))
      val decision = if (whenUndecided == WhenUndecided.KEEP_SEPARATE) keepSeparate(decided) else decided
      val target = apply(decision, observation)
      RecordOutcome(decision, target?.first, target?.second)
    }

  /** Un posto non contiene piu' il file (riga sparita dal MediaStore, file non trovato sul NAS). */
  suspend fun markVanished(key: LocationKey): Boolean = database.withTransaction {
    val entity = locationDao.byKey(key.value) ?: return@withTransaction false
    val location = entity.toDomain()
    locationDao.update(LocationRules.vanished(location, clock()).toEntity())
    refreshDerived(mediaOf(location.id))
    true
  }

  /** Un volume del telefono non e' piu' montato (microSD tolta, E-P6): niente e' stato cancellato. */
  suspend fun markVolumeUnavailable(volume: String) = database.withTransaction {
    val touched = locationDao.presentOnVolume(volume).map { entity ->
      val location = entity.toDomain()
      locationDao.update(LocationRules.unavailable(location).toEntity())
      mediaOf(location.id)
    }
    touched.toSet().forEach { refreshDerived(it) }
  }

  /** Dimentica le posizioni assenti oltre la tolleranza. I media restano, con le altre posizioni. */
  suspend fun purgeExpiredAbsences(): Int = database.withTransaction {
    val now = clock()
    val shortest = minOf(LocationRules.PHONE_ABSENCE_TOLERANCE_MILLIS, LocationRules.NAS_ABSENCE_TOLERANCE_MILLIS)
    val expired = locationDao.absentSince(now - shortest).map { it.toDomain() }.filter { LocationRules.isExpired(it, now) }
    val touched = expired.map { mediaOf(it.id) }.toSet()
    expired.forEach { locationDao.delete(it.id.value) }
    touched.forEach { refreshDerived(it) }
    expired.size
  }

  // --- Operazioni fatte dall'app ---------------------------------------------------------------------

  /**
   * Registra o aggiorna una share con la sua area (configurazione di famiglia). Proprietario e "al
   * sicuro" si ricalcolano per i media che hanno una copia in quella share: gli unici che cambiano.
   */
  suspend fun registerShare(share: NasShare) = database.withTransaction {
    shareDao.upsert(share.toEntity())
    val areas = areas()
    locationDao.mediaIdsInShare(share.account.value, share.shareKey).forEach { refreshDerived(MediaId(it), areas) }
  }

  /**
   * Una copia scritta dall'app stessa (backup verificato, "Scarica sul telefono", copia privata
   * ricreata: E-B13). Nessuna euristica: l'app sa quali byte ha scritto. [verifiedAtMillis] e' l'ora
   * della rilettura con hash uguale, se c'e' stata.
   */
  suspend fun attachCopy(
    mediaId: MediaId,
    versionId: VersionId,
    location: Location,
    stamp: FileStamp,
    verifiedAtMillis: Long?,
  ): LocationId = database.withTransaction {
    val now = clock()
    val version = requireMedia(mediaId).version(versionId)
    require(stamp.size == version.fingerprint.size) { "La copia ha una dimensione diversa dalla versione." }
    require(verifiedAtMillis == null || version.fingerprint.fullHash != null) { "Si verifica solo contro un hash completo noto." }
    val existing = locationDao.byKey(location.key.value)
    val id = if (existing == null) {
      locationDao.insert(locationEntity(0, versionId, location, stamp, LocationState.PRESENT, null, verifiedAtMillis, now))
    } else {
      val previousOwner = mediaOf(LocationId(existing.locationId))
      locationDao.update(locationEntity(existing.locationId, versionId, location, stamp, LocationState.PRESENT, null, verifiedAtMillis, now))
      if (previousOwner != mediaId) refreshDerived(previousOwner)
      existing.locationId
    }
    refreshDerived(mediaId)
    LocationId(id)
  }

  /** Uno spostamento fatto dall'app (condivisione nell'area comune, 7.4): stessa posizione, altro posto. */
  suspend fun relocate(locationId: LocationId, to: Location, stamp: FileStamp, verifiedAtMillis: Long?) = database.withTransaction {
    val before = requireLocation(locationId)
    val moved = before.copy(
      location = to,
      stamp = stamp,
      state = LocationState.PRESENT,
      absentSinceMillis = null,
      verifiedAtMillis = verifiedAtMillis,
      lastSeenAtMillis = clock(),
    )
    locationDao.update(moved.toEntity())
    refreshDerived(mediaOf(locationId))
  }

  /**
   * I byte in [locationId] sono stati riletti e hanno dato [readBack]. Vero se coincidono con l'hash
   * completo della versione (copia verificata); falso se diversi (copia non integra, E-B8) o se
   * l'hash della versione non e' noto. In caso di falso la verifica precedente decade.
   */
  suspend fun markVerified(locationId: LocationId, readBack: Sha256, atMillis: Long): Boolean = database.withTransaction {
    val before = requireLocation(locationId)
    val expected = versionDao.get(before.versionId.value)?.fullHash
    val ok = expected != null && expected == readBack.hex
    locationDao.update(before.copy(verifiedAtMillis = if (ok) atMillis else null).toEntity())
    refreshDerived(mediaOf(locationId))
    ok
  }

  /** I metadati letti (fase META) o corretti di una versione. */
  suspend fun updateMetadata(versionId: VersionId, metadata: MediaMetadata) = database.withTransaction {
    versionDao.upsertMetadata(metadata.toEntity(versionId, clock()))
    versionDao.get(versionId.value)?.let { refreshDerived(MediaId(it.mediaId)) }
  }

  /** Una correzione (data, luogo, rotazione) scritta nei file [holders] come nuova versione (E-L8). */
  suspend fun correct(
    mediaId: MediaId,
    fields: Set<CorrectionField>,
    fingerprint: Fingerprint,
    holders: Map<LocationId, FileStamp>,
    metadata: MediaMetadata?,
  ): VersionId = database.withTransaction {
    val media = requireMedia(mediaId)
    val plan = VersionRules.corrected(media, fields, fingerprint, holders, clock())
    persist(media, plan)
    val newVersion = checkNotNull(plan.newVersion).id
    if (metadata != null) {
      versionDao.upsertMetadata(metadata.toEntity(newVersion, clock()))
      refreshDerived(mediaId)
    }
    newVersion
  }

  /** "Ripristina originale" (5.4). */
  suspend fun restoreOriginal(mediaId: MediaId) = database.withTransaction {
    val media = requireMedia(mediaId)
    persist(media, VersionRules.restoreOriginal(media))
  }

  suspend fun setPersonalFlags(mediaId: MediaId, flags: PersonalFlags) = database.withTransaction {
    personalDao.upsert(flags.toEntity(mediaId, clock()))
  }

  suspend fun putEmbedding(mediaId: MediaId, vector: EmbeddingVector) = database.withTransaction {
    embeddingDao.upsert(vector.toEntity(mediaId, clock()))
  }

  suspend fun updateWork(mediaId: MediaId, state: WorkState) = database.withTransaction {
    workDao.upsert(listOf(state.toEntity(mediaId)))
  }

  /**
   * Unisce due media che si sono rivelati lo stesso (`MergeRules`: originali con hash completi
   * uguali). Ogni riferimento del perdente passa al sopravvissuto e il perdente diventa un alias.
   */
  suspend fun merge(a: MediaId, b: MediaId): MergeOutcome = database.withTransaction { mergeInTransaction(a, b) }

  // --- Letture ---------------------------------------------------------------------------------------

  /** L'identita' attuale di [id]: se stessa, o il sopravvissuto se e' stata assorbita da un'unione. */
  suspend fun resolve(id: MediaId): MediaId? =
    if (mediaDao.get(id.value) != null) id else mediaDao.aliasTarget(id.value)?.let(::MediaId)

  suspend fun media(id: MediaId): Media? = resolve(id)?.let { mediaDao.graph(it.value)?.toDomain() }

  suspend fun safety(id: MediaId): SafetyReport? =
    media(id)?.let { SafetyRules.evaluate(it, localAccount, areas()) }

  suspend fun workStates(id: MediaId): List<WorkState> = workDao.forMedia(id.value).map { it.toDomain() }

  // --- Interni ---------------------------------------------------------------------------------------

  private suspend fun loadContext(observation: Observation): LinkContext {
    val key = observation.location.key.value
    val fingerprint = observation.fingerprint
    val atSameKey = locationDao.byKey(key)?.let { known(it) }
    val atSameMediaStoreId = (observation.location as? Location.Phone)?.let { phone ->
      phone.mediaStoreId?.let { id ->
        locationDao.byMediaStoreId(phone.volume, id).firstOrNull { it.locationKey != key }?.let { known(it) }
      }
    }
    val sameContent = versionDao.byQuickFingerprint(fingerprint.size, fingerprint.quickHash.hex)
      .map { ContentCandidate(MediaId(it.mediaId), it.toDomain()) }
    val vanished = locationDao.vanishedWithContent(observation.location.type.code, fingerprint.size, fingerprint.quickHash.hex)
      .filter { it.locationKey != key }
      .map { known(it) }
    return LinkContext(atSameKey, atSameMediaStoreId, sameContent, vanished)
  }

  private suspend fun known(entity: MediaLocationEntity): KnownLocation {
    val graph = checkNotNull(mediaDao.graph(mediaOf(LocationId(entity.locationId)).value))
    val hint = graph.versions.firstOrNull { it.version.versionId == entity.versionId }?.metadata?.captureTimeHint()
    return KnownLocation(graph.toDomain(), LocationId(entity.locationId), hint)
  }

  private fun keepSeparate(decision: LinkDecision): LinkDecision = when (decision) {
    is LinkDecision.NeedsFullHash -> LinkDecision.NewMedia
    is LinkDecision.Replaced ->
      if (decision.next is LinkDecision.NeedsFullHash) decision.copy(next = LinkDecision.NewMedia) else decision
    else -> decision
  }

  private suspend fun apply(decision: LinkDecision, observation: Observation): Pair<MediaId, LocationId>? {
    val now = clock()
    return when (decision) {
      is LinkDecision.SameFile -> {
        val before = requireLocation(decision.locationId)
        val after = before.copy(
          location = observation.location,
          stamp = observation.stamp,
          state = LocationState.PRESENT,
          absentSinceMillis = null,
          // Timbro cambiato: la verifica valeva per i byte di prima.
          verifiedAtMillis = if (decision.stampChanged) null else before.verifiedAtMillis,
          lastSeenAtMillis = now,
        )
        locationDao.update(after.toEntity())
        learnFullHash(before.versionId, observation.fingerprint)
        refreshDerived(decision.mediaId)
        decision.mediaId to decision.locationId
      }

      is LinkDecision.ContentChanged -> {
        val media = requireMedia(decision.mediaId)
        val plan = VersionRules.contentChangedAt(media, decision.locationId, observation.fingerprint, observation.stamp, now)
        persist(media, plan, observed = mapOf(decision.locationId to observation.location))
        decision.mediaId to decision.locationId
      }

      is LinkDecision.RevertedToVersion -> {
        val media = requireMedia(decision.mediaId)
        val plan = VersionRules.revertedAt(media, decision.locationId, decision.versionId, observation.stamp, now)
        persist(media, plan, observed = mapOf(decision.locationId to observation.location))
        learnFullHash(decision.versionId, observation.fingerprint)
        decision.mediaId to decision.locationId
      }

      is LinkDecision.Moved -> {
        val before = requireLocation(decision.locationId)
        val after = before.copy(
          location = observation.location,
          stamp = observation.stamp,
          state = LocationState.PRESENT,
          absentSinceMillis = null,
          verifiedAtMillis = if (observation.stamp.looksUnchangedFrom(before.stamp)) before.verifiedAtMillis else null,
          lastSeenAtMillis = now,
        )
        locationDao.update(after.toEntity())
        learnFullHash(before.versionId, observation.fingerprint)
        refreshDerived(decision.mediaId)
        decision.mediaId to decision.locationId
      }

      is LinkDecision.NewLocation -> {
        val id = locationDao.insert(
          locationEntity(0, decision.versionId, observation.location, observation.stamp, LocationState.PRESENT, null, null, now),
        )
        learnFullHash(decision.versionId, observation.fingerprint)
        refreshDerived(decision.mediaId)
        // Altri media con gli stessi byte (prova: hash completo) si uniscono subito.
        var survivor = decision.mediaId
        decision.alsoIdenticalTo.forEach { other ->
          (mergeInTransaction(survivor, other) as? MergeOutcome.Merged)?.let { survivor = it.survivor }
        }
        survivor to LocationId(id)
      }

      is LinkDecision.NeedsFullHash -> null

      LinkDecision.NewMedia -> createMedia(observation, now)

      is LinkDecision.Replaced -> {
        if (decision.next is LinkDecision.NeedsFullHash) return null
        locationDao.delete(decision.previousLocationId.value)
        refreshDerived(decision.previousMediaId)
        apply(decision.next, observation)
      }
    }
  }

  private suspend fun createMedia(observation: Observation, now: Long): Pair<MediaId, LocationId> {
    val mediaId = MediaId.random()
    val version = MediaVersion(VersionId.random(), VersionKind.ORIGINAL, null, observation.fingerprint, observation.stamp.modifiedAtMillis)
    val owner = SafetyRules.ownerForNewMedia(observation.location, localAccount, areas())
    mediaDao.insert(
      MediaEntity(
        mediaId = mediaId.value,
        kind = observation.kind.code,
        source = observation.source.code,
        ownerAccount = owner?.value,
        groupId = null,
        currentVersionId = version.id.value,
        createdAt = now,
        updatedAt = now,
        // Provvisoria: la data di scatto vera arriva con la fase META.
        sortTime = observation.captureTimeHintMillis ?: observation.stamp.modifiedAtMillis,
        presence = Presence.NONE.code,
        safety = SafetyStatus.LOST.code,
      ),
    )
    versionDao.insert(version.toEntity(mediaId))
    val locationId = locationDao.insert(
      locationEntity(0, version.id, observation.location, observation.stamp, LocationState.PRESENT, null, null, now),
    )
    workDao.upsert(WorkRules.initialStates(observation.kind, version.id, now).map { it.toEntity(mediaId) })
    refreshDerived(mediaId)
    return mediaId to LocationId(locationId)
  }

  /** Scrive un cambio di versione: versione nuova, posizioni spostate, corrente, fasi da rifare. */
  private suspend fun persist(before: Media, plan: VersionPlan, observed: Map<LocationId, Location> = emptyMap()) {
    val now = clock()
    plan.newVersion?.let { version ->
      versionDao.insert(version.toEntity(before.id))
      // Metadati provvisori: quelli del genitore, finche' la fase META non rilegge i byte nuovi.
      version.parentId?.let { parent ->
        versionDao.metadata(parent.value)?.let { versionDao.upsertMetadata(it.copy(versionId = version.id.value, updatedAt = now)) }
      }
    }
    plan.changedLocations.forEach { location ->
      val updated = observed[location.id]?.let { location.copy(location = it) } ?: location
      locationDao.update(updated.toEntity())
    }
    mediaDao.setCurrentVersion(before.id.value, plan.media.currentVersionId.value, now)
    if (plan.stagesToRedo.isNotEmpty()) {
      val states = workDao.forMedia(before.id.value).map { it.toDomain() }
      workDao.upsert(WorkRules.invalidate(states, plan.stagesToRedo, plan.media.currentVersionId, now).map { it.toEntity(before.id) })
    }
    refreshDerived(before.id)
  }

  private suspend fun mergeInTransaction(a: MediaId, b: MediaId): MergeOutcome {
    val first = mediaDao.graph(a.value) ?: return MergeOutcome.NotFound
    val second = mediaDao.graph(b.value) ?: return MergeOutcome.NotFound
    val plan = MergeRules.plan(first.toDomain(), second.toDomain(), localAccount)
    if (plan is MergePlan.Refused) return MergeOutcome.Refused(plan.reason)
    plan as MergePlan.Merge

    val now = clock()
    val survivor = plan.survivor.value
    val loser = plan.loser.value
    val survivorRow = if (first.media.mediaId == survivor) first.media else second.media
    val current = plan.merged.currentVersionId

    // 1. Le versioni del perdente con gli stessi byte di una del sopravvissuto: chi le usa passa all'altra.
    plan.versionMapping.forEach { (from, to) ->
      mergeDao.repointLocations(from.value, to.value)
      mergeDao.repointWorkVersion(from.value, to.value)
      mergeDao.repointEmbeddingVersion(from.value, to.value)
      mergeDao.repointParents(from.value, to.value)
    }
    // 2. Le altre versioni passano al sopravvissuto, con posizioni e metadati.
    plan.movedVersions.forEach { mergeDao.moveVersion(it.id.value, survivor, it.parentId?.value) }

    // 3. Dati personali: preferito o nascosto se lo era uno dei due.
    val mine = personalDao.get(survivor)?.toDomain()
    val theirs = personalDao.get(loser)?.toDomain()
    if (mine != null || theirs != null) {
      personalDao.upsert((mine ?: PersonalFlags()).mergedWith(theirs ?: PersonalFlags()).toEntity(plan.survivor, now))
    }

    // 4. Album, copertine, gruppi.
    mergeDao.copyAlbumItems(loser, survivor)
    mergeDao.repointAlbumCovers(loser, survivor)
    mergeDao.repointGroupCovers(loser, survivor)

    // 5. Vettori: per ogni spazio e indice, quello calcolato dalla versione corrente.
    val survivorVectors = embeddingDao.forMedia(survivor).associateBy { slotOf(it) }
    embeddingDao.forMedia(loser).forEach { loserRow ->
      val survivorVector = survivorVectors[slotOf(loserRow)]?.toDomain()
      val loserVector = loserRow.toDomain()
      val keepLoser = survivorVector == null || MergeRules.chooseVector(survivorVector, loserVector, current) === loserVector
      if (keepLoser) embeddingDao.upsert(loserRow.withMedia(survivor))
    }

    // 6. Stati di lavoro.
    val states = WorkRules.merge(
      workDao.forMedia(survivor).map { it.toDomain() },
      workDao.forMedia(loser).map { it.toDomain() },
      current,
    )
    workDao.upsert(states.map { it.toEntity(plan.survivor) })

    // 7. L'identita' del perdente resta come alias (anche gli alias che puntavano a lui).
    mergeDao.repointAliases(loser, survivor)
    mediaDao.insertAlias(MediaAliasEntity(aliasId = loser, mediaId = survivor, mergedAt = now))

    // 8. Il media unito. Il perdente se ne va: in cascata partono solo righe gia' copiate.
    mediaDao.update(
      survivorRow.copy(
        currentVersionId = current.value,
        ownerAccount = plan.merged.owner?.value,
        source = plan.merged.source.code,
        groupId = plan.merged.groupId?.value,
        createdAt = plan.merged.createdAtMillis,
        updatedAt = now,
      ),
    )
    mediaDao.delete(loser)
    refreshDerived(plan.survivor)
    return MergeOutcome.Merged(plan.survivor, plan.loser)
  }

  private fun slotOf(row: EmbeddingEntity): String = "${row.spaceId}/${row.dimension}/${row.precision}/${row.vectorIndex}"

  /** Impara l'hash completo di una versione da un'osservazione con gli stessi byte. */
  private suspend fun learnFullHash(versionId: VersionId, observed: Fingerprint) {
    val hash = observed.fullHash ?: return
    val entity = versionDao.get(versionId.value) ?: return
    if (entity.fullHash != null) return
    if (entity.toDomain().fingerprint.match(observed) == FingerprintMatch.DIFFERENT) return
    versionDao.update(entity.copy(fullHash = hash.hex))
  }

  /** Ricalcola i campi derivati di un media con le regole di dominio. */
  private suspend fun refreshDerived(mediaId: MediaId, areas: NasAreaLookup? = null) {
    val graph = mediaDao.graph(mediaId.value) ?: return
    val lookup = areas ?: areas()
    val stored = graph.toDomain()
    val owner = SafetyRules.claimedOwner(stored, lookup)
    if (owner != stored.owner) mediaDao.setOwner(mediaId.value, owner?.value, clock())
    val media = stored.copy(owner = owner)
    val metadata = graph.versions.firstOrNull { it.version.versionId == graph.media.currentVersionId }?.metadata
    mediaDao.updateDerived(
      mediaId = mediaId.value,
      presence = SafetyRules.presence(media).code,
      safety = SafetyRules.evaluate(media, localAccount, lookup).status.code,
      sortTime = metadata?.takenAtUtc ?: graph.media.sortTime,
      now = clock(),
    )
  }

  private suspend fun areas(): NasAreaLookup = NasAreaLookup.of(shareDao.all().map { it.toDomain() })

  private suspend fun requireMedia(id: MediaId): Media =
    checkNotNull(mediaDao.graph(id.value)) { "Media sconosciuto." }.toDomain()

  private suspend fun requireLocation(id: LocationId): MediaLocation =
    checkNotNull(locationDao.get(id.value)) { "Posizione sconosciuta." }.toDomain()

  private suspend fun mediaOf(id: LocationId): MediaId =
    MediaId(checkNotNull(locationDao.mediaIdOf(id.value)) { "Posizione senza media." })

  companion object {
    /**
     * Ogni colonna che punta a `media` (tabella.colonna). [merge] le gestisce tutte prima di
     * cancellare il perdente; `MergeReferencesTest` confronta questo elenco con lo schema vero e
     * fallisce se una tabella nuova lo referenzia senza essere stata aggiunta qui (e all'unione).
     */
    val MEDIA_REFERENCES: Set<String> = setOf(
      "media_version.media_id",
      "media_personal.media_id",
      "work_state.media_id",
      "embedding.media_id",
      "media_alias.media_id",
      "media_group.cover_media_id",
      "album.cover_media_id",
      "album_item.media_id",
    )
  }
}
