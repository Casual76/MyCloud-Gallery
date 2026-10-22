package dev.pampa.mycloudgallery.core.trust.registry

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
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
  /**
   * Le impronte dei rami che l'admin ha gia' trattato ma che la modifica non puo' "contenere": i rami con un
   * annuncio di rotazione (verifica della revisione, R2). [FamilyAuthority.commit] li scavalca invece di
   * restituire di nuovo `Fork`.
   */
  internal val acknowledgedForks: Set<String> = emptySet(),
)

sealed interface CommitResult {
  /**
   * Pubblicata. [state] e' il nuovo registro fidato; [skipped] i nomi occupati da file non validi scavalcati
   * (Diagnostica).
   *
   * [unverifiedBeyond]: oltre il nome usato non si e' potuto controllare tutto (elenco della cartella troncato,
   * o troppa spazzatura tra gli indizi). Una versione piu' nuova potrebbe esistere nascosta dietro un buco, e
   * i dispositivi che l'hanno gia' vista non vedrebbero questa (verifica della revisione, N2): non e' un
   * errore, ma l'app lo deve **dire** all'admin ("cambia la password dell'account sospetto e pulisci la
   * cartella del registro"), mai pubblicare in silenzio.
   */
  class Published(
    val state: RegistryState,
    val skipped: List<SharePath>,
    val unverifiedBeyond: Boolean = false,
  ) : CommitResult

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

  /**
   * Il primo registro della famiglia: l'admin che la fonda e' il primo dispositivo approvato. [version] e' 1
   * per una famiglia nuova; dopo un reset e' il nome scelto da [refound].
   */
  fun found(familyId: String, config: FamilyConfig, founder: RegistrationRequest, version: Long = 1): SignedRegistry {
    require(founder.familyId == familyId) { "La richiesta del fondatore e' di un'altra famiglia." }
    val now = clock.nowMillis()
    return sign(FamilyRegistry(familyId, version, now, config, listOf(entryOf(founder, admin = true, now))))
  }

