package dev.pampa.mycloudgallery.core.family.registry

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.onboarding.FamilyConfigFactory
import dev.pampa.mycloudgallery.core.family.trust.TrustEngine
import dev.pampa.mycloudgallery.core.family.trust.UNEXPECTED
import dev.pampa.mycloudgallery.core.family.trust.onShare
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.TrustViolation
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKeyUse
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKeyVault
import dev.pampa.mycloudgallery.core.trust.crypto.VaultRefusal
import dev.pampa.mycloudgallery.core.trust.crypto.VaultResult
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.CommitResult
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.FamilyAuthority
import dev.pampa.mycloudgallery.core.trust.registry.FamilyConfig
import dev.pampa.mycloudgallery.core.trust.registry.LookAlike
import dev.pampa.mycloudgallery.core.trust.registry.RegistryDraft
import dev.pampa.mycloudgallery.core.trust.registry.RegistryFork
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.registry.UlaPrefix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Le teste da congelare quando l'admin dismette un dispositivo (A7, D8: "dismesso: valgono i contributi delle sue teste congelate"). Le dicono i
 * moduli che le hanno accettate su questo dispositivo (R6 della revisione di sicurezza della Fase 7: gli album condivisi, legati da :app); le
 * impronte si ordinano dalla piu' importante e il registro ne tiene al piu' [TrustLimits.MAX_FROZEN_HEADS]. Senza legame nessuna.
 */
fun interface RetireHeads {
  suspend fun headsOf(deviceId: String): List<String>

  companion object {
    val NONE: RetireHeads = RetireHeads { emptyList() }
  }
}

/** Cio' che serve a un'azione dell'admin: il registro fidato, chi sono, la chiave di famiglia aperta. */
class AdminContext(val state: RegistryState, val me: String, val authority: FamilyAuthority, val commonShare: String)

/**
 * L'admin **prima** di aprire la chiave di famiglia (Fase 10, M3): registro fidato, chi sono, l'area comune. Ogni azione controlla qui
 * tutto quello che si puo' controllare senza la chiave (esiste? cambia qualcosa? il codice coincide?) e solo dopo chiede l'impronta o
 * il PIN ([RegistryWriter.unlock]): un'azione che non firmerebbe niente non mostra il foglio.
 */
class AdminBase(val state: RegistryState, val me: String, val commonShare: String)

/** La chiave di famiglia aperta per un'azione ([Open]) o perche' no ([Denied], con l'esito da restituire: niente firmato). */
sealed interface AdminUnlock {
  class Open(val admin: AdminContext) : AdminUnlock

  class Denied(val result: AdminActionResult) : AdminUnlock
}

/**
 * Rami in conflitto trovati da una pubblicazione, in attesa della scelta dell'admin (schermata dedicata,
 * P7): [resolve] li unisce ([recognize] = vero) o li rifiuta, sulla **stessa** modifica.
 */
class PendingForks(internal val draft: RegistryDraft, val forks: List<RegistryFork>) {
  /** I rami cambiano la configurazione (es. `nasHost`): la schermata lo dice (revisione 1.4, domanda 10). */
  val configDiffers: Boolean get() = forks.any { it.configDiffers(draft.base.registry) }

  /** I rami annunciano una rotazione: i loro dispositivi vanno riconfigurati (reset), R2. */
  val containsRotation: Boolean get() = forks.any { it.registry.successorKey != null }
}

