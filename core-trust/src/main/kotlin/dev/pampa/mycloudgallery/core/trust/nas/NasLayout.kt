package dev.pampa.mycloudgallery.core.trust.nas

import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import java.nio.ByteBuffer

/**
 * I percorsi dei dati dell'app in una share (`docs/formato-nas.md` §2). Ogni nome e' costruito da
 * costanti e da id/hash/numeri gia' validati; i nomi letti da un elenco passano dai parse stretti qui
 * sotto e, se non tornano, si ignorano.
 */
object NasLayout {

  val ROOT: SharePath = SharePath.of(TrustFormat.ROOT_DIR)

  // --- Famiglia (solo nell'area comune) -----------------------------------------------------------------
  val REGISTRY_DIR: SharePath = ROOT.child("family").child("registry")
  val REQUESTS_DIR: SharePath = ROOT.child("family").child("requests")

  val REPORTS_DIR: SharePath = ROOT.child("family").child("reports")

  fun registryFile(version: Long): SharePath = REGISTRY_DIR.child(versionName(version))

  fun requestFile(deviceId: String): SharePath = REQUESTS_DIR.child(deviceId)

  /**
   * Le segnalazioni "perso" per [targetId] (tipo 7): una serie numerata, nell'area comune (per l'admin) e, con
   * lo stesso percorso, nell'area privata dell'account (prova di possesso dell'account, revisione B6).
   */
  fun reportDir(targetId: String): SharePath = REPORTS_DIR.child(targetId)

  fun reportFile(targetId: String, version: Long): SharePath = reportDir(targetId).child(versionName(version))

  /** Lo stato pubblicato da un dispositivo (tipo 8), nell'area comune. */
  fun statusDir(deviceId: String): SharePath = ROOT.child("devices").child(deviceId).child("status")

  fun statusFile(deviceId: String, version: Long): SharePath = statusDir(deviceId).child(versionName(version))

  // --- Dati di un dispositivo -------------------------------------------------------------------------
  fun headDir(deviceId: String, kind: String): SharePath = ROOT.child("devices").child(deviceId).child(kind).child("head")

  fun headFile(deviceId: String, kind: String, version: Long): SharePath = headDir(deviceId, kind).child(versionName(version))

  fun segmentFile(deviceId: String, kind: String, hash: String): SharePath =
    ROOT.child("devices").child(deviceId).child(kind).child("seg").child(hash)

  // --- Fase 6: dati AI e copie dei modelli (A1, D6, D18) -------------------------------------------------------------------

  /** Il tipo (e la cartella) delle teste dei dati AI: `devices/<id>/ai/` (D6). */
  const val AI_KIND: String = "ai"

  /** Una testa `ai`: `devices/<id>/ai/head/<versione>` (versione 2 del formato, serie propria). */
  fun aiHead(deviceId: String, version: Long): SharePath = headFile(deviceId, AI_KIND, version)

  /** Un segmento `vec` o `ocr`: `devices/<id>/ai/seg/<impronta>`. */
  fun aiSegment(deviceId: String, hash: String): SharePath = segmentFile(deviceId, AI_KIND, hash)

  /**
   * La copia di un modello pubblicata da un dispositivo nell'area comune (D18): `devices/<id>/models/<sha256>`, file grezzo senza
   * busta ne' estensione (eccezione dichiarata al §2.1). [sha256] in esadecimale minuscolo (64 cifre), come nel catalogo dei modelli.
   */
  fun modelCopy(deviceId: String, sha256: String): SharePath {
    require(SHA256_HEX.matches(sha256)) { "Impronta del modello non valida." }
    return ROOT.child("devices").child(deviceId).child("models").child(sha256)
  }

  private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

  // --- Prenotazioni ---------------------------------------------------------------------------------------
  /** `leases/<impronta della risorsa>/<gruppo>/<generazione>`: gruppi di 256 generazioni (revisione 1.4, M6). */
  fun leaseDir(resource: String): SharePath = ROOT.child("leases").child(resourceName(resource))

  fun leaseShardOf(generation: Long): Long = (generation - 1) / TrustLimits.LEASE_SHARD_SIZE + 1

  fun leaseShardDir(resource: String, shard: Long): SharePath = leaseDir(resource).child(versionName(shard))

  fun leaseFile(resource: String, generation: Long): SharePath =
    leaseShardDir(resource, leaseShardOf(generation)).child(versionName(generation))

  fun resourceName(resource: String): String = Hashes.sha256Name(resource.toByteArray(Charsets.UTF_8))