  /**
   * Il reset della famiglia (`docs/formato-nas.md` §4.10): la fondazione della famiglia **nuova** (id e chiave
   * nuovi, questa [FamilyAuthority]) si pubblica nella stessa cartella del registro, al primo nome libero dopo
   * [knownVersion] (l'ultima versione della famiglia vecchia che questo dispositivo conosce, 0 se nessuna)
   * seguito da [TrustLimits.MAX_JUNK_RUN] nomi liberi: cosi' chi parte dalla versione della fondazione (la
   * versione minima del QR nuovo) non inciampa subito nelle versioni della famiglia vecchia, che per lui sono
   * spazzatura firmata da un'altra chiave. Le versioni vecchie restano dove sono: ignorate, non cancellate.
   *
   * Si legge per nome, al massimo [TrustLimits.MAX_SERIES_READS] volte; null se non si trova posto (cartella
   * inondata: si pulisce dal PC e si riprova). Il numero della fondazione e' `registry.version` del risultato.
   */
  fun refound(
    commonShare: NasFileSystem,
    familyId: String,
    config: FamilyConfig,
    founder: RegistrationRequest,
    knownVersion: Long = 0,
  ): SignedRegistry? {
    val maxBytes = Envelope.maxFileBytes(DocType.FAMILY_REGISTRY)
    var candidate = maxOf(0L, knownVersion) + 1
    var reads = 0
    search@ while (candidate + TrustLimits.MAX_JUNK_RUN <= TrustLimits.MAX_VERSION && reads < TrustLimits.MAX_SERIES_READS) {
      for (version in candidate..candidate + TrustLimits.MAX_JUNK_RUN) {
        reads++
        if (commonShare.read(NasLayout.registryFile(version), maxBytes) !is NasRead.NotFound) {
          candidate = version + 1
          continue@search
        }
      }
      val signed = found(familyId, config, founder, candidate)
      when (commonShare.publishExclusive(NasLayout.registryFile(candidate), signed.bytes)) {
        PublishResult.CREATED -> return signed
        PublishResult.ALREADY_EXISTS -> candidate++
      }
    }
    return null
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

  /**
   * Piu' modifiche nella stessa versione (recupero dell'admin: approvarsi e segnare il vecchio admin come
   * perso): [changeStatus] applicato a una modifica gia' preparata, con le stesse regole.
   */
  fun changeStatus(draft: RegistryDraft, deviceId: String, status: DeviceStatus): RegistryDraft {
    authorized(draft.base)
    val content = draft.content
    val entry = requireNotNull(content.device(deviceId)) { "Dispositivo assente dal registro." }
    require(entry.status.canBecome(status)) { "Cambio di stato non ammesso." }
    require(status != DeviceStatus.RETIRED) { "La dismissione si prepara da sola (teste congelate)." }
    val updated = entry.copy(status = status, since = clock.nowMillis(), frozenHeads = emptyList())
    return RegistryDraft(draft.base, content.copy(devices = content.devices.map { if (it.id == deviceId) updated else it }), draft.acknowledgedForks)
  }

  /**
   * Cambia il nome da mostrare di un dispositivo approvato (F27 delle prove sul campo, E-B7). Non e' un formato nuovo: `name` e' gia' un campo
   * della voce del registro e la versione che esce e' firmata come ogni altra dalla chiave di famiglia. Restano identici chiave, id, account,
   * ruolo e stato (e la loro regola anti-regressione, che non guarda il nome): il dispositivo e' lo stesso, ha solo un'etichetta diversa.
   * Il nome deve essere un testo da mostrare valido ([TextRules.displayText]); un nome uguale a quello di prima non firma niente.
   */
  fun renameDevice(state: RegistryState, deviceId: String, name: String): RegistryDraft {
    val current = authorized(state)
    val entry = requireNotNull(current.device(deviceId)) { "Dispositivo assente dal registro." }
    require(entry.status == DeviceStatus.APPROVED) { "Si rinomina solo un dispositivo approvato." }
    TextRules.displayText(name, TrustLimits.MAX_DISPLAY_TEXT_CHARS)
    val updated = entry.copy(name = name)
    return RegistryDraft(state, current.copy(devices = current.devices.map { if (it.id == deviceId) updated else it }))
  }

  /**
   * Segna come admin un dispositivo gia' approvato (secondo admin su un dispositivo che era un familiare,
   * §4.9). Il segno e' informativo, il potere e' la chiave: la regola anti-regressione lo lascia cambiare.
   */
  fun markAdmin(draft: RegistryDraft, deviceId: String): RegistryDraft {
    authorized(draft.base)
    val content = draft.content
    val entry = requireNotNull(content.device(deviceId)) { "Dispositivo assente dal registro." }
    require(entry.status == DeviceStatus.APPROVED) { "Solo un dispositivo approvato diventa admin." }
    return RegistryDraft(draft.base, content.copy(devices = content.devices.map { if (it.id == deviceId) entry.copy(admin = true) else it }), draft.acknowledgedForks)
  }

  /** Una modifica vuota sulla base [state], a cui aggiungere [changeStatus]/[markAdmin]. */
  fun edit(state: RegistryState): RegistryDraft = RegistryDraft(state, authorized(state))

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

  /**
   * Un ramo che contiene un **annuncio di rotazione** (verifica della revisione, R2) non si puo' "contenere":
   * i dispositivi che l'hanno visto si fidano ormai di un'altra chiave e vanno comunque riconfigurati (reset
   * della famiglia). Invece di bloccare ogni pubblicazione, si prendono da quel ramo solo gli stati piu'
   * severi dei dispositivi gia' noti (quelli che esistono solo li' entrano revocati, anche se l'admin ha
   * riconosciuto il ramo) e lo si scavalca: almeno il ramo dell'admin riceve la sua modifica (una revoca).
   */
  private fun join(draft: RegistryDraft, forks: List<RegistryFork>, revokeUnknown: Boolean): RegistryDraft {
    authorized(draft.base)
    var content = draft.content
    val acknowledged = draft.acknowledgedForks.toMutableSet()
    for (fork in forks) {
      if (fork.registry.successorKey != null) {
        content = content.joinWith(fork.registry.copy(successorKey = null), revokeOnlyInOther = true)
        acknowledged += fork.contentHash
      } else {
        content = content.joinWith(fork.registry, revokeUnknown)
      }
    }
    return RegistryDraft(draft.base, content, acknowledged)
  }

  /**
   * Numera, firma e pubblica [draft] (`docs/formato-nas.md` §4.4, "Pubblicazione"):
   *
   * 1. il nome della versione fidata: se c'e' un altro contenuto firmato dalla famiglia, e' un ramo;
   * 2. i nomi dopo la base, letti per nome fino al primo libero: un registro valido piu' nuovo ferma
   *    (`NewerExists`), un registro di un'app piu' nuova ferma (`NeedsAppUpdate`), la spazzatura si scavalca
   *    (al massimo [TrustLimits.MAX_JUNK_RUN] di fila), un ramo che la modifica gia' contiene si scavalca;
   * 3. i [TrustLimits.MAX_JUNK_RUN] nomi subito dopo quello libero, **per nome** (verifica della revisione,
   *    N2): un buco aperto da chi cancella non nasconde una versione piu' nuova nemmeno se l'elenco e'
   *    inondato. Poi gli indizi dell'elenco oltre il nome libero (revisione 1.4, N2), con lo stesso tetto alla
   *    spazzatura di fila di [RegistrySync] (R4). Una versione valida dopo un buco vuol dire base vecchia
   *    (`NewerExists`), un ramo li' va considerato come al punto 2. Se l'elenco era troncato o gli indizi si
   *    sono fermati per la spazzatura, la pubblicazione lo dice ([CommitResult.Published.unverifiedBeyond]);
   * 4. se restano rami non contenuti, `Fork` con tutti; altrimenti si firma al nome libero con l'ora di
   *    emissione non inferiore a quella di ogni ramo scavalcato, cosi' la accettano anche i loro dispositivi.
   *    I rami con una rotazione gia' trattati da [mergeForks]/[rejectForks] si scavalcano (R2).
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
      if (draft.content.dominates(fork.registry) || fork.contentHash in draft.acknowledgedForks) {
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

    // Un nome oltre quello libero: una versione valida piu' nuova (base vecchia) o di un formato piu' nuovo
    // ferma la pubblicazione ([stop]); altrimenti il risultato dice se era un ramo (true) o spazzatura (false).
    var stop: CommitResult? = null
    fun beyond(bytes: ByteArray): Boolean = when (val verdict = RegistryVerifier.accept(bytes, base, anchor)) {
      is Verdict.Accepted -> {
        stop = CommitResult.NewerExists
        true
      }
      is Verdict.Rejected -> if (verdict.reason == RejectReason.NEWER_FORMAT) {
        stop = CommitResult.NeedsAppUpdate
        true
      } else {
        classify(bytes, verdict.reason)
      }
    }

    // 3a. I nomi subito dopo quello libero, per nome (N2): niente elenco, quindi niente inondazione.
    val tried = HashSet<Long>()
    val lookaheadEnd = minOf(target + TrustLimits.MAX_JUNK_RUN, TrustLimits.MAX_VERSION)
    for (ahead in target + 1..lookaheadEnd) {
      tried.add(ahead)
      val read = commonShare.read(NasLayout.registryFile(ahead), maxBytes) as? NasRead.Data ?: continue
      beyond(read.bytes)
      stop?.let { return it }
    }

    // 3b. Indizi dell'elenco oltre: con lo stesso tetto alla spazzatura di fila di RegistrySync (R4).
    val listing = commonShare.list(NasLayout.REGISTRY_DIR, TrustLimits.MAX_LIST_REGISTRY)
    var unverifiedBeyond = listing.truncated
    val hints = listing.names.mapNotNull(NasLayout::parseVersionName)
      .filter { it > target && it !in tried }.sorted().take(TrustLimits.MAX_SERIES_READS)
    var hintJunkRun = 0
    for (hint in hints) {
      if (hintJunkRun > TrustLimits.MAX_JUNK_RUN) {
        unverifiedBeyond = true
        break
      }
      when (val read = commonShare.read(NasLayout.registryFile(hint), maxBytes)) {
        NasRead.NotFound -> Unit
        NasRead.TooLarge -> hintJunkRun++
        is NasRead.Data -> {
          val isFork = beyond(read.bytes)
          stop?.let { return it }
          hintJunkRun = if (isFork) 0 else hintJunkRun + 1
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
        CommitResult.Published(state, skipped, unverifiedBeyond)
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
