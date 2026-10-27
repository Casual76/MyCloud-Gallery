package dev.pampa.mycloudgallery.core.albums.share

import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.AlbumTitles
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteBlock
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.MediaFacts
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.ShareBlock
import dev.pampa.mycloudgallery.core.albums.contract.ShareExclusion
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction
import dev.pampa.mycloudgallery.core.albums.contract.SharePlan
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanItem
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanKind
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumLimits
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/**
 * IL PIANO DELLA CONDIVISIONE (docs/fase7-progetto.md §2.5 passo 1, D12, D30, D31, D38, D39; E-SH9, E-SH11, E-SH19, E-SH22). Puro sopra le porte:
 * **niente si scrive, nessuna porta che sposta o carica si chiama**. Lo usa [ShareOrchestrator] per `plan` e per ricontrollare il piano allo
 * `start` (stessa impronta, o `PlanChanged`: serve un foglio nuovo).
 *
 * Per ogni media (solo dal **catalogo locale**, mai dal NAS): dove sta e cosa gli succede.
 * - Solo sul telefono → caricamento nella cartella dell'album (`UPLOAD`, E-SH1); nell'area privata mia → spostamento (`MOVE_IN`); gia' nell'area
 *   comune → riferimento, un file solo (`REFERENCE`, E-SH11); nell'area privata di un altro account → fuori (non si puo' aggiungere).
 * - Nascosti fuori con «Lascia fuori» (preselezionata, Q3 (a), come R11); con «Condividi anche i nascosti» dentro, contati (l'avviso E-SH9 lo
 *   mostra il foglio).
 * - Gia' nell'album → fuori. Oltre [SharedAlbumLimits.MAX_ITEMS] elementi → bloccato (detto nel foglio). Un riferimento con un percorso che
 *   la voce non puo' portare (oltre [SharedAlbumLimits.MAX_PATH_BYTES]) → fuori come non disponibile.
 * - "Riporta" (D31): solo media **miei**, nell'area comune, con la cartella d'origine (`shared_origin`), e, se sono elementi vivi di un album
 *   attivo, aggiunti da me e con la tabella che lo permette (§2.4, anche "Gli altri guardano" per cio' che ho aggiunto quando si poteva).
 * - Adozione (D39): solo file che il mio catalogo vede nell'area comune (e, se lo so, nella cartella dell'album): nessuno spostamento.
 * - Un media che una mia operazione aperta sta ancora portando (un elemento non finito) resta fuori da ogni altro piano finche' non ha finito.
 *
 * Cancelli (D30, D38, E-SH19): la recinzione degli album condivisi e i cancelli delle scritture valgono per l'area comune **e** per quella
 * privata (le sorgenti vanno nel suo cestino, "Riporta" ci scrive): con uno chiuso non si offre la conferma.
 *
 * Revisione B: i cancelli del **backup** (SG-1, I4: D12, R2 della 5A, radice dei caricamenti) non fermano il piano ma lo rendono vero: un media solo
 * sul telefono che non si potrebbe caricare resta fuori con il suo motivo (`UPLOAD_BLOCKED`), e i miei media che dopo lo spostamento avrebbero solo
 * la copia comune, con il guardiano fermo, si contano nel piano ([SharePlan.safeCopyBlocked]) perche' il foglio lo dica. Ogni piano ha il suo
 * tentativo (SH-1).
 */
class SharePlanner(private val ports: ShareOrchestratorPorts) {

  private fun blockOf(block: AlbumWriteBlock): ShareBlock = when (block) {
    AlbumWriteBlock.TEST_SHARES_ONLY, AlbumWriteBlock.FENCE -> ShareBlock.TEST_SHARES_ONLY
    AlbumWriteBlock.READ_ONLY -> ShareBlock.READ_ONLY
    AlbumWriteBlock.NO_NAS -> ShareBlock.NO_NAS
    AlbumWriteBlock.FROZEN, AlbumWriteBlock.EMERGENCY_STOP, AlbumWriteBlock.CLOCK_JUMP, AlbumWriteBlock.NOT_TRUSTED -> ShareBlock.WRITES_BLOCKED
  }

