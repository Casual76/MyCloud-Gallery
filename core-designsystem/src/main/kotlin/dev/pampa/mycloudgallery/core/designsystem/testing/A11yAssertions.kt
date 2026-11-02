package dev.pampa.mycloudgallery.core.designsystem.testing

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density

// Fase 10, P8 (D28, §5.3 del progetto): le regole di TalkBack che i test sui nodi pretendono, scritte UNA volta.
// Ogni modulo ha un `*AccessibilityTest` che monta le sue schermate (gli stati del catalogo) e chiede a questa utilita'
// di percorrere l'albero semantico: un nome e un ruolo per ogni elemento attivo, bersagli da 48 dp misurati sui limiti
// del tocco, nessuna descrizione con un id, un percorso o un nome di file, intestazioni, raccolte, azioni
// personalizzate, annunci, ordine di lettura, testo non tagliato.
//
// Sta in `src/main` come gli altri package `testing` (solo `androidx.compose.ui`, nessuna libreria di prova): R8 la
// toglie dall'APK e `tools/check.ps1` (passo 8) lo controlla. L'ATF (che invece e' una libreria di prova) si chiama dai
// test di ogni modulo con `A11yAtf` (src/test).

/** Una regola violata: quale, su quale nodo e perche'. Il nodo e' descritto senza dati personali (le prove sono sintetiche). */
data class A11yFinding(val rule: String, val node: String, val detail: String) {
  override fun toString(): String = "[$rule] $node: $detail"
}

/** Le regole che si possono spegnere per una schermata, ognuna con il motivo scritto nel test (`exempt`). */
enum class A11yRule {
  /** Un elemento attivo senza nome (ne' descrizione ne' testo). */
  NAME,

  /** Un elemento attivo senza ruolo. */
  ROLE,

  /** Un bersaglio di tocco sotto i 48 dp (misurato sui limiti del tocco). */
  TARGET,

  /** Una descrizione che contiene un id, un percorso o un nome di file. */
  LEAK,

  /** Un testo tagliato dal contenitore (non un'ellissi voluta: quella lascia il testo intero a TalkBack). */
  CLIPPED,

  /** Due bersagli di tocco che si sovrappongono (senza che uno contenga l'altro). */
  OVERLAP,
}

/** Come si leggono le regole: la densita' (per i dp) e cosa e' ammesso per questa schermata. */
class A11yConfig(
  val density: Density,
  /** I nodi che una regola non deve toccare, con il motivo nel codice di chi li esenta. */
  val exempt: (A11yRule, SemanticsNode) -> Boolean = { _, _ -> false },
  /** Le regole che valgono: di norma tutte tranne OVERLAP, che e' un avviso da guardare. */
  val rules: Set<A11yRule> = setOf(A11yRule.NAME, A11yRule.ROLE, A11yRule.TARGET, A11yRule.LEAK, A11yRule.CLIPPED),
  /** Il lato minimo del bersaglio. */
  val minTarget: Float = 48f,
  /**
   * Le **righe di Fluid** (`FluidListRow`: `combinedClickable` a tutta larghezza, con il nome fuso dai figli) non dichiarano un ruolo: TalkBack
   * dice "tocca due volte per attivare" invece di "pulsante". La correzione e' nell'engine (`fluidRowPressable`, `role = Button`) e va fatta con
   * Fluid Engine 3.0 (nota per la fase grafica, `docs/decisioni.md` Fase 10 P8). Finche' c'e', la regola ROLE lascia passare un elemento
   * **a tutta larghezza** (almeno [rowWidthShare] della radice) **con un nome**: e' una riga. Ogni altro elemento attivo il ruolo lo deve avere.
   */
  val allowRolelessRows: Boolean = true,
  val rowWidthShare: Float = 0.8f,
  /**
   * Il **campo di testo del Fluid** (`FluidTextField`, nell'engine) ha due limiti che non si correggono da qui (`engine/` e' un sotto-modulo e
   * `check.ps1` lo vuole pulito): (1) il tasto che svuota il campo e' un cerchio da 28 dp disegnato, **senza nome ne' ruolo** (il tocco e' allargato
   * a 48 dp da Compose); (2) l'etichetta e' un testo sopra il campo, **non fuso** con lui: un campo ancora vuoto e senza segnaposto non ha un nome
   * proprio (TalkBack legge l'etichetta, poi "campo di modifica"). Con questo valore la regola NAME/ROLE li lascia passare **solo** in quella forma
   * (un elemento toccabile e piccolo dentro o accanto a un campo; un campo con la sua etichetta subito sopra): ogni altro elemento senza nome resta una
   * violazione. Da correggere con Fluid Engine 3.0 (`contentDescription` "Cancella" e `Role.Button` sul tasto, etichetta fusa con il campo).
   */
  val allowEngineTextFieldLimits: Boolean = true,
  /**
   * `FluidButton` (engine) ha un'**altezza fissa** (50, 42 o 32 dp): con il testo a 2,0x un'etichetta lunga va a capo e le righe in piu' escono dal
   * bottone (TalkBack legge l'etichetta intera, e' lo schermo che la taglia). La correzione e' nell'engine (altezza minima al posto di quella
   * fissa) e va fatta con Fluid Engine 3.0. Con questo valore la regola CLIPPED lascia passare **solo** il taglio in **altezza** di un testo dentro un
   * elemento con ruolo di pulsante alto esattamente quanto un `FluidButton`: un pulsante di un'altra altezza, o un taglio in larghezza, restano violazioni.
   */
  val allowEngineButtonLabels: Boolean = true,
)

