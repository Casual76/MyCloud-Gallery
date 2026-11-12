// Prova sintetica (I5, 12/11/2026) della regola degli ingressi del launcher di gradle/verify-release-manifest.gradle (`verifyLauncherAliases`), sui casi
// buoni e cattivi: esegue IL TESTO della funzione preso dal file (con `attr`), non una copia, su piccoli manifest fusi di prova. La corsa vera sul
// manifest di release e' `gradlew :app:verifyReleaseManifest`; questa prova che la regola cade quando deve.
//
// Si lancia con il Groovy della distribuzione di Gradle (nessuna dipendenza nuova), dalla radice del repository:
//   java -cp "<gradle-8.14.3>\lib\groovy-3.0.24.jar;<gradle-8.14.3>\lib\groovy-xml-3.0.24.jar" groovy.ui.GroovyMain tools\release\test-launcher-gradle.groovy
// Codice di uscita 0 solo se ogni caso va come deve. Solo ASCII.

def gradle = new File('gradle/verify-release-manifest.gradle').getText('UTF-8')

String functionText(String text, String signature) {
  int start = text.indexOf(signature)
  assert start >= 0: "non trovo ${signature}"
  int open = text.indexOf('{', start)
  int depth = 0
  for (int i = open; i < text.length(); i++) {
    char c = text.charAt(i)
    if (c == '{' as char) depth++
    if (c == '}' as char) {
      depth--
      if (depth == 0) return text.substring(start, i + 1)
    }
  }
  throw new IllegalStateException("funzione non chiusa: ${signature}")
}

def source = "class LauncherRule {\n" +
  functionText(gradle, 'static String attr(Object node, String name)') + "\n" +
  functionText(gradle, 'static List<String> verifyLauncherAliases(Object application, Map<String, String> policy)') + "\n}\n"
def rule = new GroovyClassLoader().parseClass(source)

// La politica dal file, come la legge la task.
def block = (gradle =~ /(?s)launcherAliasPolicy\s*:\s*\[(.*?)\n\s*\],/)
assert block.find(): 'non trovo launcherAliasPolicy'
def policy = [:]
(block.group(1) =~ /'([^']+)'\s*:\s*'([^']*)'/).each { all, key, value -> policy[key] = value }
assert policy.keySet() == ['aliases', 'target', 'enabledByDefault'] as Set

String alias(String name, Map o = [:]) {
  String enabled = o.containsKey('enabled') ? o.enabled : (name == 'LauncherAuto' ? 'true' : 'false')
  String target = o.target ?: 'dev.pampa.mycloudgallery.MainActivity'
  String exported = o.containsKey('exported') ? o.exported : 'true'
  String permission = o.permission ? " android:permission=\"${o.permission}\"" : ''
  String extra = o.extra ?: ''
  return "<activity-alias android:name=\"dev.pampa.mycloudgallery.${name}\" android:enabled=\"${enabled}\" android:exported=\"${exported}\"" +
    " android:targetActivity=\"${target}\"${permission}><intent-filter><action android:name=\"android.intent.action.MAIN\"/>" +
    "<category android:name=\"android.intent.category.LAUNCHER\"/>${extra}</intent-filter></activity-alias>"
}

String manifest(Map o = [:]) {
  String main = o.mainLauncher ?
    '<activity android:name="dev.pampa.mycloudgallery.MainActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>' :
    '<activity android:name="dev.pampa.mycloudgallery.MainActivity" android:exported="true"/>'
  def aliases = ['LauncherAuto', 'LauncherLight', 'LauncherDark'].findAll { !(it in (o.missing ?: [])) }.collect { alias(it, (o.changes ?: [:])[it] ?: [:]) }
  if (o.extraAlias) aliases << alias(o.extraAlias)
  return '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.pampa.mycloudgallery"><application>' +
    main + aliases.join('') + '</application></manifest>'
}

def cases = [
  ['i tre alias, solo l automatica abilitata: nessun problema', manifest(), null],
  ['un quarto alias del launcher', manifest(extraAlias: 'LauncherRainbow'), 'fuori elenco'],
  ['un alias con un filtro in piu (VIEW)', manifest(changes: [LauncherAuto: [extra: '<action android:name="android.intent.action.VIEW"/>']]), 'la sola azione MAIN'],
  ['un alias con una categoria in piu', manifest(changes: [LauncherLight: [extra: '<category android:name="android.intent.category.DEFAULT"/>']]), 'la sola categoria LAUNCHER'],
  ['un alias con un data', manifest(changes: [LauncherDark: [extra: '<data android:scheme="https"/>']]), 'non deve avere altro'],
  ['un alias verso un altra attivita', manifest(changes: [LauncherDark: [target: 'dev.pampa.mycloudgallery.Altra']]), 'targetActivity deve essere'],
  ['due alias abilitati di serie', manifest(changes: [LauncherLight: [enabled: 'true']]), 'un solo ingresso del launcher alla volta'],
  ['nessun alias abilitato di serie', manifest(changes: [LauncherAuto: [enabled: 'false']]), 'deve essere abilitato'],
  ['la MainActivity con il suo filtro LAUNCHER', manifest(mainLauncher: true), 'MainActivity e\' un ingresso del launcher fuori elenco'],
  ['un alias con un permesso diverso', manifest(changes: [LauncherAuto: [permission: 'android.permission.DUMP']]), 'android:permission deve essere'],
  ['un alias non esportato', manifest(changes: [LauncherLight: [exported: 'false']]), 'android:exported'],
  ['un alias che manca', manifest(missing: ['LauncherDark']), 'manca l\'ingresso del launcher'],
]

int bad = 0
cases.each { name, xml, expected ->
  def root = new XmlSlurper(false, false).parseText(xml)
  List<String> problems = rule.verifyLauncherAliases(root.application[0], policy)
  boolean ok = expected == null ? problems.isEmpty() : problems.any { it.contains(expected) }
  println((ok ? '  ok    ' : '  MALE  ') + name + (problems ? '  -> ' + problems.join(' | ') : ''))
  if (!ok) bad++
}
println(bad == 0 ? "OK: ${cases.size()} casi, tutti come dovevano." : "FAIL: ${bad} casi andati male")
System.exit(bad == 0 ? 0 : 1)
