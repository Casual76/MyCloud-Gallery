package dev.pampa.mycloudgallery.albums

import dev.pampa.mycloudgallery.core.albums.contract.AlbumClocks
import dev.pampa.mycloudgallery.core.albums.contract.AlbumFamily
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteBlock
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteGate
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.SharedOrigins
import dev.pampa.mycloudgallery.core.albums.shared.AlbumIdentity
import dev.pampa.mycloudgallery.core.albums.shared.AlbumReadGate
import dev.pampa.mycloudgallery.core.albums.shared.RegistryAlbumView
import dev.pampa.mycloudgallery.core.albums.shared.SharedAlbumChoices
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.index.contract.IndexWriteGate
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.nas.session.ShareGuard
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.nas.NasInputs
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

// CHI SONO, CHE ORE SONO E SE SI PUO' SCRIVERE, PER GLI ALBUM (Fase 7, P13; §1.4, D30, D36, D38). Tutto dal registro firmato accettato, dalle scelte
// dell'onboarding e dagli orologi che il resto dell'app usa gia': niente di nuovo da sapere, niente dal NAS da solo.

/** [AlbumFamily] dal registro accettato e dalle aree dell'onboarding. */
class AppAlbumFamily(private val inputs: NasInputs, private val deviceFolder: () -> String?) : AlbumFamily {
  private fun registry(): RegistryState? = inputs.registry()

  override fun me(): AccountId? {
    val device = inputs.deviceId()?.let { registry()?.registry?.device(it) }
    return device?.nasUser?.let(AccountId::of) ?: inputs.account()?.let(AccountId::of)
  }

  override fun commonShare(): String? = registry()?.registry?.config?.commonShare ?: inputs.shares().firstOrNull { it.area == NasArea.COMMON }?.share

  override fun privateShare(): String? = inputs.shares().firstOrNull { it.area == NasArea.PRIVATE }?.share

  override fun amAdmin(): Boolean {
    val id = inputs.deviceId() ?: return false
    return registry()?.registry?.device(id)?.let { it.admin && it.status == DeviceStatus.APPROVED } == true
  }

  override fun leftFamily(account: AccountId): Boolean = registry()?.let { RegistryAlbumView(it).accountLeft(account) } ?: false

  /** "Backup/<cartella del dispositivo>" (P4: la destinazione di "Riporta" senza cartella d'origine; P6: la regola B). */
  override fun backupFolderName(): String = deviceFolder()?.let { "$BACKUP/$it" } ?: BACKUP

  private companion object {
    const val BACKUP = "Backup"
  }
}

/** Gli orologi degli album (D36): monotono per durate e scadenze; ora del NAS per le voci, **null** con il salto dell'ora (D38, come i dati personali). */
class AppAlbumClocks(private val inputs: NasInputs, private val monotonic: () -> Long, private val clockBlocked: () -> Boolean) : AlbumClocks {
  override fun monotonicMs(): Long = monotonic()

  override fun nasNowMs(): Long? = if (clockBlocked()) null else inputs.nasClock.nowMillisOrNull()
}

/** [AlbumIdentity]: il dispositivo, la sua chiave del Keystore e il registro accettato. Null-safe: senza chiave la famiglia non e' pronta. */
class AppAlbumIdentity(private val inputs: NasInputs) : AlbumIdentity {
  override val deviceId: String get() = inputs.deviceId() ?: NO_DEVICE
  override val signer: Signer get() = inputs.signer() ?: throw IllegalStateException("Nessuna chiave del dispositivo.")

  override fun registry(): RegistryState? = if (inputs.deviceId() == null || inputs.signer() == null) null else inputs.registry()

  private companion object {
    /** Un id ben formato che il registro non conosce mai (la famiglia non e' pronta: [registry] e' null e nessuno scrive). */
    val NO_DEVICE: String = dev.pampa.mycloudgallery.core.trust.codec.Base32.encode(ByteArray(16))
  }
}

/** D30: si leggono gli album condivisi di una share solo se la recinzione lo permette (sulle share vere nessun album, vero o falso, prima del consenso). */
class FlagsAlbumReadGate(private val flags: FeatureFlags) : AlbumReadGate {
  override fun mayRead(share: String): Boolean = flags.sharedAlbumsMayUse(share)
}

/**
 * Il cancello delle scritture degli album (§1.4, D30, D38): la recinzione D30 (`FeatureFlags.sharedAlbumsMayUse`, accesa anche in release fino al
 * consenso Q7), la recinzione D33 delle prove sul campo (`ShareFence`, **solo debug**: [guard] null in release) e i cancelli delle teste dell'indice
 * ([index]: emergenza, fiducia, congelamento, salto dell'ora, sola lettura). Non quello di D31 (l'indice "solo locale"): gli album condivisi hanno la
 * loro recinzione e il loro consenso (Q7 (a)). Senza NAS o senza account: [AlbumWriteBlock.NO_NAS].
 */