  // --- Cestino ----------------------------------------------------------------------------------------------
  fun trashRecordFile(deviceId: String, trashId: String): SharePath =
    ROOT.child("trash").child(deviceId).child("rec").child(trashId)

  /** Il file cestinato, senza estensione: non compare nei servizi multimediali (E-I13). */
  fun trashItemFile(deviceId: String, trashId: String): SharePath =
    ROOT.child("trash").child(deviceId).child("files").child(trashId)

  // --- Album condivisi (Fase 7, D6, A1: solo nell'area comune; [albumId] e' l'id in base32, 26 caratteri) -------------------
  /** `albums/`: una cartella per album. */
  val ALBUMS_DIR: SharePath = ROOT.child("albums")

  /** `albums/<album>/`: una cartella per dispositivo che contribuisce. */
  fun albumDir(albumId: String): SharePath = ALBUMS_DIR.child(albumId)

  /** `albums/<album>/<dispositivo>/`: il contributo di un dispositivo (l'unica cartella in cui quel dispositivo scrive per l'album). */
  fun albumContributionDir(albumId: String, deviceId: String): SharePath = albumDir(albumId).child(deviceId)

  fun albumHeadDir(albumId: String, deviceId: String): SharePath = albumContributionDir(albumId, deviceId).child("head")

  /** Una testa d'album: `albums/<album>/<dispositivo>/head/<versione>` (tipo 6, versione 3 del formato). */
  fun albumHead(albumId: String, deviceId: String, version: Long): SharePath = albumHeadDir(albumId, deviceId).child(versionName(version))

  /** Un segmento d'album: `albums/<album>/<dispositivo>/seg/<impronta>` (classe `album`, 17). */
  fun albumSegment(albumId: String, deviceId: String, hash: String): SharePath = albumContributionDir(albumId, deviceId).child("seg").child(hash)

  // --- Dati dei volti (Fase 8, D11, D12, D16, A1, A5) ------------------------------------------------------------------------------
  // SOLO nell'area privata dell'account che ha attivato Persone, mai nell'area comune (chi chiama lo controlla: L23). `<G>` e' la
  // generazione in base32 (26 caratteri, `FaceGeneration`). Ogni percorso e' costruito qui da valori gia' validati: un percorso dei
  // volti non si legge mai da un dato (D31, formato §9 punto 6-bis). Il codec e la pubblicazione sono di P3, la cancellazione di P8.

  /** `faces/`: una cartella per generazione. */
  val FACES_DIR: SharePath = ROOT.child("faces")

  /** `faces/<G>/`: tutto cio' che appartiene a una generazione (cartelle dei dispositivi e prenotazioni); "Disattiva e cancella" la toglie intera. */
  fun facesGenerationDir(generation: FaceGeneration): SharePath = FACES_DIR.child(generation.base32)

  /** `faces/<G>/<dispositivo>/`: l'unica cartella in cui quel dispositivo scrive per la generazione [generation]. */
  fun facesDeviceDir(generation: FaceGeneration, deviceId: String): SharePath {
    require(TrustIds.isValid(deviceId)) { "Id di dispositivo non valido." }
    return facesGenerationDir(generation).child(deviceId)
  }

  fun facesHeadDir(generation: FaceGeneration, deviceId: String): SharePath = facesDeviceDir(generation, deviceId).child("head")

  /** Una testa dei volti: `faces/<G>/<dispositivo>/head/<versione>` (tipo 9, versione 4 del formato). */
  fun facesHead(generation: FaceGeneration, deviceId: String, version: Long): SharePath =
    facesHeadDir(generation, deviceId).child(versionName(version))

  fun facesSegmentDir(generation: FaceGeneration, deviceId: String): SharePath = facesDeviceDir(generation, deviceId).child("seg")

  /** Un segmento dei volti: `faces/<G>/<dispositivo>/seg/<impronta>` (classi `faces`, `cluster`, `people`). */
  fun facesSegment(generation: FaceGeneration, deviceId: String, hash: String): SharePath {
    require(Hashes.isHashName(hash)) { "Impronta non valida." }
    return facesSegmentDir(generation, deviceId).child(hash)
  }

  /**
   * `faces/<G>/leases/`: la radice delle prenotazioni dei volti (D16, A5), **sotto la generazione**: "Disattiva e cancella" toglie anche
   * loro. Stessa struttura di `leases/` (gruppi di 256 generazioni di prenotazione); `LeaseManager` prende la radice da P3.
   */
  fun facesLeaseRoot(generation: FaceGeneration): SharePath = facesGenerationDir(generation).child(FACES_LEASES)

  fun facesLeaseDir(generation: FaceGeneration, resource: String): SharePath = facesLeaseRoot(generation).child(resourceName(resource))