  /**
   * Il piano di [request]; [nonceHex] rifa' lo stesso piano di un album nuovo (lo `start`), [now] e' l'istante monotono, [attempt] il tentativo
   * (revisione B, SH-1: nuovo per ogni `plan`, quello del piano allo `start`).
   */
  suspend fun plan(request: ShareRequest, nonceHex: String?, now: Long, newNonce: () -> ByteArray, attempt: String = ""): SharePlanOutcome {
    val me = ports.family.me() ?: return SharePlanOutcome.Blocked(ShareBlock.NO_NAS)
    val common = ports.family.commonShare() ?: return SharePlanOutcome.Blocked(ShareBlock.NO_NAS)
    val private = ports.family.privateShare() ?: return SharePlanOutcome.Blocked(ShareBlock.NO_NAS)
    ports.gate.blockFor(common)?.let { return SharePlanOutcome.Blocked(blockOf(it)) }
    ports.gate.blockFor(private)?.let { return SharePlanOutcome.Blocked(blockOf(it)) }
    return when (request) {
      is ShareRequest.NewAlbum -> {
        AlbumTitles.problem(request.title)?.let { return SharePlanOutcome.InvalidTitle(it) }
        newAlbum(AlbumTitles.normalized(request.title), request.level, request.media, request.hidden, request, nonceHex, newNonce, now, me, common, attempt)
      }
      is ShareRequest.FromPersonal -> {
        val source = ports.personal(request.album) ?: return SharePlanOutcome.Blocked(ShareBlock.ALBUM_NOT_ACTIVE)
        AlbumTitles.problem(source.title)?.let { return SharePlanOutcome.InvalidTitle(it) }
        newAlbum(AlbumTitles.normalized(source.title), request.level, source.items, request.hidden, request, nonceHex, newNonce, now, me, common, attempt)
      }
      is ShareRequest.AddToShared -> existing(request.album, request.media, request.hidden, request, AlbumOp.ADD_MEDIA, now, attempt)
      is ShareRequest.FolderAdoption -> existing(request.album, request.media, HiddenChoice.SHARE_TOO, request, AlbumOp.ADOPT_FOLDER_FILES, now, attempt)
      is ShareRequest.BringBack -> bringBack(request, me, now, attempt)
    }
  }

  private suspend fun newAlbum(
    title: String,
    level: ShareLevel,
    media: List<MediaId>,
    hidden: HiddenChoice,
    request: ShareRequest,
    nonceHex: String?,
    newNonce: () -> ByteArray,
    now: Long,
    me: AccountId,
    common: String,
    attempt: String,
  ): SharePlanOutcome {
    val nonce = nonceHex?.let(::bytesOf) ?: newNonce()
    val album = AlbumId.derived(me, nonce)
    val folder = AlbumFolderNames.folderFor(title, ports.knownFolders())
    return build(
      request, SharePlanKind.SHARE, album, title, folder.value, level, hexOf(nonce), media, hidden, emptySet(), null, now, ShareFolder(common, folder), attempt,
    )
  }

  private suspend fun existing(
    album: AlbumId,
    media: List<MediaId>,
    hidden: HiddenChoice,
    request: ShareRequest,
    op: AlbumOp,
    now: Long,
    attempt: String,
  ): SharePlanOutcome {
    if (ports.shared.state(album) != SharedState.ACTIVE) return SharePlanOutcome.Blocked(ShareBlock.ALBUM_NOT_ACTIVE)
    if (!ports.allowed(album, op, null)) return SharePlanOutcome.NotAllowed(op)
    val folder = ports.shared.folder(album) ?: return SharePlanOutcome.Blocked(ShareBlock.ALBUM_NOT_ACTIVE)
    val adopt = op == AlbumOp.ADOPT_FOLDER_FILES
    val kind = if (adopt) SharePlanKind.ADOPT else SharePlanKind.SHARE
    val inFolder = if (adopt) ports.folderContents(folder) else null
    return build(
      request, kind, album, folder.path.name, folder.path.value, null, null, media, hidden, ports.shared.members(album).keys, inFolder, now, folder, attempt,
    )
  }