class AppAlbumWriteGate(
  private val flags: FeatureFlags,
  private val guard: ShareGuard?,
  private val user: () -> String?,
  private val index: () -> IndexWriteGate?,
) : AlbumWriteGate {
  override suspend fun blockFor(share: String): AlbumWriteBlock? {
    if (!flags.sharedAlbumsMayUse(share)) return AlbumWriteBlock.TEST_SHARES_ONLY
    val account = user() ?: return AlbumWriteBlock.NO_NAS
    if (guard != null && !guard.allows(account, share)) return AlbumWriteBlock.FENCE
    val gate = index() ?: return AlbumWriteBlock.NO_NAS
    return when (gate.blockFor(share)) {
      null -> null
      WriteBlock.FROZEN -> AlbumWriteBlock.FROZEN
      WriteBlock.CLOCK_JUMP -> AlbumWriteBlock.CLOCK_JUMP
      WriteBlock.READ_ONLY_AREA -> AlbumWriteBlock.READ_ONLY
      WriteBlock.EMERGENCY_STOP -> AlbumWriteBlock.EMERGENCY_STOP
      WriteBlock.NOT_TRUSTED -> AlbumWriteBlock.NOT_TRUSTED
      else -> AlbumWriteBlock.NOT_TRUSTED
    }
  }
}

/**
 * Le scelte di questo account sugli album degli altri (P2): gli album lasciati sono dati personali (`shared_album_left:<id>`, E-SH21: non si vedono
 * piu' su nessun dispositivo dell'account); gli avvisi visti sono di **questo dispositivo** ([seen], un file): un avviso visto sul telefono si vede
 * ancora una volta sul tablet. [store] e' null senza NAS: niente si puo' scrivere (si dice con un'eccezione), niente risulta lasciato.
 */
class PersonalSharedAlbumChoices(private val store: () -> PersonalStore?, seen: File) : SharedAlbumChoices {
  private val notices = object : DurableRecords<String, Boolean>(seen, "avvisi degli album condivisi visti") {
    override fun encode(out: DataOutputStream, records: Map<String, Boolean>) {
      out.writeInt(1)
      out.writeInt(records.size)
      records.keys.forEach(out::writeUTF)
    }

    override fun decode(input: DataInputStream): LinkedHashMap<String, Boolean> {
      if (input.readInt() != 1) throw IOException("versione")
      val out = LinkedHashMap<String, Boolean>()
      repeat(input.readInt()) { out[input.readUTF()] = true }
      return out
    }
  }

  override suspend fun left(): Set<AlbumId> {
    val entries = store()?.effectiveEntries?.value ?: return emptySet()
    return entries.mapNotNullTo(LinkedHashSet()) { (key, entry) ->
      if (key.kind != PersonalKind.SETTING || key.subject != PersonalFields.SETTINGS_SUBJECT) return@mapNotNullTo null
      val raw = PersonalFields.sharedAlbumLeftId(key.field) ?: return@mapNotNullTo null
      if ((entry.value as? PersonalValue.Flag)?.on != true) return@mapNotNullTo null
      runCatching { AlbumId(raw) }.getOrNull()
    }
  }

  override suspend fun setLeft(album: AlbumId, left: Boolean) {
    val personal = store() ?: throw IllegalStateException("Dati personali non pronti.")
    personal.set(PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, PersonalFields.sharedAlbumLeft(album.value)), PersonalValue.Flag(left))
  }

  override suspend fun dismissed(): Set<String> = notices.read { it.keys.toSet() }

  override suspend fun dismiss(notice: String) {
    notices.update { it[notice] = true }
  }
}

/**
 * D31: la cartella d'origine (area privata) dei miei media spostati in un album condiviso, voce personale `shared_origin` (`MEDIA_FLAG`, area privata:
 * mai nella definizione comune). Si registra **prima** dello spostamento: senza dati personali non si registra (eccezione: l'orchestratore riprova).
 */
class PersonalSharedOrigins(private val store: () -> PersonalStore?) : SharedOrigins {
  private fun keyOf(media: MediaId) = PersonalKey(PersonalKind.MEDIA_FLAG, media.value, PersonalFields.SHARED_ORIGIN)

  override suspend fun record(media: MediaId, origin: ShareFolder) {
    val personal = store() ?: throw IllegalStateException("Dati personali non pronti.")
    personal.set(keyOf(media), PersonalValue.Text(origin.share + SEPARATOR + origin.path.value))
  }

  override suspend fun originOf(media: MediaId): ShareFolder? {
    val text = (store()?.get(keyOf(media))?.value as? PersonalValue.Text)?.text ?: return null
    val at = text.indexOf(SEPARATOR)
    if (at <= 0) return null
    return runCatching { ShareFolder(text.substring(0, at), SharePath.parse(text.substring(at + 1))) }.getOrNull()
  }

  private companion object {
    /** Fra share e percorso: un carattere che non sta mai nel nome di una share ne' in un percorso del NAS. */
    const val SEPARATOR = "|"
  }
}
