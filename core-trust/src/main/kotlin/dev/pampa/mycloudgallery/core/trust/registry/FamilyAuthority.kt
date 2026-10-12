package dev.pampa.mycloudgallery.core.trust.registry

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/** Un registro firmato, pronto da pubblicare (solo la fondazione: le altre versioni passano da [FamilyAuthority.commit]). */
class SignedRegistry(val registry: FamilyRegistry, bytes: ByteArray) {
  private val data = bytes.copyOf()
  val bytes: ByteArray get() = data.copyOf()
}

/**
 * Una modifica del registro preparata dall'admin a partire da [base], non ancora numerata ne' firmata: il
 * numero lo sceglie [FamilyAuthority.commit] (il primo nome libero).
 */
class RegistryDraft internal constructor(
  val base: RegistryState,
  internal val content: FamilyRegistry,
)

sealed interface CommitResult {
  /** Pubblicata. [state] e' il nuovo registro fidato; [skipped] i nomi occupati da file non validi scavalcati (Diagnostica). */
  class Published(val state: RegistryState, val skipped: List<SharePath>) : CommitResult

  /** Sul NAS c'e' gia' una versione piu' nuova valida (un altro admin): risincronizzare e rifare la modifica. */
  data object NewerExists : CommitResult

  /** Sul NAS c'e' un registro di un'app piu' nuova: questa app non firma finche' non si aggiorna (E-I3). */
  data object NeedsAppUpdate : CommitResult

  /**
   * Rami diversi firmati con la chiave di famiglia che la modifica non contiene ancora (un admin che ha
   * lavorato su una base vecchia, oppure la chiave e' stata usata da altri): tutti quelli trovati, non solo
   * il primo. L'admin li guarda e riprova con [FamilyAuthority.mergeForks] (li riconosce) o
   * [FamilyAuthority.rejectForks] (non li riconosce: i loro dispositivi entrano revocati, poi reset). In
   * entrambi i casi la sua modifica, per esempio una revoca, esce insieme.
   */
  class Fork(val forks: List<RegistryFork>) : CommitResult

  /**
   * Troppi nomi consecutivi occupati da file non validi ([TrustLimits.MAX_JUNK_RUN]): la revoca non si
   * puo' pubblicare finche' quei file (elenco esatto in [junk]) non vengono cancellati dal PC, dopo aver
   * cambiato la password NAS dell'account che li scrive.
   */
  class Blocked(val junk: List<SharePath>) : CommitResult
}

/**
 * Le operazioni dell'admin sul registro (`docs/formato-nas.md` §4.4–4.6): ognuna prepara una modifica
 * ([RegistryDraft]) che [commit] numera, firma con la chiave di famiglia e pubblica con `FILE_CREATE`.
 * Nessuna modifica parte da dati letti: approvare, revocare, dismettere, unire un ramo sono azioni locali
 * dell'admin.
 */
class FamilyAuthority(private val familyKey: FamilyKey, private val clock: NasClock) {

  /** Il primo registro della famiglia: l'admin che la fonda e' il primo dispositivo approvato. */
  fun found(familyId: String, config: FamilyConfig, founder: RegistrationRequest): SignedRegistry {
    require(founder.familyId == familyId) { "La richiesta del fondatore e' di un'altra famiglia." }
    val now = clock.nowMillis()
    return sign(FamilyRegistry(familyId, 1, now, config, listOf(entryOf(founder, admin = true, now))))
  }

  /**
   * Approva un dispositivo che ha chiesto di entrare. [proof]: il codice digitato dall'admin leggendolo sul
   * dispositivo nuovo, o la sua chiave scansionata. [firstSeenAt]: quando l'app dell'admin ha visto la
   * richiesta la prima volta (ora del NAS, annotata in locale); oltre 15 minuti la richiesta va rifatta.
   */
  fun approve(
    state: RegistryState,
    request: RegistrationRequest,
    proof: ApprovalProof,
    firstSeenAt: Long,
    admin: Boolean = false,
  ): RegistryDraft {
    val current = authorized(state)
    require(request.familyId == current.familyId) { "Richiesta di un'altra famiglia." }
    require(current.device(request.deviceId) == null) { "Dispositivo gia' presente nel registro." }
    require(proof.proves(request)) { "Il codice non corrisponde al dispositivo: non approvare." }
    require(ApprovalWindow.isOpen(firstSeenAt, clock.nowMillis())) { "Richiesta troppo vecchia: il dispositivo deve rifarla." }
    val devices = (current.devices + entryOf(request, admin, clock.nowMillis())).sortedBy { it.id }
    return RegistryDraft(state, current.copy(devices = devices))
  }