  fun facesLeaseShardDir(generation: FaceGeneration, resource: String, shard: Long): SharePath =
    facesLeaseDir(generation, resource).child(versionName(shard))

  /** Una prenotazione dei volti: `faces/<G>/leases/<impronta della risorsa>/<gruppo>/<numero>` (tipo 4). */
  fun facesLeaseFile(generation: FaceGeneration, resource: String, number: Long): SharePath =
    facesLeaseShardDir(generation, resource, leaseShardOf(number)).child(versionName(number))

  /** Il nome della cartella delle prenotazioni sotto `faces/<G>/` (mai un id di dispositivo: un id ha 26 caratteri base32). */
  const val FACES_LEASES: String = "leases"

  /** Le due aree di un'unita' dei volti (D16): mai il nome della share. */
  const val FACES_AREA_PRIVATE: String = "priv"
  const val FACES_AREA_COMMON: String = "comm"

  /**
   * La risorsa di un'unita' di calcolo dei volti (D16, A5): `faces/<G>/work/<priv|comm>/<aaaa-mm>/<parte>`, 200 media per chiave di
   * contenuto. **Mai nomi di share, cartelle o file**: la risorsa sta in chiaro nel record firmato.
   */
  fun facesWorkResource(generation: FaceGeneration, area: String, month: String, part: Int): String {
    require(area == FACES_AREA_PRIVATE || area == FACES_AREA_COMMON) { "Area non valida." }
    require(FACES_MONTH.matches(month)) { "Mese non valido: aaaa-mm." }
    require(part >= 0) { "Parte negativa." }
    return "faces/${generation.base32}/work/$area/$month/$part"
  }

  /** La risorsa del custode del raggruppamento (D21): `faces/<G>/cluster`. */
  fun facesClusterResource(generation: FaceGeneration): String = "faces/${generation.base32}/cluster"

  private val FACES_MONTH = Regex("^(0000-00|[0-9]{4}-(0[1-9]|1[0-2]))$")

  // --- Kit di recupero (area privata dell'admin) ------------------------------------------------------
  /**
   * Un kit per ogni chiave di famiglia (revisione 1.4, M4): dopo una rotazione il kit nuovo ha un posto suo,
   * e quello vecchio si riconosce e si cancella (azione locale confermata, Fase 2.4).
   */
  fun recoveryKitFile(familyId: String, keyFingerprint: String): SharePath =
    RECOVERY_DIR.child(familyId).child(keyFingerprint)

  /** `recovery/`: una cartella per famiglia, un file per chiave. Il recupero dell'admin la elenca (solo nomi validi). */
  val RECOVERY_DIR: SharePath = ROOT.child("recovery")

  // --- Nomi numerati ----------------------------------------------------------------------------------
  private const val VERSION_DIGITS = 20

  /** 20 cifre con zeri a sinistra: l'ordine dei nomi e' l'ordine dei numeri. */
  fun versionName(version: Long): String {
    require(version >= 1) { "Le versioni partono da 1." }
    return version.toString().padStart(VERSION_DIGITS, '0')
  }

  /** Il numero di un nome lungo esattamente 20 cifre ASCII, tra 1 e [TrustLimits.MAX_VERSION], oppure null. */
  fun parseVersionName(name: String): Long? {
    if (name.length != VERSION_DIGITS || !name.all { it in '0'..'9' }) return null
    return name.toLongOrNull()?.takeIf { it in 1..TrustLimits.MAX_VERSION }
  }
}

/**
 * Nomi dei file LOCALI (cache, miniature, replica) derivati da un hash, mai da testo letto dal NAS
 * (`docs/formato-nas.md` §8.3): 53 caratteri `[0-9a-z]`, senza estensione, uguali su ogni dispositivo.
 */
object LocalNames {
  fun of(vararg parts: String): String {
    var size = 0
    val encoded = parts.map { it.toByteArray(Charsets.UTF_8).also { b -> size += 4 + b.size } }
    val buffer = ByteBuffer.allocate(size)
    encoded.forEach { buffer.putInt(it.size).put(it) }
    return "m" + Base32.encode(Hashes.sha256("mycloudgallery/local-name\u0000".toByteArray(), buffer.array()))
  }
}

/** Il tetto di pixel per decodificare un'immagine arrivata dall'indice (piano 1.4). */
object ImageBounds {
  private const val MAX_SIDE = 16384

  fun isDecodable(width: Int, height: Int): Boolean =
    width in 1..MAX_SIDE && height in 1..MAX_SIDE && width.toLong() * height <= TrustLimits.MAX_INDEX_IMAGE_PIXELS
}
