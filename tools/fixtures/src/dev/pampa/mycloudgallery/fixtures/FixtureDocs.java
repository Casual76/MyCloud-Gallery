package dev.pampa.mycloudgallery.fixtures;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Le tabelle dei valori attesi di docs/fase3-campo.md, generate dal catalogo: il documento e i test dicono sempre le stesse
 * cose (un test confronta questo testo con quello del documento, tra i marcatori {@link #BEGIN} e {@link #END}).
 */
public final class FixtureDocs {

  private FixtureDocs() {}

  public static final String BEGIN = "<!-- FIXTURES:BEGIN (generato da tools/fixtures: non modificare a mano) -->";
  public static final String END = "<!-- FIXTURES:END -->";

  /** Il testo tra i marcatori, compresi i marcatori. */
  public static String markdown() {
    StringBuilder sb = new StringBuilder();
    sb.append(BEGIN).append("\n\n");
    Map<String, List<Fixture>> bySection = new LinkedHashMap<>();
    for (Fixture f : FixtureCatalog.all()) bySection.computeIfAbsent(f.section, k -> new java.util.ArrayList<>()).add(f);
    for (Map.Entry<String, List<Fixture>> section : bySection.entrySet()) {
      sb.append("### ").append(section.getKey()).append("\n\n");
      sb.append("| File | Cartella simulata nei test | Cosa è | Atteso sul telefono | Sul telefono |\n");
      sb.append("|---|---|---|---|---|\n");
      for (Fixture f : section.getValue()) {
        sb.append("| `").append(f.name).append("` | `").append(f.folder).append("` | ").append(cell(f.description)).append(" | ")
            .append(cell(deviceHuman(f))).append(" | ").append(f.onDevice ? "sì" : "solo test").append(" |\n");
      }
      sb.append("\n");
    }
    sb.append("### Gruppi: raffiche e coppie RAW+JPEG (D20)\n\n");
    sb.append("| Insieme | File | Gruppi attesi |\n|---|---|---|\n");
    for (FixtureCatalog.GroupSet set : FixtureCatalog.groupSets()) {
      StringBuilder names = new StringBuilder();
      for (int i = 0; i < set.members.size(); i++) {
        if (i > 0) names.append(", ");
        names.append(i).append(" `").append(set.members.get(i).name).append("`");
      }
      sb.append("| ").append(cell(set.title)).append(" | ").append(names).append(" | ").append(groupsHuman(set)).append(" |\n");
    }
    sb.append("\n").append(END);
    return sb.toString();
  }

  private static String groupsHuman(FixtureCatalog.GroupSet set) {
    if (set.expectedGroups.isEmpty()) return "nessuno";
    StringBuilder sb = new StringBuilder();
    for (String g : set.expectedGroups) {
      if (sb.length() > 0) sb.append("; ");
      String kind = g.substring(0, g.indexOf(':'));
      String members = g.substring(g.indexOf(':') + 1, g.indexOf('@'));
      String cover = g.substring(g.indexOf('@') + 1);
      sb.append(kind.equals("BURST") ? "raffica" : "coppia RAW+JPEG").append(" dei file ").append(members).append(", copertina ").append(cover);
    }
    return sb.toString();
  }

  /**
   * L'atteso sul telefono: nella cartella {@code Pictures/MCG-prova/} la sorgente che dipende solo dalla cartella
   * (Fotocamera, Quick Share) non si vede, e vale "Altro".
   */
  public static String deviceHuman(Fixture f) {
    Fixture.Expect e = f.expect;
    if (!"read".equals(e.result) || e.source == null) return e.human();
    String name = f.name.toLowerCase(java.util.Locale.ROOT);
    boolean byName = name.startsWith("screenshot") || name.startsWith("screen_recording") || name.matches("^(img|vid)-\\d{8}-wa\\d+.*");
    String original = e.source;
    String onDevice = (original.equals("CAMERA") || original.equals("QUICK_SHARE") || !byName && !original.equals("OTHER")) ? "OTHER" : original;
    if (onDevice.equals(original)) return e.human();
    String saved = e.source;
    e.source = onDevice;
    String text = e.human();
    e.source = saved;
    return text;
  }

  private static String cell(String text) {
    return text.replace("|", "\\|").replace("\n", " ");
  }
}
