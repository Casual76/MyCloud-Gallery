using System;
using System.IO;
using System.Collections.Generic;
using System.Text.RegularExpressions;
using System.Diagnostics;

// HK: identita' dell'ISTRUZIONE, mai il nome scelto per una classe fusa.
public static class R8Provenance {
  class Frame { public string Origin, Signature; }
  class Entry { public int Lo, Hi; public string Origin, Range, Signature, OriginalSignature; public bool HasResidualSignature; public List<Frame> Frames=new List<Frame>(); }
  class Method {
    public string Key, Signature;
    public SortedList<int,int> Positions = new SortedList<int,int>();
    public bool KeepBody;
    public List<string> Instructions = new List<string>();
  }
  class Location {public string Key; public Entry Entry;}
  static Dictionary<string,List<Location>> runtimeLocations=new Dictionary<string,List<Location>>();
  public static List<string> PolicyEvidence=new List<string>();
  public static List<string> RuntimeProofs=new List<string>();
  static Dictionary<string,string> residualSignatures=new Dictionary<string,string>();
  static Dictionary<string,List<Entry>> map;
  static Dictionary<string,Method> instructions = new Dictionary<string,Method>();
  static Dictionary<string,Method> methods = new Dictionary<string,Method>();
  static Dictionary<string,string> aliases = new Dictionary<string,string>();
  static Dictionary<string,string> contentIds = new Dictionary<string,string>();
  static HashSet<string> prepared = new HashSet<string>();
  public static string MappingPath;
  static string Address(string line) {int colon=line.IndexOf(':'); return colon<0 ? line : line.Substring(0,colon).Trim();}
  static Regex cls = new Regex(@"^\s*Class descriptor\s*:\s*'([^']+)'", RegexOptions.Compiled);
  static Regex name = new Regex(@"^\s*name\s*:\s*'([^']+)'", RegexOptions.Compiled);
  static Regex type = new Regex(@"^\s*type\s*:\s*'([^']+)'", RegexOptions.Compiled);
  static Regex insn = new Regex(@"\|([0-9a-fA-F]+):\s+(\S+)\s*(.*)$", RegexOptions.Compiled);
  static Regex pos = new Regex(@"^\s*0x([0-9a-fA-F]+) line=(\d+)", RegexOptions.Compiled);
  static Regex member = new Regex(@"^\s+(?:(\d+):(\d+):)?\S+ ([^ (]+)\([^)]*\)(?::\d+(?::\d+)?)? -> (\S+)$", RegexOptions.Compiled);
  static string Descriptor(string s) { return "L" + s.Replace('.', '/') + ";"; }
  static string TypeDescriptor(string s) {
    if(s.EndsWith("[]")) return "["+TypeDescriptor(s.Substring(0,s.Length-2));
    switch(s) {case "void":return "V";case "boolean":return "Z";case "byte":return "B";case "short":return "S";case "char":return "C";case "int":return "I";case "long":return "J";case "float":return "F";case "double":return "D";default:return Descriptor(s);}
  }
  static void LoadMap() {
    if (map != null) return;
    if (String.IsNullOrEmpty(MappingPath) || !File.Exists(MappingPath)) throw new InvalidOperationException("HK: manca mapping.txt");
    var result = new Dictionary<string,List<Entry>>();
    string owner = null, residual = null, lastKey=null; Entry last=null;
    foreach (string raw in File.ReadLines(MappingPath)) {
      if(raw.Contains("com.android.tools.r8.residualsignature") && last!=null) {
        var sm=Regex.Match(raw,"\"signature\":\"([^\"]+)\"");
        if(sm.Success && sm.Groups[1].Value.StartsWith("(")) residualSignatures[lastKey+"|"+last.OriginalSignature]=sm.Groups[1].Value;
      }
      if (raw.Length == 0 || raw[0] == '#') continue;
      if (raw[0] != ' ') {
        int a = raw.IndexOf(" -> ");
        last=null; lastKey=null;
        owner = a < 0 ? null : raw.Substring(0,a);
        residual = a < 0 ? null : Descriptor(raw.Substring(a+4).TrimEnd(':'));
        continue;
      }
      var m = member.Match(raw); if (!m.Success || owner == null) continue;
      string qualified = m.Groups[3].Value; int dot = qualified.LastIndexOf('.');
      string origin = Descriptor(dot < 0 ? owner : qualified.Substring(0,dot)) + "." + qualified.Substring(dot+1);
      string key = residual + "." + m.Groups[4].Value;
      List<Entry> list; if (!result.TryGetValue(key,out list)) result[key] = list = new List<Entry>();
      string range=m.Groups[1].Value + ":" + m.Groups[2].Value;
      string left=raw.Trim().Split(new string[]{" -> "},StringSplitOptions.None)[0];
      left=Regex.Replace(left,@"^\d+:\d+:","");
      string ret=left.Substring(0,left.IndexOf(' '));
      int paren=left.IndexOf('('), close=left.IndexOf(')');
      string sig="("; string args=left.Substring(paren+1,close-paren-1);
      if(args.Length>0) foreach(string arg in args.Split(',')) sig+=TypeDescriptor(arg);
      sig+=")"+TypeDescriptor(ret);
      if(last!=null && lastKey==key && last.Range==range && m.Groups[1].Success) {last.Signature=String.Intern(sig); last.OriginalSignature=last.Signature; last.Frames.Add(new Frame {Origin=String.Intern(origin),Signature=String.Intern(sig)}); continue;}
      last=new Entry { Lo = m.Groups[1].Success ? Int32.Parse(m.Groups[1].Value) : -1,
        Hi = m.Groups[2].Success ? Int32.Parse(m.Groups[2].Value) : -1, Origin = String.Intern(origin),
        Range = String.Intern(range), Signature=String.Intern(sig), OriginalSignature=String.Intern(sig) };
      last.Frames.Add(new Frame {Origin=last.Origin,Signature=last.Signature});
      list.Add(last); lastKey=key;
    }
    var inlineOrigins=new HashSet<string>();
    foreach(var pair in result) foreach(var e in pair.Value) {
      string residualSignature;
      if(residualSignatures.TryGetValue(pair.Key+"|"+e.OriginalSignature,out residualSignature)) {e.Signature=residualSignature; e.HasResidualSignature=true;}
      for(int i=0;i<e.Frames.Count-1;i++) if(!e.Frames[i].Origin.StartsWith("Ldev/")) inlineOrigins.Add(e.Frames[i].Origin);
    }
    foreach(var pair in result) foreach(var e in pair.Value) {
      var seen=new HashSet<string>();
      foreach(var f in e.Frames) if(inlineOrigins.Contains(f.Origin) && seen.Add(f.Origin)) {
        List<Location> locations;
        if(!runtimeLocations.TryGetValue(f.Origin,out locations)) runtimeLocations[f.Origin]=locations=new List<Location>();
        locations.Add(new Location {Key=pair.Key,Entry=e});
      }
    }
    map = result;
  }
  // Parser pubblico anche per le prove con dump scritti a mano.
  public static void ReadDump(string id, IEnumerable<string> lines) {
    string c = "", n = ""; Method current = null;
    foreach (string line in lines) {
      var m = cls.Match(line);
      if (m.Success) { c=m.Groups[1].Value; current=null; continue; }
      m=name.Match(line); if(m.Success) { n=m.Groups[1].Value; current=null; continue; }
      m=type.Match(line);
      if(m.Success && m.Groups[1].Value.StartsWith("(")) {
        current=new Method { Key=c+"."+n, Signature=m.Groups[1].Value };
        List<Entry> es;
        current.KeepBody=current.Key.Contains("$$ExternalSyntheticApiModelOutline");
        if(map!=null && map.TryGetValue(current.Key,out es)) foreach(var e in es) foreach(var f in e.Frames) if(runtimeLocations.ContainsKey(f.Origin)) current.KeepBody=true;
        if(current.KeepBody) methods[current.Key+":"+current.Signature]=current; continue;
      }
      if(current==null) continue;
      m=insn.Match(line);
      if(m.Success) {
        if(current.KeepBody) current.Instructions.Add(line);
        string op=m.Groups[2].Value;
        if(op.StartsWith("invoke") || op=="new-instance" || op.StartsWith("sget")) instructions[id+"|"+Address(line)]=current;
        continue;
      }
      m=pos.Match(line);
      if(m.Success) current.Positions[Convert.ToInt32(m.Groups[1].Value,16)]=Int32.Parse(m.Groups[2].Value);
    }
  }
  public static void Prepare(string dexdump,string dex) {
    LoadMap();
    if(aliases.ContainsKey(dex)) return;
    string id;
    using(var sha=System.Security.Cryptography.SHA256.Create()) using(var f=File.OpenRead(dex)) id=BitConverter.ToString(sha.ComputeHash(f));
    string shortId;
    if(!contentIds.TryGetValue(id,out shortId)) contentIds[id]=shortId=contentIds.Count.ToString();
    id=shortId;
    if(prepared.Contains(id)) { aliases[dex]=id; return; }
    string dump=dex+".hk-dump";
    var info=new ProcessStartInfo(dexdump,"-d \""+dex+"\"") {UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true};
    using(var p=Process.Start(info)) {
      var errors=p.StandardError.ReadToEndAsync();
      using(var w=new StreamWriter(dump)) {string line; while((line=p.StandardOutput.ReadLine())!=null) w.WriteLine(line);}
      p.WaitForExit(); if(p.ExitCode!=0) throw new InvalidOperationException("dexdump: "+errors.Result);
    }
    ReadDump(id,File.ReadLines(dump)); prepared.Add(id); aliases[dex]=id;
  }
  static Method InstructionMethod(string dex,string line) {
    LoadMap(); Method m;
    string id=aliases.ContainsKey(dex) ? aliases[dex] : dex;
    if(!instructions.TryGetValue(id+"|"+Address(line),out m)) throw new InvalidOperationException("HK: istruzione non indicizzata");
    return m;
  }
  static int Position(Method m,string line) {
    int pc=Convert.ToInt32(insn.Match(line).Groups[1].Value,16), residual=-1;
    foreach(var p in m.Positions) {if(p.Key>pc) break; residual=p.Value;}
    return residual;
  }
  static bool IsOurOrigin(string origin) {
    return origin.StartsWith("Ldev/pampa/") || origin.StartsWith("Ldev/antigravity/fluidengine/");
  }
  static string PolicyOrigin(Entry entry) {
    // CR-3: la libreria inline e' trasparente. Decide il primo autore nostro
    // dal frame interno verso l'esterno; in assenza di nostri frame vale l'interno.
    foreach(var frame in entry.Frames) if(IsOurOrigin(frame.Origin)) return frame.Origin;
    return entry.Origin;
  }
  public static string[] Origins(string dex,string line,string target) {
    Method m=InstructionMethod(dex,line);
    // La firma di un ponte sintetico e' attribuita al callee, ispezionato a sua volta.
    if(Regex.IsMatch(m.Key,@"\$\$ExternalSyntheticLambda\d+;\.")) {
      var bridge=Regex.Match(line,@"\}\s*,\s*(L[^;\s]+;)\.(\$r8\$lambda\$[^:\s]+):");
      if(bridge.Success) return new string[]{bridge.Groups[1].Value+"."+bridge.Groups[2].Value};
    }
    List<Entry> entries;
    if(!map.TryGetValue(m.Key,out entries)) throw new InvalidOperationException("HK: origine assente in mapping: "+m.Key);
    int residual=Position(m,line);
    Entry first=null; bool hasSignature=false;
    foreach(var e in entries) {
      if(e.Signature!=m.Signature) continue;
      hasSignature=true;
      if(e.Lo>=0 && residual>=e.Lo && residual<=e.Hi) {
        if(first==null) first=e;
        else if(first.Range!=e.Range || first.Origin!=e.Origin) throw new InvalidOperationException("HK: intervalli ambigui: "+m.Key+" line="+residual);
      }
    }
    if(!hasSignature) throw new InvalidOperationException("HK: firma residuale non risolta: "+m.Key+":"+m.Signature);
    if(first!=null) {
      string author=PolicyOrigin(first);
      if(target=="Landroidx/glance/" && !author.StartsWith("Landroidx/glance/") && !author.StartsWith("Ldev/")) PolicyEvidence.Add("POSIZIONATA "+m.Key+" -> "+author+" :: "+line);
      return new string[]{author};
    }
    // Il prologo puo' precedere il dispatch di una classe fusa. Nessuna scelta dal nome,
    // dai campi condivisi o dal ramo del switch: tutti i frame e il contenitore sono candidati.
    var candidates=new HashSet<string>();
    foreach(var e in entries) if(e.Signature==m.Signature) foreach(var f in e.Frames) candidates.Add(f.Origin);
    if(candidates.Count==0) throw new InvalidOperationException("HK: nessuna origine nel mapping: "+m.Key);
    candidates.Add(m.Key);
    if(target=="Landroidx/glance/") {
      var remove=new List<string>();
      foreach(string origin in candidates) if(!origin.StartsWith(target) && ProveInnocentInlineRuntime(m,entries,origin,line,target)) remove.Add(origin);
      foreach(string origin in remove) { candidates.Remove(origin); RuntimeProofs.Add(m.Key+" pc="+insn.Match(line).Groups[1].Value+": frame inline innocuo dimostrato "+origin); }
    }
    if(target=="Landroidx/glance/" && candidates.Count>1) PolicyEvidence.Add("SENZA POSIZIONE "+m.Key+" -> "+String.Join(", ",new List<string>(candidates).ToArray())+" :: "+line);
    var result=new List<string>(candidates); result.Sort(StringComparer.Ordinal); return result.ToArray();
  }
  public static string OriginGroup(string dex,string line) {return String.Join("\n",Origins(dex,line,""));}
  public static bool IsOnlyOrigin(string dex,string line,string expected) {
    foreach(string o in Origins(dex,line,"")) if(o!=expected) return false;
    return true;
  }
  // Compatibilita' per la diagnosi delle istruzioni che hanno una provenienza precisa.
  public static string Origin(string dex,string line) {
    string[] os=Origins(dex,line,"");
    if(os.Length!=1) throw new InvalidOperationException("HK: origini multiple: "+String.Join(", ",os));
    return os[0];
  }
  static bool HasReference(string text,string prefix) {return text.Contains(prefix);}
  static bool ProveInnocentInlineRuntime(Method m,List<Entry> entries,string origin,string line,string target) {
    if(origin.StartsWith("Ldev/")) return false;
    // Ogni occorrenza nel metodo deve essere un frame INLINE (mai l'ultimo frame).
    bool found=false;
    foreach(var e in entries) if(e.Signature==m.Signature) {
      for(int i=0;i<e.Frames.Count;i++) if(e.Frames[i].Origin==origin) {
        found=true;
        if(i==e.Frames.Count-1 || e.Lo<0 || HasReference(e.Frames[i].Signature,target)) return false;
        bool saw=false;
        foreach(string instruction in m.Instructions) {
          int position=Position(m,instruction);
          if(position>=e.Lo && position<=e.Hi) {
            saw=true;
            // Gli altri frame della stessa catena sono lo stack inline, non gli autori
            // delle istruzioni del callback piu' interno.
            // Il perimetro della policy e' sulle invocazioni: cast generici e spill nei
            // campi della continuazione non sono invocazioni e non possono essere
            // l'autore della chiamata in esame. Il corpo originale, se presente,
            // resta invece controllato in tutte le sue istruzioni qui sotto.
            if(e.Origin==origin && insn.Match(instruction).Groups[2].Value.StartsWith("invoke") && HasReference(instruction,target) && !SignatureOnlyArtifact(insn.Match(instruction).Groups[3].Value,target)) {
              PolicyEvidence.Add("PROVA INLINE RESPINTA "+m.Key+" origine "+origin+" :: "+instruction); return false;
            }
          }
        }
        if(!saw) return false;
      }
    }
    if(!found) return false;
    // Controlla anche gli eventuali corpi residui del metodo runtime, inclusi quelli spostati da R8.
    foreach(var location in runtimeLocations[origin]) {
      Entry e=location.Entry;
      // Le altre copie inline appartengono ad altri contesti; qui servono il frame in esame
      // e gli eventuali corpi originali rimasti, non i corpi dei loro chiamanti.
      if(e.Frames[e.Frames.Count-1].Origin!=origin) continue;
      Method body;
      // Il metodo puo' essere stato eliminato; dove esiste il suo bytecode deve essere leggibile.
      if(!methods.TryGetValue(location.Key+":"+e.Signature,out body)) continue;
      if(body.Instructions.Count==0) return false;
      foreach(string instruction in body.Instructions) if(HasReference(instruction,target) && !SignatureOnlyArtifact(insn.Match(instruction).Groups[3].Value,target)) {
        PolicyEvidence.Add("CORPO RUNTIME RESPINTO "+m.Key+" origine "+origin+" :: "+instruction); return false;
      }
    }
    // La chiamata in esame deve avere un owner esterno al runtime e nominare la libreria
    // nella firma: non puo' essere l'istruzione di quei frame runtime gia' provati privi di tali tipi.
    var call=Regex.Match(insn.Match(line).Groups[3].Value,@"\}\s*,\s*(L[^;\s]+;)\.([^:]+):(\([^ ]+)");
    if(!call.Success || call.Groups[1].Value.StartsWith("Lkotlin/") || !HasReference(call.Value,target)) return false;
    return true;
  }
  public static bool SignatureOnlyArtifact(string rest,string target) {
    LoadMap();
    var call=Regex.Match(rest,@"\}\s*,\s*(L[^;\s]+;)\.([^:]+):(\([^ ]+)");
    if(!call.Success || call.Groups[1].Value.StartsWith(target) || !call.Groups[3].Value.Contains(target)) return false;
    List<Entry> entries;
    if(!map.TryGetValue(call.Groups[1].Value+"."+call.Groups[2].Value,out entries)) return false;
    bool found=false;
    foreach(var e in entries) if(e.Signature==call.Groups[3].Value) {
      if(!e.HasResidualSignature) return false;
      found=true;
      foreach(var f in e.Frames) if(f.Origin.StartsWith(target) || f.Signature.Contains(target)) return false;
    }
    if(found) RuntimeProofs.Add("firma introdotta da residualsignature: "+rest);
    return found;
  }
  // Non basta il nome sintetico: tutte le istruzioni con un riferimento di tipo devono essere di piattaforma.
  // Un corpo mancante, vuoto, una chiamata indiretta o un riferimento non riconosciuto fallisce chiuso.
  public static bool PlatformOutline(string rest) {
    var call=Regex.Match(rest,@"(L[^;\s]+\$\$ExternalSyntheticApiModelOutline\d+;)\.(m[^: ]*):(\([^ ]+)\s");
    if(!call.Success) return false;
    Method m;
    if(!methods.TryGetValue(call.Groups[1].Value+"."+call.Groups[2].Value+":"+call.Groups[3].Value,out m) || m.Instructions.Count==0) throw new InvalidOperationException("HK: corpo outline assente: "+rest);
    bool platform=true;
    foreach(string line in m.Instructions) {
      var i=insn.Match(line); string op=i.Groups[2].Value, body=i.Groups[3].Value;
      if(body.Contains("$$ExternalSyntheticApiModelOutline")) throw new InvalidOperationException("HK: outline annidato non risolto");
      if(op.StartsWith("invoke-custom") || op.StartsWith("invoke-polymorphic")) throw new InvalidOperationException("HK: outline con chiamata indiretta");
      foreach(Match t in Regex.Matches(body,@"L[^;\s]+;")) {
        string s=t.Value;
        if(!(s.StartsWith("Landroid/") || s.StartsWith("Ljava/") || s.StartsWith("Ljavax/") || s.StartsWith("Lkotlin/")) || (s.Contains("URLConnection;") || s.StartsWith("Ljava/net/URL;") || s.StartsWith("Ljavax/net/ssl/SSLSocket") || s.StartsWith("Ljavax/net/ssl/SSLEngine;") || s.StartsWith("Ljava/net/http/HttpClient") || s.StartsWith("Landroid/net/http/") || s.StartsWith("Landroid/net/Network;") || s.StartsWith("Landroid/app/DownloadManager;") || s.StartsWith("Landroid/webkit/WebView;"))) platform=false;

      }
    }

    return platform;
  }
  public static string OutlineBody(string rest) {
    var call=Regex.Match(rest,@"(L[^;\s]+\$\$ExternalSyntheticApiModelOutline\d+;)\.(m[^: ]*):(\([^ ]+)\s");
    if(!call.Success) throw new InvalidOperationException("HK: outline illeggibile");
    Method m;
    if(!methods.TryGetValue(call.Groups[1].Value+"."+call.Groups[2].Value+":"+call.Groups[3].Value,out m)) throw new InvalidOperationException("HK: corpo outline assente");
    return String.Join(" ",m.Instructions.ToArray());
  }
}