  /**
   * Cambia lo stato di un dispositivo: dismesso ([DeviceStatus.RETIRED], con le teste che restano valide),
   * revocato, perso. Non si torna mai indietro ([DeviceStatus.canBecome]).
   */
  fun changeStatus(
    state: RegistryState,
    deviceId: String,
    status: DeviceStatus,
    frozenHeads: List<String> = emptyList(),
  ): RegistryDraft {
    val current = authorized(state)
    val entry = requireNotNull(current.device(deviceId)) { "Dispositivo assente dal registro." }
    require(entry.status.canBecome(status)) { "Cambio di stato non ammesso." }
    require(status == DeviceStatus.RETIRED || frozenHeads.isEmpty()) { "Solo un dispositivo dismesso conserva dati." }
    val updated = entry.copy(status = status, since = clock.nowMillis(), frozenHeads = frozenHeads.distinct().sorted())
    return RegistryDraft(state, current.copy(devices = current.devices.map { if (it.id == deviceId) updated else it }))
  }

  fun updateConfig(state: RegistryState, config: FamilyConfig): RegistryDraft =
    RegistryDraft(state, authorized(state).copy(config = config))

  /**
   * Rotazione: la versione pubblicata da questo draft, firmata dalla chiave attuale, annuncia [next]; da li'
   * in poi firma solo [next] (con un nuovo [FamilyAuthority]). Non e' una difesa da una chiave gia' rubata:
   * per quella c'e' il reset. Dopo la rotazione si genera un kit nuovo, con un codice nuovo.
   */
  fun rotateTo(state: RegistryState, next: FamilyKey): RegistryDraft {
    val current = authorized(state)
    require(next.publicKey != familyKey.publicKey) { "La chiave nuova deve essere diversa." }
    return RegistryDraft(state, current.copy(successorKey = next.publicKey))
  }

  /**
   * Ripubblica il registro fidato invariato con un numero nuovo: quando la versione fidata e' stata
   * sostituita da spazzatura sul NAS (revisione 1.4, verifica N3) o per completare una rotazione.
   */
  fun reissue(state: RegistryState): RegistryDraft = RegistryDraft(state, authorized(state))

  /**
   * Unisce alla modifica [draft] i rami che l'admin ha riconosciuto (tipicamente il secondo dispositivo admin
   * rimasto indietro): ogni dispositivo con lo stato piu' severo. La versione che ne esce contiene tutti i
   * rami, quindi la accettano i dispositivi di ogni ramo e nessuna revoca va persa.
   */
  fun mergeForks(draft: RegistryDraft, forks: List<RegistryFork>): RegistryDraft = join(draft, forks, revokeUnknown = false)

  /**
   * L'uscita da rami che l'admin NON riconosce (la chiave di famiglia l'ha usata qualcun altro): come
   * [mergeForks], ma i dispositivi che esistono solo nei rami entrano revocati. Tutti i dispositivi
   * accettano la versione e quei dispositivi non valgono per nessuno; poi si fa il reset della famiglia.
   */
  fun rejectForks(draft: RegistryDraft, forks: List<RegistryFork>): RegistryDraft = join(draft, forks, revokeUnknown = true)

  private fun join(draft: RegistryDraft, forks: List<RegistryFork>, revokeUnknown: Boolean): RegistryDraft {
    authorized(draft.base)
    var content = draft.content
    for (fork in forks) {
      require(fork.registry.successorKey == null) { "Un ramo contiene una rotazione: serve il reset della famiglia." }
      content = content.joinWith(fork.registry, revokeUnknown)
    }
    return RegistryDraft(draft.base, content)
  }