object A11yAssertions {

  /** Tutti i nodi dell'albero (fuso: un nodo che fonde i figli non ha figli), la radice compresa, in ordine di lettura dell'albero. */
  fun nodes(root: SemanticsNode): List<SemanticsNode> {
    val out = ArrayList<SemanticsNode>()
    fun walk(node: SemanticsNode) {
      out += node
      node.children.forEach(::walk)
    }
    walk(root)
    return out
  }

  /** Un elemento "attivo": ha un'azione che l'utente puo' fare (tocco, pressione lunga, valore, testo) o uno stato che cambia. */
  fun isActionable(node: SemanticsNode): Boolean {
    val c = node.config
    if (c.getOrNull(SemanticsProperties.Disabled) != null && c.getOrNull(SemanticsActions.OnClick) == null) return false
    return SemanticsActions.OnClick in c ||
      SemanticsActions.OnLongClick in c ||
      SemanticsActions.SetProgress in c ||
      SemanticsActions.SetText in c ||
      SemanticsProperties.ToggleableState in c ||
      (SemanticsProperties.Selected in c && c.getOrNull(SemanticsProperties.Role) != null)
  }

  /** Tutto cio' che un lettore di schermo pronuncia per il nodo: descrizione, testo, testo in modifica, stato. */
  fun spoken(node: SemanticsNode): List<String> {
    val c = node.config
    val out = ArrayList<String>()
    c.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
    c.getOrNull(SemanticsProperties.Text)?.let { list -> out += list.map { it.text } }
    c.getOrNull(SemanticsProperties.EditableText)?.let { out += it.text }
    c.getOrNull(SemanticsProperties.StateDescription)?.let { out += it }
    return out.filter { it.isNotBlank() }
  }