  /**
   * I media che una mia operazione aperta sta ancora portando da qualche parte (un elemento non finito), con l'album: non entrano in un altro piano
   * finche' non hanno finito (un "Riporta" mentre la voce non e' ancora pubblicata la lascerebbe senza file; due spostamenti dello stesso file si
   * contenderebbero la sorgente).
   */
  private suspend fun inFlight(): Map<MediaId, AlbumId> {
    val out = HashMap<MediaId, AlbumId>()
    for (op in ports.diary.openOps()) {
      for (item in ports.diary.items(op.id)) if (!item.state.terminal) out[item.media] = op.album
    }
    return out
  }

  private suspend fun build(
    request: ShareRequest,
    kind: SharePlanKind,
    album: AlbumId,
    title: String,
    folderLabel: String,
    level: ShareLevel?,
    nonceHex: String?,
    media: List<MediaId>,
    hidden: HiddenChoice,
    members: Set<MediaId>,
    inFolder: Set<MediaId>?,
    now: Long,
    folder: ShareFolder,
    attempt: String,
  ): SharePlanOutcome {
    val known = ports.facts.facts(media.distinct())
    val busy = inFlight()
    // Revisione B, SG-1 e I4: i cancelli del backup per questa cartella (caricamenti, guardiano), chiesti una volta per piano.
    val gates = if (kind == SharePlanKind.ADOPT) ShareBackupGates.OPEN else ports.backupGates(folder)
    val excluded = LinkedHashMap<ShareExclusion, Int>()
    val items = ArrayList<SharePlanItem>()
    var safeCopyBlocked = 0
    for (id in media.distinct()) {
      val f = known[id]
      val reason = busy[id]?.let { if (it == album) ShareExclusion.ALREADY_IN_ALBUM else ShareExclusion.UNAVAILABLE }
        ?: exclusionOf(id, f, kind, hidden, members, inFolder, gates)
      if (reason != null) {
        excluded.merge(reason, 1, Int::plus)
        continue
      }
      val action = when {
        kind == SharePlanKind.ADOPT -> ShareItemAction.ADOPT
        f!!.place == MediaPlace.PHONE_ONLY -> ShareItemAction.UPLOAD
        f.place == MediaPlace.MY_PRIVATE -> ShareItemAction.MOVE_IN
        else -> ShareItemAction.REFERENCE
      }
      // D28: un mio media spostato che il telefono non ha piu' chiede la copia del guardiano; se il backup la ferma, il foglio lo dice.
      if (action == ShareItemAction.MOVE_IN && !gates.safeCopies && f!!.mine && !f.onPhone) safeCopyBlocked++
      items += SharePlanItem(id, action, f!!.size, f.isVideo, f.hidden)
    }
    if (items.size + members.size > SharedAlbumLimits.MAX_ITEMS) return SharePlanOutcome.Blocked(ShareBlock.TOO_MANY_ITEMS)
    if (items.isEmpty()) return SharePlanOutcome.NothingToShare(excluded)
    return SharePlanOutcome.Ready(SharePlan(request, kind, album, title, folderLabel, level, nonceHex, items, excluded, hidden, now, attempt, safeCopyBlocked))
  }