  /**
   * Numera, firma e pubblica [draft] (`docs/formato-nas.md` §4.4, "Pubblicazione"):
   *
   * 1. il nome della versione fidata: se c'e' un altro contenuto firmato dalla famiglia, e' un ramo;
   * 2. i nomi dopo la base, letti per nome fino al primo libero: un registro valido piu' nuovo ferma
   *    (`NewerExists`), un registro di un'app piu' nuova ferma (`NeedsAppUpdate`), la spazzatura si scavalca
   *    (al massimo [TrustLimits.MAX_JUNK_RUN] di fila), un ramo che la modifica gia' contiene si scavalca;
   * 3. gli indizi dell'elenco OLTRE il nome libero (revisione 1.4, verifica N2): una versione valida dopo un
   *    buco vuol dire base vecchia (`NewerExists`), un ramo li' va considerato come al punto 2;
   * 4. se restano rami non contenuti, `Fork` con tutti; altrimenti si firma al nome libero con l'ora di
   *    emissione non inferiore a quella di ogni ramo scavalcato, cosi' la accettano anche i loro dispositivi.
   */
  fun commit(commonShare: NasFileSystem, draft: RegistryDraft): CommitResult {
    authorized(draft.base)
    repeat(3) {
      // Un altro scrittore puo' prendere il nome libero un istante prima: si riprova.
      val attempt = attemptCommit(commonShare, draft)
      if (attempt != null) return attempt
    }
    return CommitResult.NewerExists
  }

  /** Un tentativo di [commit]; null se il nome libero e' stato preso da altri nel frattempo. */
  private fun attemptCommit(commonShare: NasFileSystem, draft: RegistryDraft): CommitResult? {
    val base = draft.base
    val anchor = TrustAnchor.create(base.registry.familyId, base.signerKey.fingerprint, base.registry.version)!!
    val maxBytes = Envelope.maxFileBytes(DocType.FAMILY_REGISTRY)
    val skipped = ArrayList<SharePath>()
    val forks = ArrayList<RegistryFork>()
    var issuedAtFloor = base.registry.issuedAt

    // Un file che non e' un registro valido piu' nuovo: ramo gia' contenuto, ramo da mostrare, o spazzatura (false).
    fun classify(bytes: ByteArray, reason: RejectReason): Boolean {
      if (reason != RejectReason.REGRESSION && reason != RejectReason.EQUIVOCATION) return false
      val fork = (RegistryVerifier.readFork(bytes, base) as? Verdict.Accepted)?.value ?: return false
      if (draft.content.dominates(fork.registry)) {
        issuedAtFloor = maxOf(issuedAtFloor, fork.registry.issuedAt)
      } else if (forks.none { it.contentHash == fork.contentHash }) {
        forks.add(fork)
      }
      return true
    }

    // 1. La versione fidata e' ancora quella al suo nome?
    val atBase = commonShare.read(NasLayout.registryFile(base.registry.version), maxBytes)
    if (atBase is NasRead.Data) {
      val verdict = RegistryVerifier.accept(atBase.bytes, base, anchor)
      if (verdict is Verdict.Rejected) classify(atBase.bytes, verdict.reason)
    }

    // 2. Dopo la base, per nome, fino al primo nome libero.
    var version = base.registry.version + 1
    var junkRun = 0
    var free: Long? = null
    while (version <= TrustLimits.MAX_VERSION) {
      val path = NasLayout.registryFile(version)
      when (val read = commonShare.read(path, maxBytes)) {
        NasRead.NotFound -> {
          free = version
          break
        }
        NasRead.TooLarge -> {
          skipped.add(path)
          junkRun++
        }
        is NasRead.Data -> when (val verdict = RegistryVerifier.accept(read.bytes, base, anchor)) {
          is Verdict.Accepted -> return CommitResult.NewerExists
          is Verdict.Rejected -> {
            if (verdict.reason == RejectReason.NEWER_FORMAT) return CommitResult.NeedsAppUpdate
            if (!classify(read.bytes, verdict.reason)) {
              skipped.add(path)
              junkRun++
            }
          }
        }
      }
      if (junkRun > TrustLimits.MAX_JUNK_RUN) return CommitResult.Blocked(skipped)
      version++
    }
    val target = free ?: return CommitResult.Blocked(skipped)

    // 3. Indizi dell'elenco oltre il nome libero.
    val hints = commonShare.list(NasLayout.REGISTRY_DIR, TrustLimits.MAX_LIST_REGISTRY).names
      .mapNotNull(NasLayout::parseVersionName).filter { it > target }.sorted().take(TrustLimits.MAX_SERIES_READS)
    for (hint in hints) {
      val read = commonShare.read(NasLayout.registryFile(hint), maxBytes) as? NasRead.Data ?: continue
      when (val verdict = RegistryVerifier.accept(read.bytes, base, anchor)) {
        is Verdict.Accepted -> return CommitResult.NewerExists
        is Verdict.Rejected -> {
          if (verdict.reason == RejectReason.NEWER_FORMAT) return CommitResult.NeedsAppUpdate
          classify(read.bytes, verdict.reason)
        }
      }
    }

    // 4. Rami non contenuti: decide l'admin. Altrimenti si firma.
    if (forks.isNotEmpty()) return CommitResult.Fork(forks)
    val issuedAt = maxOf(clock.nowMillis(), issuedAtFloor, draft.content.issuedAt)
    val signed = sign(draft.content.copy(version = target, issuedAt = issuedAt))
    return when (commonShare.publishExclusive(NasLayout.registryFile(target), signed.bytes)) {
      PublishResult.CREATED -> {
        val state = (RegistryVerifier.accept(signed.bytes, base, anchor) as Verdict.Accepted).value
        CommitResult.Published(state, skipped)
      }
      PublishResult.ALREADY_EXISTS -> null
    }
  }