/**
 * L'unico punto da cui `core-family` firma e pubblica il registro (`FamilyAuthority.commit`). Ogni chiamata
 * parte da un gesto locale dell'admin (E-S11); la chiave di famiglia si apre dalla cassaforte solo qui, per
 * il tempo dell'azione (a telefono sbloccato con blocco schermo sicuro: revisione M3), e **ogni azione chiede
 * l'impronta o il PIN** sul foglio di sistema (Fase 10, M3, decisione 2(a) del 31/10): un foglio per gesto,
 * anche quando il gesto firma piu' versioni (le segnalazioni "perso" convertite insieme). Annullato:
 * [AdminActionResult.KeyRefused], niente firmato. Nessun lavoro in background passa di qui (la ripubblicazione
 * di N3 non e' piu' automatica: [reissue] e' un tasto).
 *
 * Esiti di `commit` → [AdminActionResult]: `Published` → `Done` (e lo stato nuovo diventa quello fidato);
 * `NewerExists` → si rilegge il registro e si restituisce `NewerExists` (l'interfaccia riprova sulla base
 * nuova); `Fork` → [pendingForks] per la schermata dedicata; `Blocked` → [blockedPaths] (i file esatti da
 * cancellare dal PC, **dopo** aver cambiato la password NAS sospetta, L18); `NeedsAppUpdate`.
 */