  private fun exclusionOf(
    id: MediaId,
    f: MediaFacts?,
    kind: SharePlanKind,
    hidden: HiddenChoice,
    members: Set<MediaId>,
    inFolder: Set<MediaId>?,
    gates: ShareBackupGates,
  ): ShareExclusion? = when {
    f == null -> ShareExclusion.UNAVAILABLE
    id in members -> ShareExclusion.ALREADY_IN_ALBUM
    f.place == MediaPlace.OTHERS_PRIVATE -> ShareExclusion.OTHERS_AREA
    f.hidden && hidden == HiddenChoice.LEAVE_OUT -> ShareExclusion.HIDDEN_LEFT_OUT
    kind == SharePlanKind.ADOPT && (f.place != MediaPlace.COMMON || (inFolder != null && id !in inFolder)) -> ShareExclusion.NOT_IN_COMMON
    // Un file del NAS senza percorso (catalogo incompleto) non si sposta ne' si cita; il telefono senza copia non si carica.
    f.place != MediaPlace.PHONE_ONLY && f.nas == null -> ShareExclusion.UNAVAILABLE
    f.place == MediaPlace.PHONE_ONLY && !f.onPhone -> ShareExclusion.UNAVAILABLE
    // SG-1, I4: il caricamento dal telefono non partirebbe (cancello degli arrivi chiuso, cartella fuori dalla radice dei caricamenti).
    f.place == MediaPlace.PHONE_ONLY && !gates.uploads -> ShareExclusion.UPLOAD_BLOCKED
    // La voce porta il percorso del file nell'area comune (riferimento o adozione): oltre il tetto del formato non si puo' citare.
    f.place == MediaPlace.COMMON && f.nas!!.path.value.toByteArray(Charsets.UTF_8).size > SharedAlbumLimits.MAX_PATH_BYTES -> ShareExclusion.UNAVAILABLE
    f.contentKey == null && f.place != MediaPlace.PHONE_ONLY -> ShareExclusion.UNAVAILABLE
    else -> null
  }

  private suspend fun bringBack(request: ShareRequest.BringBack, me: AccountId, now: Long, attempt: String): SharePlanOutcome {
    val state = ports.shared.state(request.album) ?: return SharePlanOutcome.Blocked(ShareBlock.ALBUM_NOT_ACTIVE)
    val members = ports.shared.members(request.album)
    val known = ports.facts.facts(request.media.distinct())
    val busy = inFlight()
    val excluded = LinkedHashMap<ShareExclusion, Int>()
    val items = ArrayList<SharePlanItem>()
    for (id in request.media.distinct()) {
      val f = known[id]
      val origin = if (f != null && f.mine) ports.origins.originOf(id) else null
      val member = members[id]
      val reason = when {
        f == null || id in busy -> ShareExclusion.UNAVAILABLE
        !f.mine -> ShareExclusion.NOT_MINE
        f.place != MediaPlace.COMMON || f.nas == null -> ShareExclusion.NOT_IN_COMMON
        // D31: "Riporta" solo per i media di cui ho la cartella d'origine (nei miei dati personali, mai nella definizione comune).
        origin == null -> ShareExclusion.NOT_MINE
        // Un elemento vivo dell'album aggiunto da un altro non e' mio da riportare (nemmeno se il file e' "mio" nel catalogo).
        member != null && member != me -> ShareExclusion.NOT_MINE
        state == SharedState.ACTIVE && member != null && !ports.allowed(request.album, AlbumOp.BRING_BACK, id) -> ShareExclusion.NOT_MINE
        else -> null
      }
      if (reason != null) excluded.merge(reason, 1, Int::plus) else items += SharePlanItem(id, ShareItemAction.MOVE_OUT, f!!.size, f.isVideo, f.hidden)
    }
    if (items.isEmpty()) return SharePlanOutcome.NothingToShare(excluded)
    val label = ports.shared.folder(request.album)?.path?.name ?: ""
    return SharePlanOutcome.Ready(
      SharePlan(request, SharePlanKind.BRING_BACK, request.album, label, label, null, null, items, excluded, HiddenChoice.SHARE_TOO, now, attempt),
    )
  }

  companion object {
    fun hexOf(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun bytesOf(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /**
     * La cartella di "Riporta" per [origin] (D31): lei, se sta nell'area privata [private] (la pubblicazione ricrea le cartelle che mancano);
     * se l'area privata e' cambiata, `Backup/<dispositivo>/AAAA/MM/` di adesso ([nasNowMs], ora del NAS; senza, `Backup/<dispositivo>/`).
     */
    fun bringBackFolder(origin: ShareFolder, private: String, backupFolder: String, nasNowMs: Long?): ShareFolder {
      if (origin.share.equals(private, ignoreCase = true)) return origin
      val base = SharePath.parse(backupFolder)
      val month = nasNowMs?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC) }
      val path = if (month == null) base else base.child("%04d".format(month.year)).child("%02d".format(month.monthValue))
      return ShareFolder(private, path)
    }
  }
}