  /** Il nome del nodo (descrizione, o testo): vuoto se un lettore di schermo non ha niente da dire. */
  fun name(node: SemanticsNode): String {
    val c = node.config
    val description = c.getOrNull(SemanticsProperties.ContentDescription).orEmpty().filter { it.isNotBlank() }
    if (description.isNotEmpty()) return description.joinToString(", ")
    val text = c.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }.filter { it.isNotBlank() }
    if (text.isNotEmpty()) return text.joinToString(" ")
    return c.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
  }

  /** Un nodo in poche parole per i messaggi (id, tag, nome e limiti): niente contenuto del media. */
  fun describeNode(node: SemanticsNode): String {
    val b = node.boundsInRoot
    val tag = node.config.getOrNull(SemanticsProperties.TestTag)?.let { " tag=$it" }.orEmpty()
    val name = name(node).take(NameChars).replace('\n', ' ')
    val props = node.config.joinToString(",") { it.key.name }
    return "#${node.id}$tag \"$name\" [${b.left.toInt()},${b.top.toInt()} ${b.width.toInt()}x${b.height.toInt()}] props=$props"
  }

  /**
   * L'albero **non fuso** a cui appartiene [node] (`SemanticsOwner.unmergedRootSemanticsNode`), o null se non si raggiunge. Si passa dalla riflessione
   * perche' `RootForTest` non e' un'API stabile: se non c'e' (o cambia nome) le regole che ne hanno bisogno ripiegano sull'albero fuso, piu' povero
   * ma mai sbagliato.
   */
  private fun unmergedRootOf(node: SemanticsNode): SemanticsNode? = try {
    val owner = node.javaClass.getMethod("getRoot").invoke(node)
    val semantics = owner?.javaClass?.getMethod("getSemanticsOwner")?.invoke(owner)
    semantics?.javaClass?.getMethod("getUnmergedRootSemanticsNode")?.invoke(semantics) as? SemanticsNode
  } catch (_: Exception) {
    null
  }

  /**
   * Il nome di un elemento attivo come lo legge TalkBack: il suo, o -- se non ne ha -- quello dei suoi figli (un contenitore toccabile senza
   * testo proprio dice il testo dei discendenti). Si guarda l'albero non fuso, dove i figli ci sono ancora.
   */
  private fun nameOrDescendants(node: SemanticsNode, unmergedById: Map<Int, SemanticsNode>): String {
    val own = name(node)
    if (own.isNotBlank()) return own
    val twin = unmergedById[node.id] ?: return ""
    fun below(n: SemanticsNode): List<String> = n.children.flatMap { c -> listOf(name(c)).filter { it.isNotBlank() } + below(c) }
    return below(twin).joinToString(" ")
  }

  /** Le violazioni delle regole di [config] su tutto l'albero. Vuota = pulito. */
  fun check(root: SemanticsNode, config: A11yConfig): List<A11yFinding> {
    val found = ArrayList<A11yFinding>()
    val all = nodes(root)
    val unmergedRoot = unmergedRootOf(root) ?: root
    val unmergedById: Map<Int, SemanticsNode> by lazy { nodes(unmergedRoot).associateBy { it.id } }
    for (node in all) {
      if (node.config.getOrNull(SemanticsProperties.InvisibleToUser) != null) continue
      // Un nodo senza dimensioni non si vede e TalkBack non ci arriva (una riga non ancora disposta, un contenuto chiuso): non c'e' niente da leggere.
      if (node.boundsInRoot.width <= 0f || node.boundsInRoot.height <= 0f) continue
      val actionable = isActionable(node)
      if (actionable) {
        val engineField = config.allowEngineTextFieldLimits &&
          (isEngineClearButton(node, unmergedById, config.density) || hasNeighbourLabel(node, unmergedById, config.density))
        if (A11yRule.NAME in config.rules && nameOrDescendants(node, unmergedById).isBlank() && !engineField && !config.exempt(A11yRule.NAME, node)) {
          found += A11yFinding("NAME", describeNode(node), "elemento attivo senza nome")
        }
        val fullWidthRow = config.allowRolelessRows && nameOrDescendants(node, unmergedById).isNotBlank() &&
          node.boundsInRoot.width >= root.boundsInRoot.width * config.rowWidthShare
        // Un campo di testo ha il suo ruolo implicito (per TalkBack e' un "campo di modifica"), anche quando e' disattivato: ha `EditableText`.
        if (A11yRule.ROLE in config.rules && node.config.getOrNull(SemanticsProperties.Role) == null &&
          SemanticsActions.SetText !in node.config && SemanticsProperties.EditableText !in node.config &&
          SemanticsActions.SetProgress !in node.config &&
          !fullWidthRow && !engineField && !config.exempt(A11yRule.ROLE, node)
        ) {
          found += A11yFinding("ROLE", describeNode(node), "elemento attivo senza ruolo")
        }
        if (A11yRule.TARGET in config.rules && !config.exempt(A11yRule.TARGET, node)) {
          val t = node.touchBoundsInRoot
          val min = config.minTarget * config.density.density
          // I limiti del tocco sono **tagliati** dal contenitore che scorre: una cella mezza uscita dalla finestra ha un pezzo visibile piu' piccolo
          // del bersaglio vero (che TalkBack porta in vista da solo). Il bersaglio e' il piu' grande tra i limiti del tocco e la misura del nodo.
          val width = maxOf(t.width, node.size.width.toFloat())
          val height = maxOf(t.height, node.size.height.toFloat())
          // Mezzo pixel di tolleranza per gli arrotondamenti del layout.
          if (minOf(width, height) + HALF_PIXEL < min) {
            found += A11yFinding(
              "TARGET",
              describeNode(node),
              "bersaglio ${(width / config.density.density).toInt()}x${(height / config.density.density).toInt()} dp, sotto i ${config.minTarget.toInt()}",
            )
          }
        }
      }
      if (A11yRule.LEAK in config.rules) {
        for (said in spoken(node)) {
          leakIn(said)?.let { why ->
            if (!config.exempt(A11yRule.LEAK, node)) found += A11yFinding("LEAK", describeNode(node), "$why in \"${said.take(NameChars)}\"")
          }
        }
      }
    }
    if (A11yRule.CLIPPED in config.rules) {
      // Un nodo fuso porta solo il primo testo dei figli: il taglio si cerca su **ogni** `Text`, cioe' sull'albero non fuso.
      for (node in nodes(unmergedRoot)) {
        if (node.config.getOrNull(SemanticsProperties.InvisibleToUser) != null || config.exempt(A11yRule.CLIPPED, node)) continue
        if (node.boundsInRoot.width <= 0f || node.boundsInRoot.height <= 0f) continue
        clippedText(node)?.let { why ->
          val engineButton = config.allowEngineButtonLabels && why.contains("altezza") && insideEngineButton(node, config.density)
          if (!engineButton) found += A11yFinding("CLIPPED", describeNode(node), why)
        }
      }
    }
    if (A11yRule.OVERLAP in config.rules) {
      val targets = all.filter(::isActionable).filter { it.config.getOrNull(SemanticsProperties.InvisibleToUser) == null }
      for (i in targets.indices) for (j in i + 1 until targets.size) {
        val a = targets[i]
        val b = targets[j]
        if (config.exempt(A11yRule.OVERLAP, a) || config.exempt(A11yRule.OVERLAP, b)) continue
        val overlap = a.touchBoundsInRoot.intersect(b.touchBoundsInRoot)
        if (overlap.width > OVERLAP_PX && overlap.height > OVERLAP_PX && !contains(a.touchBoundsInRoot, b.touchBoundsInRoot) &&
          !contains(b.touchBoundsInRoot, a.touchBoundsInRoot)
        ) {
          found += A11yFinding("OVERLAP", describeNode(a), "si sovrappone a ${describeNode(b)}")
        }
      }
    }
    return found
  }

  /** Come [check], ma fa fallire il test con l'elenco leggibile delle violazioni. */
  fun assertClean(root: SemanticsNode, config: A11yConfig) {
    val found = check(root, config)
    if (found.isNotEmpty()) throw AssertionError("${found.size} violazioni di accessibilita':\n" + found.joinToString("\n") { "  $it" })
  }

  /**
   * Il perche' una descrizione non va letta ad alta voce, o null se va bene: un id (UUID, esadecimale lungo, `MediaId`), un
   * percorso (`/`, `\`, `content://`) o un nome di file (`IMG_0001.jpg`). Le espressioni sono corte e larghe: meglio un falso
   * allarme da esentare per iscritto che un nome di file letto a una persona.
   */
  fun leakIn(text: String): String? {
    for ((why, re) in LEAK_PATTERNS) if (re.containsMatchIn(text)) return why
    return null
  }

  /** Le intestazioni dell'albero (`heading()`), nell'ordine in cui compaiono. */
  fun headings(root: SemanticsNode): List<SemanticsNode> = nodes(root).filter { SemanticsProperties.Heading in it.config }

  /** I nomi delle intestazioni. */
  fun headingNames(root: SemanticsNode): List<String> = headings(root).map(::name)

  /** I nodi che dicono di essere una raccolta (`CollectionInfo`) con le righe e le colonne. */
  fun collections(root: SemanticsNode): List<Pair<SemanticsNode, Pair<Int, Int>>> = nodes(root).mapNotNull { n ->
    n.config.getOrNull(SemanticsProperties.CollectionInfo)?.let { n to (it.rowCount to it.columnCount) }
  }

  /** Le etichette delle azioni personalizzate di un nodo (per esempio "Apri", "Preferito"). */
  fun customActionLabels(node: SemanticsNode): List<String> =
    node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

  /** Vero se il nodo e' un annuncio educato (`liveRegion` Polite) o assertivo. */
  fun liveRegion(node: SemanticsNode): String? =
    node.config.getOrNull(SemanticsProperties.LiveRegion)?.let { it.toString() }

  /**
   * Come TalkBack percorre l'albero: dall'alto in basso e da sinistra a destra dentro lo stesso gruppo, salvo un
   * `traversalIndex` esplicito. Per il test dell'ordine ("le barre di vetro prima del contenuto"): i nodi che hanno un
   * nome, nell'ordine in cui il lettore li incontra.
   */
  fun readingOrder(root: SemanticsNode): List<SemanticsNode> {
    val out = ArrayList<SemanticsNode>()
    fun walk(node: SemanticsNode) {
      if (name(node).isNotBlank() || isActionable(node)) out += node
      node.children
        .sortedWith(
          compareBy<SemanticsNode>({ it.config.getOrNull(SemanticsProperties.TraversalIndex) ?: 0f }, { it.boundsInRoot.top.toInt() / ROW_SLOP }, { it.boundsInRoot.left }),
        )
        .forEach(::walk)
    }
    walk(root)
    return out
  }

  /** Il ruolo del nodo in una parola, per i messaggi e per i test ("Button", "Checkbox", ...). */
  fun roleName(node: SemanticsNode): String? = node.config.getOrNull(SemanticsProperties.Role)?.let { roleLabel(it) }

  private fun roleLabel(role: Role): String = when (role) {
    Role.Button -> "Button"
    Role.Checkbox -> "Checkbox"
    Role.Switch -> "Switch"
    Role.RadioButton -> "RadioButton"
    Role.Tab -> "Tab"
    Role.Image -> "Image"
    Role.DropdownList -> "DropdownList"
    else -> role.toString()
  }

  /**
   * Il testo del nodo tagliato dal contenitore (troppo alto o troppo largo per lo spazio che ha avuto, senza un'ellissi voluta), o null.
   * Si confronta il **paragrafo** (la sua altezza e la fine delle righe) con la **dimensione reale del nodo**: `didOverflowHeight` e
   * `didOverflowWidth` di Compose dicono vero anche per testi che stanno nei loro limiti (arrotondamenti, vincoli), e non sono affidabili.
   */
  private fun clippedText(node: SemanticsNode): String? {
    val getter = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
    val results = ArrayList<TextLayoutResult>()
    getter(results)
    val layout = results.firstOrNull() ?: return null
    if (layout.lineCount == 0) return null
    val ellipsized = layout.hasVisualOverflowEllipsis()
    // Altezza: si tollera lo scarto dei margini di riga (la riga puo' misurare qualche pixel piu' del nodo): conta il taglio vero, quello che toglie
    // almeno un quarto del testo.
    val textHeight = layout.getLineBottom(layout.lineCount - 1) - layout.getLineTop(0)
    if (!ellipsized && textHeight - node.size.height > maxOf(HEIGHT_SLACK_PX, node.size.height * HEIGHT_SLACK_SHARE)) {
      return "il testo supera l'altezza del contenitore"
    }
    // Un campo di testo a una riga **scorre** (il testo non e' tagliato: si vede scorrendo e TalkBack lo legge intero): niente taglio in larghezza.
    if (SemanticsProperties.EditableText in node.config) return null
    // Larghezza: la *larghezza della riga* (un testo centrato non parte dal bordo), contro quella del nodo.
    for (line in 0 until layout.lineCount) {
      val lineWidth = layout.getLineRight(line) - layout.getLineLeft(line)
      if (lineWidth - node.size.width > maxOf(CLIP_SLACK_PX, node.size.width * WIDTH_SLACK_SHARE) && !layout.isLineEllipsized(line)) {
        return "il testo supera la larghezza del contenitore"
      }
    }
    return null
  }

  /**
   * Il tasto che svuota un `FluidTextField`: un elemento toccabile **piccolo** (al piu' [ENGINE_CLEAR_MAX_DP] dp) che sta accanto a un campo di
   * testo (entro due livelli, in un contenitore con un solo campo). Niente altro viene lasciato passare da qui.
   */
  private fun isEngineClearButton(node: SemanticsNode, unmergedById: Map<Int, SemanticsNode>, density: Density): Boolean {
    if (SemanticsActions.OnClick !in node.config || SemanticsActions.SetText in node.config || SemanticsProperties.EditableText in node.config) return false
    val maxSide = ENGINE_CLEAR_MAX_DP * density.density
    if (node.size.width > maxSide || node.size.height > maxSide) return false
    var up = unmergedById[node.id]?.parent
    repeat(NEIGHBOUR_LEVELS) {
      val parent = up ?: return false
      if (nodes(parent).count { SemanticsActions.SetText in it.config } == 1) return true
      up = parent.parent
    }
    return false
  }

  /** Il testo sta dentro un pulsante con l'altezza fissa di un `FluidButton` (50, 42 o 32 dp, a meno di un dp), entro tre livelli. */
  private fun insideEngineButton(node: SemanticsNode, density: Density): Boolean {
    var up = node.parent
    repeat(BUTTON_LEVELS) {
      val parent = up ?: return false
      if (parent.config.getOrNull(SemanticsProperties.Role) == Role.Button && SemanticsActions.OnClick in parent.config) {
        val dp = parent.size.height / density.density
        return ENGINE_BUTTON_HEIGHTS_DP.any { kotlin.math.abs(it - dp) <= 1f }
      }
      up = parent.parent
    }
    return false
  }

  /**
   * Un campo di testo senza nome proprio (vuoto e senza segnaposto) con la sua **etichetta subito sopra**: un testo non toccabile, tra i fratelli
   * del campo, che finisce non oltre [LABEL_GAP_DP] dp sopra di lui (l'etichetta puo' stare in una riga piu' alta, con un tasto "Mostra") e lo copre in larghezza. E' il disegno di `FluidTextField` (la colonna
   * "etichetta, campo, testo di aiuto" non ha un nodo semantico suo, quindi l'etichetta e il campo sono fratelli).
   */
  private fun hasNeighbourLabel(node: SemanticsNode, unmergedById: Map<Int, SemanticsNode>, density: Density): Boolean {
    if (SemanticsProperties.EditableText !in node.config && SemanticsActions.SetText !in node.config) return false
    val siblings = unmergedById[node.id]?.parent?.children ?: return false
    val field = node.boundsInRoot
    val maxGap = LABEL_GAP_DP * density.density
    return siblings.any { s ->
      val b = s.boundsInRoot
      s.id != node.id && SemanticsProperties.Text in s.config && !isActionable(s) && name(s).isNotBlank() &&
        b.bottom <= field.top + HALF_PIXEL && field.top - b.bottom <= maxGap && b.left < field.right && b.right > field.left
    }
  }

  /** Un'ellissi (`maxLines` + `TextOverflow.Ellipsis`) lascia il testo intero a TalkBack: non e' un taglio. */
  private fun TextLayoutResult.hasVisualOverflowEllipsis(): Boolean =
    layoutInput.overflow == TextOverflow.Ellipsis && lineCount > 0 && isLineEllipsized(lineCount - 1)

  private fun contains(outer: Rect, inner: Rect): Boolean =
    outer.left <= inner.left && outer.top <= inner.top && outer.right >= inner.right && outer.bottom >= inner.bottom

  private const val HALF_PIXEL = 0.5f
  private const val CLIP_SLACK_PX = 2f
  private const val HEIGHT_SLACK_PX = 8f
  private const val HEIGHT_SLACK_SHARE = 0.25f
  private const val WIDTH_SLACK_SHARE = 0.05f
  private const val OVERLAP_PX = 2f
  private const val ROW_SLOP = 1
  private const val NameChars = 60
  private const val ENGINE_CLEAR_MAX_DP = 32f
  private const val NEIGHBOUR_LEVELS = 4
  private const val LABEL_GAP_DP = 40f
  private const val BUTTON_LEVELS = 3
  private val ENGINE_BUTTON_HEIGHTS_DP = listOf(50f, 42f, 32f)

  private val LEAK_PATTERNS: List<Pair<String, Regex>> = listOf(
    "un UUID" to Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
    "un identificativo esadecimale lungo" to Regex("(?<![0-9A-Za-z])[0-9a-fA-F]{16,}(?![0-9A-Za-z])"),
    "un indirizzo di contenuto" to Regex("(content|file|smb|https?)://", RegexOption.IGNORE_CASE),
    "un percorso" to Regex("(^|\\s)(/[A-Za-z0-9._-]+){2,}|[A-Za-z]:\\\\|\\\\\\\\"),
    "un nome di file" to Regex("(?<![0-9A-Za-z])[A-Za-z0-9_\\-()]{2,}\\.(jpe?g|png|heic|heif|webp|gif|mp4|mov|m4v|avi|mkv|dng|raw|3gp)(?![0-9A-Za-z])", RegexOption.IGNORE_CASE),
    "un MediaId" to Regex("MediaId\\(|media-id|mediaId", RegexOption.IGNORE_CASE),
  )
}