  private fun authorized(state: RegistryState): FamilyRegistry {
    check(state.trustedFamilyKey == familyKey.publicKey) { "Questa chiave non e' (piu') quella di famiglia." }
    // Dopo una rotazione l'annuncio non si ripete: le versioni nuove non lo portano.
    return state.registry.copy(successorKey = null)
  }

  private fun entryOf(request: RegistrationRequest, admin: Boolean, now: Long) = DeviceEntry(
    id = request.deviceId,
    key = request.key,
    name = request.name,
    model = request.model,
    nasUser = request.nasUser,
    admin = admin,
    status = DeviceStatus.APPROVED,
    since = now,
  )

  private fun sign(registry: FamilyRegistry): SignedRegistry =
    SignedRegistry(registry, Envelope.seal(DocType.FAMILY_REGISTRY, registry.toJson(), familyKey))

  companion object {
    /**
     * Rotazione completa (revisione 1.4, verifica N4): l'annuncio firmato dalla chiave attuale e subito dopo
     * una versione invariata firmata da [next], cosi' il QR di stato ([RegistryState.toAnchor]) mostra gia'
     * la chiave nuova. Se il secondo passo non riesce (per esempio `NewerExists`), lo si ripete con
     * `FamilyAuthority(next, clock).reissue(...)` sullo stato risincronizzato.
     */
    fun rotate(commonShare: NasFileSystem, state: RegistryState, current: FamilyKey, next: FamilyKey, clock: NasClock): CommitResult {
      val oldAuthority = FamilyAuthority(current, clock)
      val announce = oldAuthority.commit(commonShare, oldAuthority.rotateTo(state, next))
      if (announce !is CommitResult.Published) return announce
      val newAuthority = FamilyAuthority(next, clock)
      return newAuthority.commit(commonShare, newAuthority.reissue(announce.state))
    }

    /** Pubblica la fondazione (versione 1). [PublishResult.ALREADY_EXISTS]: qui c'e' gia' una famiglia. */
    fun publishFounding(commonShare: NasFileSystem, signed: SignedRegistry): PublishResult {
      require(signed.registry.version == 1L) { "Solo la fondazione si pubblica cosi'." }
      return commonShare.publishExclusive(NasLayout.registryFile(1), signed.bytes)
    }
  }
}
