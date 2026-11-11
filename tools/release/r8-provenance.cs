using System;
using System.IO;
using System.Collections.Generic;
using System.Text.RegularExpressions;
using System.Diagnostics;

// HK: identita' dell'ISTRUZIONE, mai il nome scelto per una classe fusa.
public static class R8Provenance {
  class Entry { public int Lo, Hi; public string Origin, Range, Signature; }
  class Method {
    public string Key, Signature;
    public SortedList<int,int> Positions = new SortedList<int,int>();
    public List<string> Instructions = new List<string>();
  }
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
        if(sm.Success && sm.Groups[1].Value.StartsWith("(")) last.Signature=sm.Groups[1].Value;
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
      if(last!=null && lastKey==key && last.Range==range && m.Groups[1].Success) {last.Signature=String.Intern(sig); continue;}
      last=new Entry { Lo = m.Groups[1].Success ? Int32.Parse(m.Groups[1].Value) : -1,
        Hi = m.Groups[2].Success ? Int32.Parse(m.Groups[2].Value) : -1, Origin = String.Intern(origin),
        Range = String.Intern(range), Signature=String.Intern(sig) };
      list.Add(last); lastKey=key;
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
        if(current.Key.Contains("$$ExternalSyntheticApiModelOutline")) methods[current.Key+":"+current.Signature]=current; continue;
      }
      if(current==null) continue;
      m=insn.Match(line);
      if(m.Success) {
        if(current.Key.Contains("$$ExternalSyntheticApiModelOutline")) current.Instructions.Add(line);
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
  public static string Origin(string dex,string line) {
    LoadMap(); Method m;
    string id=aliases.ContainsKey(dex) ? aliases[dex] : dex;
    if(!instructions.TryGetValue(id+"|"+Address(line),out m)) throw new InvalidOperationException("HK: istruzione non indicizzata");
    // Il ponte non esegue qui codice della libreria: la sua firma ne nomina i tipi.
    // L'identita' e' il callee del ponte, il cui corpo viene ispezionato separatamente.
    if(Regex.IsMatch(m.Key,@"\$\$ExternalSyntheticLambda\d+;\.")) {
      var bridge=Regex.Match(line,@"\}\s*,\s*(L[^;\s]+;)\.(\$r8\$lambda\$[^:\s]+):");
      if(bridge.Success) return bridge.Groups[1].Value+"."+bridge.Groups[2].Value;
    }
    var im=insn.Match(line); int pc=Convert.ToInt32(im.Groups[1].Value,16), residual=-1;
    foreach(var p in m.Positions) {if(p.Key>pc) break; residual=p.Value;}
    List<Entry> entries;
    if(!map.TryGetValue(m.Key,out entries)) throw new InvalidOperationException("HK: origine assente in mapping: "+m.Key);
    // R8 scrive prima il frame inline piu' interno; le righe successive dello STESSO intervallo sono lo stack inline.
    // Intervalli diversi sovrapposti o posizioni assenti non autorizzano una scelta arbitraria.
    Entry first=null;
    foreach(var e in entries) {
      if(e.Signature!=m.Signature) continue;
      if(e.Lo>=0 && residual>=e.Lo && residual<=e.Hi) {
        if(first==null) first=e;
        else if(first.Range!=e.Range || first.Origin!=e.Origin) throw new InvalidOperationException("HK: intervalli ambigui: "+m.Key+" line="+residual);
      }
    }
    if(first!=null) return first.Origin;
    // Senza tabella posizioni si accetta solo un metodo con UNA identita' originale (anche se piu' intervalli).
    string only=null;
    foreach(var e in entries) {if(e.Signature!=m.Signature) continue; if(only==null) only=e.Origin; else if(only!=e.Origin) throw new InvalidOperationException("HK: origine indeterminata: "+m.Key+" pc="+pc+" line="+residual);}
    if(only==null) throw new InvalidOperationException("HK: firma residuale non risolta: "+m.Key+":"+m.Signature);
    return only;
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