class RegistryWriter(
  private val engine: TrustEngine,
  private val sessions: NasSessionManager,
  private val familyKeys: FamilyKeyVault,
  private val nasClock: NasClock,
) {
  private val _pendingForks = MutableStateFlow<PendingForks?>(null)
  private val _blockedPaths = MutableStateFlow<List<SharePath>>(emptyList())
  private val _unverifiedBeyond = MutableStateFlow(false)

  val pendingForks: StateFlow<PendingForks?> = _pendingForks.asStateFlow()
  val blockedPaths: StateFlow<List<SharePath>> = _blockedPaths.asStateFlow()

  /** R6: chi dice quali teste congelare alla dismissione (lo lega :app quando gli album ci sono). */
  @Volatile var retireHeads: RetireHeads = RetireHeads.NONE

  /** L'ultima pubblicazione non ha potuto controllare tutto oltre il nome usato (N2): da dire all'admin. */
  val unverifiedBeyond: StateFlow<Boolean> = _unverifiedBeyond.asStateFlow()

  /**
   * Il contesto dell'admin ([AdminUnlock.Open]) o perche' no: registro fidato caricato, dispositivo approvato **e segnato admin
   * nel registro firmato** ([adminBase]), chiave di famiglia nella cassaforte, uguale a quella che deve firmare la prossima
   * versione, e aperta con l'impronta o il PIN ([unlock], Fase 10, M3).
   *
   * Il segno admin (R19 della revisione 5B) non da' il potere, che e' la chiave: toglie quello che una chiave rimasta per
   * sbaglio nella cassaforte (una distruzione fallita in silenzio, un ex admin rientrato come familiare con un id nuovo) non
   * deve poter fare, cioe' firmare la configurazione della famiglia da un dispositivo che la famiglia non conosce come admin.
   */
  suspend fun adminContext(): AdminUnlock {
    val base = adminBase() ?: return AdminUnlock.Denied(AdminActionResult.NotAdmin)
    return unlock(base)
  }

  /** L'admin senza aprire la chiave (nessun foglio): null se questo dispositivo non e' un admin approvato del registro fidato. */
  suspend fun adminBase(): AdminBase? {
    if (!engine.ensureLoaded()) return null
    val state = engine.state ?: return null
    val me = try {
      engine.myDeviceId()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // R7: il Keystore non risponde, non si sa chi e' questo dispositivo: nessuna azione dell'admin (prima l'eccezione usciva).
      GalleryLog.w(TAG) { "Keystore che non risponde: niente azioni dell'admin" }
      null
    } ?: return null
    if (state.statusOf(me) != DeviceStatus.APPROVED) return null
    if (state.registry.device(me)?.admin != true) return null
    val share = engine.profile()?.commonShare ?: state.registry.config.commonShare
    return AdminBase(state, me, share)
  }

  /**
   * La chiave di famiglia per **questa** azione: la cassaforte chiede l'impronta o il PIN (M3). [AdminUnlock.Denied] con
   * [AdminActionResult.NotAdmin] se la cassaforte non tiene la chiave che firma il registro fidato, con
   * [AdminActionResult.KeyRefused] se il foglio e' annullato, il telefono non ha un blocco schermo sicuro o la chiave del Keystore e'
   * invalidata (allora il kit di recupero).
   */
  suspend fun unlock(base: AdminBase): AdminUnlock {
    val opened = try {
      familyKeys.load(base.state.trustedFamilyKey, FamilyKeyUse.SIGN)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "chiave di famiglia non disponibile (${e.javaClass.simpleName})" }
      VaultResult.Refused(VaultRefusal.UNAVAILABLE)
    }
    return when (opened) {
      is VaultResult.Done -> AdminUnlock.Open(AdminContext(base.state, base.me, FamilyAuthority(opened.value, nasClock), base.commonShare))
      VaultResult.Absent -> AdminUnlock.Denied(AdminActionResult.NotAdmin)
      is VaultResult.Refused -> {
        GalleryLog.w(TAG) { "chiave di famiglia non aperta (${opened.reason})" }
        AdminUnlock.Denied(AdminActionResult.KeyRefused(opened.reason))
      }
    }
  }

  suspend fun commit(admin: AdminContext, draft: RegistryDraft): AdminActionResult {
    val result = try {
      sessions.onShare(admin.commonShare, Purpose.INTERACTIVE) { fs -> admin.authority.commit(fs, draft) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Una modifica non firmabile (per esempio un registro oltre il tetto): nessun byte e' stato scritto.
      GalleryLog.e(TAG, e) { "pubblicazione del registro non riuscita" }
      NasResult.Err(UNEXPECTED)
    }
    return when (result) {
      is NasResult.Err -> AdminActionResult.Failed(result.error)
      is NasResult.Ok -> when (val commit = result.value) {
        is CommitResult.Published -> {
          engine.adopt(commit.state)
          _pendingForks.value = null
          _blockedPaths.value = emptyList()
          _unverifiedBeyond.value = commit.unverifiedBeyond
          AdminActionResult.Done
        }
        CommitResult.NewerExists -> {
          engine.sync(Purpose.INTERACTIVE)
          AdminActionResult.NewerExists
        }
        is CommitResult.Fork -> {
          _pendingForks.value = PendingForks(draft, commit.forks)
          AdminActionResult.Fork
        }
        is CommitResult.Blocked -> {
          _blockedPaths.value = commit.junk
          AdminActionResult.Blocked
        }
        CommitResult.NeedsAppUpdate -> AdminActionResult.NeedsAppUpdate
      }
    }
  }

  /** Cambia lo stato di un dispositivo (non il proprio): dismesso, revocato, perso. */
  suspend fun changeStatus(deviceId: String, status: DeviceStatus): AdminActionResult = changeStatusAll(listOf(deviceId), status)

  /**
   * Lo stesso cambio di stato per piu' dispositivi, una versione per dispositivo, con **un solo** foglio dell'impronta o del PIN (un
   * gesto dell'admin: le segnalazioni "perso" convertite insieme, M3). Tutto si controlla prima del foglio; alla prima versione non
   * pubblicata ci si ferma e si restituisce il suo esito.
   */
  suspend fun changeStatusAll(deviceIds: List<String>, status: DeviceStatus): AdminActionResult {
    val ids = deviceIds.distinct()
    if (ids.isEmpty()) return AdminActionResult.Done
    val base = adminBase() ?: return AdminActionResult.NotAdmin
    val frozenOf = HashMap<String, List<String>>()
    for (deviceId in ids) {
      val entry = base.state.registry.device(deviceId) ?: return AdminActionResult.Failed(NasError.NotFound)
      if (deviceId == base.me || !entry.status.canBecome(status)) return AdminActionResult.Failed(NasError.PermissionDenied)
      // La dismissione congela le teste gia' verificate su questo dispositivo (A7): R6, quelle degli album condivisi (cosi' un telefono dismesso
      // non fa sparire a tutti i suoi contributi, la definizione dell'album compresa). Le teste dell'indice e dei dati personali non le dice ancora
      // nessuno. Se chi le cerca sbaglia, la dismissione non parte (si riprova): dismettere senza di loro farebbe sparire i contributi.
      frozenOf[deviceId] = if (status != DeviceStatus.RETIRED) {
        emptyList()
      } else {
        try {
          retireHeads.headsOf(deviceId).distinct().take(TrustLimits.MAX_FROZEN_HEADS)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          GalleryLog.w(TAG) { "teste da congelare non lette: ${e.javaClass.simpleName}" }
          return AdminActionResult.Failed(UNEXPECTED)
        }
      }
    }
    var admin = when (val unlocked = unlock(base)) {
      is AdminUnlock.Open -> unlocked.admin
      is AdminUnlock.Denied -> return unlocked.result
    }
    for ((index, deviceId) in ids.withIndex()) {
      // Dopo la prima versione la base e' quella appena pubblicata e adottata; la chiave aperta e' la stessa (un solo foglio per il gesto).
      if (index > 0) admin = AdminContext(engine.state ?: return AdminActionResult.Failed(UNEXPECTED), admin.me, admin.authority, admin.commonShare)
      val draft = try {
        admin.authority.changeStatus(admin.state, deviceId, status, frozenOf.getValue(deviceId))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        return AdminActionResult.Failed(NasError.PermissionDenied)
      }
      val result = commit(admin, draft)
      if (result != AdminActionResult.Done) return result
    }
    return AdminActionResult.Done
  }

  /**
   * Il nome da mostrare di un dispositivo approvato (F27, E-B7), nella voce firmata del registro: una versione nuova firmata dall'admin, come ogni
   * altra modifica, con la sua chiave e il suo account invariati (`FamilyAuthority.renameDevice`). Un nome che un altro dispositivo ha gia' (anche
   * "sembra uguale": `LookAlike`) e' rifiutato con `NasError.Exists`, uno non valido con `NasError.PermissionDenied` (la pagina lo controlla prima e non
   * lo manda); lo stesso nome di prima non firma niente (e non chiede l'impronta).
   */
  suspend fun renameDevice(deviceId: String, name: String): AdminActionResult {
    val base = adminBase() ?: return AdminActionResult.NotAdmin
    val entry = base.state.registry.device(deviceId) ?: return AdminActionResult.Failed(NasError.NotFound)
    if (entry.status != DeviceStatus.APPROVED) return AdminActionResult.Failed(NasError.PermissionDenied)
    if (entry.name == name) return AdminActionResult.Done
    val key = LookAlike.key(name)
    if (base.state.registry.devices.any { it.id != deviceId && LookAlike.key(it.name) == key }) return AdminActionResult.Failed(NasError.Exists)
    try {
      TextRules.displayText(name, TrustLimits.MAX_DISPLAY_TEXT_CHARS)
    } catch (e: TrustViolation) {
      return AdminActionResult.Failed(NasError.PermissionDenied)
    }
    val admin = when (val unlocked = unlock(base)) {
      is AdminUnlock.Open -> unlocked.admin
      is AdminUnlock.Denied -> return unlocked.result
    }
    val draft = try {
      admin.authority.renameDevice(admin.state, deviceId, name)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return AdminActionResult.Failed(NasError.PermissionDenied)
    }
    return commit(admin, draft)
  }

  /**
   * Le esclusioni dell'area comune nella configurazione firmata (verifica funzionale, ON-8): il passo 5 ripreso dopo la
   * fondazione cambiava solo l'avanzamento locale, e la scansione (che usa quelle firmate) non lo vedeva. Stesse esclusioni
   * di prima: niente da firmare.
   */
  suspend fun updateCommonExclusions(excluded: Set<String>): AdminActionResult {
    val sorted = excluded.sorted()
    return amendConfig { it.copy(commonExclusions = sorted) }
  }

  /**
   * L'indirizzo del NAS nella configurazione firmata (verifica funzionale, RE-5): prima l'admin lo cambiava solo sul suo telefono e il
   * QR d'invito portava ancora l'IP vecchio. L'identita' del NAS (GUID) non cambia: l'ha gia' verificata la pagina che ha chiesto la
   * modifica, e il GUID firmato resta quello di prima.
   */
  suspend fun updateNasAddress(ip: String): AdminActionResult {
    val host = FamilyConfigFactory.hostOf(ip)
    return amendConfig { it.copy(nasHost = host) }
  }

  /**
   * I prefissi ULA di casa nella configurazione firmata (RE-5, E-R16). Come al passo 12 della fondazione: solo i /48 ULA nella forma del
   * formato, senza ripetizioni, in ordine, al massimo [TrustLimits.MAX_HOME_ULA]. Nessun prefisso valido = nessuna modifica (un invito
   * senza ULA non e' valido).
   */
  suspend fun updateHomeUla(prefixes: Set<String>): AdminActionResult {
    val canonical = prefixes.mapNotNull(UlaPrefix::canonical).distinct().sorted().take(TrustLimits.MAX_HOME_ULA)
    if (canonical.isEmpty()) return AdminActionResult.Failed(NasError.PermissionDenied)
    return amendConfig { it.copy(homeUla = canonical) }
  }

  /**
   * Una modifica alla configurazione firmata, con lo stesso controllo dell'admin di ogni azione (dispositivo approvato, chiave di
   * famiglia nella cassaforte a telefono sbloccato, impronta o PIN: M3). La stessa configurazione di prima non firma niente e non
   * chiede l'impronta.
   */
  private suspend fun amendConfig(change: (FamilyConfig) -> FamilyConfig): AdminActionResult {
    val base = adminBase() ?: return AdminActionResult.NotAdmin
    val config = base.state.registry.config
    val changed = try {
      change(config)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return AdminActionResult.Failed(NasError.PermissionDenied)
    }
    if (changed == config) return AdminActionResult.Done
    val admin = when (val unlocked = unlock(base)) {
      is AdminUnlock.Open -> unlocked.admin
      is AdminUnlock.Denied -> return unlocked.result
    }
    val draft = try {
      admin.authority.updateConfig(admin.state, changed)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Una configurazione che il formato non accetta (percorsi, indirizzo o tetti): niente da firmare.
      return AdminActionResult.Failed(NasError.PermissionDenied)
    }
    return commit(admin, draft)
  }

  /** La scelta dell'admin sui rami in attesa: [recognize] li unisce, altrimenti li rifiuta (poi reset). */
  suspend fun resolveForks(recognize: Boolean): AdminActionResult {
    val pending = _pendingForks.value ?: return AdminActionResult.Done
    val base = adminBase() ?: return AdminActionResult.NotAdmin
    if (base.state.contentHash != pending.draft.base.contentHash) {
      _pendingForks.value = null
      return AdminActionResult.NewerExists
    }
    val admin = when (val unlocked = unlock(base)) {
      is AdminUnlock.Open -> unlocked.admin
      is AdminUnlock.Denied -> return unlocked.result
    }
    val joined = try {
      if (recognize) admin.authority.mergeForks(pending.draft, pending.forks) else admin.authority.rejectForks(pending.draft, pending.forks)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return AdminActionResult.Fork
    }
    return commit(admin, joined)
  }

  /**
   * La versione fidata e' stata sostituita con spazzatura sul NAS (revisione 1.4, N3): l'admin ripubblica lo stesso contenuto con un
   * numero nuovo. Fase 10, M3: **non** si fa piu' da sola durante un controllo del registro (un controllo in background non apre mai
   * la chiave di famiglia); la chiede l'admin con il tasto della pagina dei dispositivi, con il foglio dell'impronta o del PIN.
   */
  suspend fun reissue(): AdminActionResult {
    val base = adminBase() ?: return AdminActionResult.NotAdmin
    val admin = when (val unlocked = unlock(base)) {
      is AdminUnlock.Open -> unlocked.admin
      is AdminUnlock.Denied -> return unlocked.result
    }
    return commit(admin, admin.authority.reissue(admin.state))
  }

  private companion object {
    const val TAG = "RegistryWriter"
  }
}
