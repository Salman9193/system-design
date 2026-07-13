#!/usr/bin/env python3
"""
Static site generator for the system-design prep repo.

Produces a single self-contained index.html (SPA with hash routing) from the
markdown/java content. Carries over the proven approach from the dsa-problems
generator: light theme, hash-based single-page navigation, and a tokenizing
syntax highlighter for code blocks — starting clean rather than re-deriving bugs.

Content model:
  frameworks/*.md        -> each a single-page doc under the "Frameworks" section
  fundamentals/*.md      -> each a single-page doc under the "Fundamentals" section
  hld/<system>/*.md      -> each system is a multi-tab page under "HLD"
  lld/<system>/{*.md,*.java} -> each system is a multi-tab page under "LLD"

Run:  python3 scripts/generate_site.py
Out:  site/index.html
"""

import os
import re
import html
import glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "site")

# Preferred tab ordering within a system (files not listed fall to the end,
# alphabetical). Mirrors the natural interview flow.
HLD_ORDER = ["REQUIREMENTS", "DESIGN", "DEEP_DIVES", "TRADEOFFS", "FAILURE_MODES", "SCALING", "AI_EVOLUTION"]
LLD_ORDER = ["PROBLEM", "DESIGN", "ROADMAP", "JVM_PRIMER", "CRITICAL_PATH", "Solution", "NOTES"]

# Sidebar ordering for the Fundamentals section (by file/dir stem). Foundational
# topics first, AI-engineering topics last. Unlisted items fall to the end,
# alphabetically. Applies to both flat .md files and multi-tab folders.
FUNDAMENTALS_ORDER = [
    "communication",
    "networking",
    "storage-and-databases",
    "data-distribution",
    "capacity-estimation",
    "caching",
    "ai-engineering-primer",
    "llm-inference-serving",
    "embeddings-and-vector-search",
]


# ─────────────────────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────────────────────
def slugify(text):
    s = text.lower().strip()
    s = re.sub(r"[^\w\s-]", "", s)
    s = re.sub(r"[\s_]+", "-", s)
    return s.strip("-")


def esc(text):
    return html.escape(text, quote=True)


def read_file(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


ACRONYMS = {"Ai": "AI", "Rag": "RAG", "Llm": "LLM", "Api": "API", "Hld": "HLD",
            "Lld": "LLD", "Kv": "KV", "Ml": "ML", "Ann": "ANN", "Gpu": "GPU",
            "Id": "ID", "Url": "URL", "Sql": "SQL", "Http": "HTTP", "Tcp": "TCP",
            "Udp": "UDP", "Ip": "IP", "Tls": "TLS", "Rpc": "RPC", "Grpc": "gRPC",
            "Rest": "REST", "Soap": "SOAP", "Graphql": "GraphQL", "Sse": "SSE",
            "Dns": "DNS", "Cdn": "CDN", "Jwt": "JWT", "Mtls": "mTLS", "Quic": "QUIC",
            "Nat": "NAT", "Vpn": "VPN", "Vpc": "VPC", "Cidr": "CIDR", "Ssl": "SSL",
            "Oauth": "OAuth", "Rbac": "RBAC", "Abac": "ABAC", "Acid": "ACID",
            "Slo": "SLO", "Sla": "SLA", "Sli": "SLI", "Bgp": "BGP", "Cap": "CAP",
            "Nosql": "NoSQL", "Newsql": "NewSQL", "Pacelc": "PACELC", "Crdt": "CRDT",
            "Jvm": "JVM", "Ast": "AST", "Ir": "IR", "Cpm": "CPM", "Pert": "PERT"}

# Connector words rendered lowercase (unless first) for natural titles.
SMALL_WORDS = {"and", "or", "vs", "the", "of", "to", "a", "an", "in", "on",
               "for", "with", "via", "per"}


def prettify(base):
    """Turn a file/dir stem into a display title.

    Strips a leading numeric ordering prefix (01_, 02-), title-cases, restores
    known acronyms, and lowercases connector words except the first.
    e.g. 05_API_GATEWAY_AND_LOAD_BALANCING -> API Gateway and Load Balancing
    """
    base = re.sub(r"^\d+[_-]", "", base)
    words = base.replace("_", " ").replace("-", " ").title().split()
    out = []
    for idx, w in enumerate(words):
        if w in ACRONYMS:
            out.append(ACRONYMS[w])
        elif idx != 0 and w.lower() in SMALL_WORDS:
            out.append(w.lower())
        else:
            out.append(w)
    return " ".join(out)


def title_from_filename(fn):
    """DEEP_DIVES.md -> Deep Dives ; 02_TRANSPORT_PROTOCOLS.md -> Transport Protocols"""
    return prettify(os.path.splitext(fn)[0])


def title_from_dirname(d):
    """search-typeahead -> Search Typeahead ; communication -> Communication"""
    return prettify(d)


# ─────────────────────────────────────────────────────────────────────────────
# Java syntax highlighter (tokenizer — same technique as the DSA repo)
# ─────────────────────────────────────────────────────────────────────────────
JAVA_KEYWORDS = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
    "class", "const", "continue", "default", "do", "double", "else", "enum",
    "extends", "final", "finally", "float", "for", "goto", "if", "implements",
    "import", "instanceof", "int", "interface", "long", "native", "new",
    "package", "private", "protected", "public", "return", "short", "static",
    "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
    "transient", "try", "void", "volatile", "while", "var", "true", "false",
    "null",
}


def highlight_java(code):
    """
    Tokenize Java into spans. Handles: block comments, line comments, strings,
    char literals, numbers, keywords, annotations, and plain identifiers.
    Everything is escaped as it's emitted, so the output is safe HTML.
    """
    out = []
    i = 0
    n = len(code)
    while i < n:
        c = code[i]

        # Block comment
        if c == "/" and i + 1 < n and code[i + 1] == "*":
            j = code.find("*/", i + 2)
            j = n if j == -1 else j + 2
            out.append(f'<span class="cm">{esc(code[i:j])}</span>')
            i = j
            continue

        # Line comment
        if c == "/" and i + 1 < n and code[i + 1] == "/":
            j = code.find("\n", i)
            j = n if j == -1 else j
            out.append(f'<span class="cm">{esc(code[i:j])}</span>')
            i = j
            continue

        # String literal
        if c == '"':
            j = i + 1
            while j < n and code[j] != '"':
                if code[j] == "\\":
                    j += 2
                else:
                    j += 1
            j = min(j + 1, n)
            out.append(f'<span class="st">{esc(code[i:j])}</span>')
            i = j
            continue

        # Char literal
        if c == "'":
            j = i + 1
            while j < n and code[j] != "'":
                if code[j] == "\\":
                    j += 2
                else:
                    j += 1
            j = min(j + 1, n)
            out.append(f'<span class="st">{esc(code[i:j])}</span>')
            i = j
            continue

        # Annotation
        if c == "@":
            j = i + 1
            while j < n and (code[j].isalnum() or code[j] == "_"):
                j += 1
            out.append(f'<span class="an">{esc(code[i:j])}</span>')
            i = j
            continue

        # Number
        if c.isdigit():
            j = i
            while j < n and (code[j].isalnum() or code[j] in "._"):
                j += 1
            out.append(f'<span class="nu">{esc(code[i:j])}</span>')
            i = j
            continue

        # Identifier / keyword
        if c.isalpha() or c == "_":
            j = i
            while j < n and (code[j].isalnum() or code[j] == "_"):
                j += 1
            word = code[i:j]
            if word in JAVA_KEYWORDS:
                out.append(f'<span class="kw">{esc(word)}</span>')
            else:
                out.append(esc(word))
            i = j
            continue

        # Anything else
        out.append(esc(c))
        i += 1

    return "".join(out)


# ─────────────────────────────────────────────────────────────────────────────
# Minimal Markdown -> HTML
# ─────────────────────────────────────────────────────────────────────────────
def inline_md(text):
    """Inline formatting. `text` arrives already HTML-escaped."""
    # inline code
    text = re.sub(r"`([^`]+)`", r"<code>\1</code>", text)
    # bold
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    # italic (avoid matching bold leftovers)
    text = re.sub(r"(?<!\*)\*(?!\*)([^*]+)\*(?!\*)", r"<em>\1</em>", text)
    # links
    text = re.sub(r"\[([^\]]+)\]\(([^)]+)\)",
                  r'<a href="\2" target="_blank" rel="noopener">\1</a>', text)
    return text


def md_to_html(md):
    """
    Convert a markdown document to HTML. Supports headings, fenced code blocks
    (```lang), tables, unordered/ordered lists, blockquotes, hr, and paragraphs.
    """
    lines = md.split("\n")
    out = []
    i = 0
    n = len(lines)

    while i < n:
        line = lines[i]

        # Fenced code block
        m = re.match(r"^```(\w*)\s*$", line)
        if m:
            lang = m.group(1)
            i += 1
            buf = []
            while i < n and not re.match(r"^```\s*$", lines[i]):
                buf.append(lines[i])
                i += 1
            i += 1  # skip closing fence
            code = "\n".join(buf)
            if lang == "java":
                rendered = highlight_java(code)
            else:
                rendered = esc(code)
            out.append(f'<pre><code>{rendered}</code></pre>')
            continue

        # Heading
        m = re.match(r"^(#{1,6})\s+(.*)$", line)
        if m:
            level = len(m.group(1))
            content = inline_md(esc(m.group(2)))
            out.append(f"<h{level}>{content}</h{level}>")
            i += 1
            continue

        # Horizontal rule
        if re.match(r"^---+\s*$", line):
            out.append("<hr>")
            i += 1
            continue

        # Table (header row followed by a |---| separator)
        if "|" in line and i + 1 < n and re.match(r"^\s*\|?[\s:|-]+\|?\s*$", lines[i + 1]) and "-" in lines[i + 1]:
            header = [c.strip() for c in line.strip().strip("|").split("|")]
            i += 2  # skip header + separator
            rows = []
            while i < n and "|" in lines[i] and lines[i].strip():
                cells = [c.strip() for c in lines[i].strip().strip("|").split("|")]
                rows.append(cells)
                i += 1
            thead = "".join(f"<th>{inline_md(esc(h))}</th>" for h in header)
            tbody = ""
            for r in rows:
                tds = "".join(f"<td>{inline_md(esc(c))}</td>" for c in r)
                tbody += f"<tr>{tds}</tr>"
            out.append(f"<table><thead><tr>{thead}</tr></thead><tbody>{tbody}</tbody></table>")
            continue

        # Blockquote
        if re.match(r"^>\s?", line):
            buf = []
            while i < n and re.match(r"^>\s?", lines[i]):
                buf.append(re.sub(r"^>\s?", "", lines[i]))
                i += 1
            inner = inline_md(esc(" ".join(buf)))
            out.append(f"<blockquote>{inner}</blockquote>")
            continue

        # Unordered list
        if re.match(r"^\s*[-*]\s+", line):
            buf = []
            while i < n and re.match(r"^\s*[-*]\s+", lines[i]):
                item = re.sub(r"^\s*[-*]\s+", "", lines[i])
                buf.append(f"<li>{inline_md(esc(item))}</li>")
                i += 1
            out.append(f"<ul>{''.join(buf)}</ul>")
            continue

        # Ordered list
        if re.match(r"^\s*\d+\.\s+", line):
            buf = []
            while i < n and re.match(r"^\s*\d+\.\s+", lines[i]):
                item = re.sub(r"^\s*\d+\.\s+", "", lines[i])
                buf.append(f"<li>{inline_md(esc(item))}</li>")
                i += 1
            out.append(f"<ol>{''.join(buf)}</ol>")
            continue

        # Blank line
        if line.strip() == "":
            i += 1
            continue

        # Paragraph (gather until blank or block-starter)
        buf = [line]
        i += 1
        while i < n and lines[i].strip() != "" and not re.match(
            r"^(#{1,6}\s|```|>\s?|\s*[-*]\s+|\s*\d+\.\s+|---+\s*$)", lines[i]
        ):
            buf.append(lines[i])
            i += 1
        para = inline_md(esc(" ".join(buf)))
        out.append(f"<p>{para}</p>")

    return "\n".join(out)


# ─────────────────────────────────────────────────────────────────────────────
# Content discovery
# ─────────────────────────────────────────────────────────────────────────────
def discover():
    """Return the site model: a list of sections, each with pages."""
    sections = []

    # Frameworks — flat md files
    fw_pages = []
    for path in sorted(glob.glob(os.path.join(ROOT, "frameworks", "*.md"))):
        fn = os.path.basename(path)
        fw_pages.append({
            "id": "fw-" + slugify(os.path.splitext(fn)[0]),
            "title": title_from_filename(fn),
            "tabs": [{"title": title_from_filename(fn), "html": md_to_html(read_file(path))}],
        })
    if fw_pages:
        sections.append({"id": "frameworks", "title": "Frameworks", "pages": fw_pages})

    # Fundamentals — a flat .md is a single-page doc; a folder is a multi-tab
    # page (tabs ordered by numeric filename prefix). Section order is set by
    # FUNDAMENTALS_ORDER (unlisted fall to the end, alphabetically).
    fu_pages = []
    fbase = os.path.join(ROOT, "fundamentals")
    if os.path.isdir(fbase):
        def fu_key(name):
            stem = os.path.splitext(name)[0]
            rank = FUNDAMENTALS_ORDER.index(stem) if stem in FUNDAMENTALS_ORDER else len(FUNDAMENTALS_ORDER)
            return (rank, name)

        for name in sorted(os.listdir(fbase), key=fu_key):
            path = os.path.join(fbase, name)
            if os.path.isfile(path) and name.endswith(".md"):
                stem = os.path.splitext(name)[0]
                fu_pages.append({
                    "id": "fu-" + slugify(stem),
                    "title": title_from_filename(name),
                    "tabs": [{"title": title_from_filename(name), "html": md_to_html(read_file(path))}],
                })
            elif os.path.isdir(path):
                tab_files = sorted(f for f in os.listdir(path)
                                   if f.endswith(".md") or f.endswith(".java"))
                tabs = []
                for f in tab_files:  # numeric prefixes sort correctly
                    content = read_file(os.path.join(path, f))
                    if f.endswith(".java"):
                        tab_html = f'<pre><code>{highlight_java(content)}</code></pre>'
                    else:
                        tab_html = md_to_html(content)
                    tabs.append({"title": title_from_filename(f), "html": tab_html})
                if tabs:
                    fu_pages.append({
                        "id": "fu-" + slugify(name),
                        "title": title_from_dirname(name),
                        "tabs": tabs,
                    })
    if fu_pages:
        sections.append({"id": "fundamentals", "title": "Fundamentals", "pages": fu_pages})

    # LLD Fundamentals — design-pattern reference (same layout rules as Fundamentals:
    # a flat .md is single-page, a folder is a multi-tab page ordered by filename).
    lf_pages = []
    lfbase = os.path.join(ROOT, "lld-fundamentals")
    if os.path.isdir(lfbase):
        for name in sorted(os.listdir(lfbase)):
            path = os.path.join(lfbase, name)
            if os.path.isfile(path) and name.endswith(".md"):
                stem = os.path.splitext(name)[0]
                lf_pages.append({
                    "id": "lf-" + slugify(stem),
                    "title": title_from_filename(name),
                    "tabs": [{"title": title_from_filename(name), "html": md_to_html(read_file(path))}],
                })
            elif os.path.isdir(path):
                tab_files = sorted(f for f in os.listdir(path)
                                   if f.endswith(".md") or f.endswith(".java"))
                tabs = []
                for f in tab_files:
                    content = read_file(os.path.join(path, f))
                    if f.endswith(".java"):
                        tab_html = f'<pre><code>{highlight_java(content)}</code></pre>'
                    else:
                        tab_html = md_to_html(content)
                    tabs.append({"title": title_from_filename(f), "html": tab_html})
                if tabs:
                    lf_pages.append({
                        "id": "lf-" + slugify(name),
                        "title": title_from_dirname(name),
                        "tabs": tabs,
                    })
    if lf_pages:
        sections.append({"id": "lld-fundamentals", "title": "LLD Fundamentals", "pages": lf_pages})

    # HLD / LLD — dir per system, multi-tab
    for kind, order in (("hld", HLD_ORDER), ("lld", LLD_ORDER)):
        pages = []
        base = os.path.join(ROOT, kind)
        if not os.path.isdir(base):
            continue
        for system in sorted(os.listdir(base)):
            sdir = os.path.join(base, system)
            if not os.path.isdir(sdir):
                continue
            files = [f for f in os.listdir(sdir) if f.endswith(".md") or f.endswith(".java")]

            def sort_key(f):
                stem = os.path.splitext(f)[0]
                return (order.index(stem) if stem in order else len(order), f)

            files.sort(key=sort_key)
            tabs = []
            for f in files:
                content = read_file(os.path.join(sdir, f))
                if f.endswith(".java"):
                    tab_html = f'<pre><code>{highlight_java(content)}</code></pre>'
                else:
                    tab_html = md_to_html(content)
                tabs.append({"title": title_from_filename(f), "html": tab_html})
            pages.append({
                "id": f"{kind}-" + slugify(system),
                "title": title_from_dirname(system),
                "tabs": tabs,
            })
        if pages:
            sections.append({"id": kind, "title": kind.upper(), "pages": pages})

    return sections


# ─────────────────────────────────────────────────────────────────────────────
# HTML rendering
# ─────────────────────────────────────────────────────────────────────────────
CSS = """
:root{
  --bg:#ffffff; --fg:#1f2328; --muted:#6e7781; --border:#d0d7de;
  --accent:#0969da; --accent-soft:#ddf4ff; --sidebar:#f6f8fa;
  --code-bg:#f6f8fa; --code-fg:#1f2328; --kw:#cf222e; --st:#0a3069;
  --cm:#6e7781; --nu:#0550ae; --an:#8250df;
}
*{box-sizing:border-box}
body{margin:0;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Helvetica,Arial,sans-serif;
  color:var(--fg);background:var(--bg);line-height:1.6;font-size:16px}
a{color:var(--accent);text-decoration:none}
a:hover{text-decoration:underline}
.layout{display:flex;min-height:100vh}
.sidebar{width:280px;flex-shrink:0;background:var(--sidebar);border-right:1px solid var(--border);
  padding:1.5rem 0;position:sticky;top:0;height:100vh;overflow-y:auto}
.sidebar h1{font-size:1.05rem;margin:0 1.5rem 1rem;padding-bottom:1rem;border-bottom:1px solid var(--border)}
.sidebar .sub{font-size:.8rem;color:var(--muted);font-weight:400;display:block;margin-top:.25rem}
.nav-section{margin-bottom:.5rem}
.nav-section > .nav-title{font-size:.72rem;text-transform:uppercase;letter-spacing:.05em;
  color:var(--muted);font-weight:600;padding:.5rem 1.5rem;margin-top:.75rem}
.nav-link{display:block;padding:.35rem 1.5rem;color:var(--fg);font-size:.9rem;cursor:pointer;
  border-left:3px solid transparent}
.nav-link:hover{background:var(--accent-soft);text-decoration:none}
.nav-link.active{background:var(--accent-soft);border-left-color:var(--accent);color:var(--accent);font-weight:600}
.main{flex:1;padding:2.5rem 3rem;min-width:0}
.page{display:none}
.page.active{display:block}
.page-title{font-size:1.9rem;margin:0 0 1.5rem;padding-bottom:.5rem}
.tabs{display:flex;flex-wrap:wrap;gap:.25rem;border-bottom:1px solid var(--border);margin-bottom:1.5rem}
.tab-btn{padding:.5rem 1rem;cursor:pointer;background:none;border:none;font-size:.9rem;
  color:var(--muted);border-bottom:2px solid transparent;font-family:inherit}
.tab-btn:hover{color:var(--fg)}
.tab-btn.active{color:var(--accent);border-bottom-color:var(--accent);font-weight:600}
.tab-pane{display:none}
.tab-pane.active{display:block}
.content h1{font-size:1.6rem;margin:1.5rem 0 1rem}
.content h2{font-size:1.3rem;margin:1.5rem 0 .75rem;padding-bottom:.3rem;border-bottom:1px solid var(--border)}
.content h3{font-size:1.1rem;margin:1.25rem 0 .5rem}
.content h4{font-size:1rem;margin:1rem 0 .5rem}
.content p{margin:.75rem 0}
.content ul,.content ol{margin:.75rem 0;padding-left:1.5rem}
.content li{margin:.25rem 0}
.content blockquote{margin:1rem 0;padding:.5rem 1rem;border-left:4px solid var(--accent);
  background:var(--accent-soft);color:var(--fg);border-radius:0 4px 4px 0}
.content blockquote p{margin:.25rem 0}
.content code{background:var(--code-bg);padding:.15em .4em;border-radius:4px;font-size:.88em;
  font-family:"SF Mono",Menlo,Consolas,monospace}
.content pre{background:var(--code-bg);color:var(--code-fg);padding:1rem;border-radius:6px;
  overflow-x:auto;border:1px solid var(--border);margin:1rem 0}
.content pre code{background:none;padding:0;font-size:.85rem;line-height:1.5}
.content table{border-collapse:collapse;margin:1rem 0;width:100%;font-size:.9rem}
.content th,.content td{border:1px solid var(--border);padding:.5rem .75rem;text-align:left;vertical-align:top}
.content th{background:var(--sidebar);font-weight:600}
.content hr{border:none;border-top:1px solid var(--border);margin:1.5rem 0}
.kw{color:var(--kw)}.st{color:var(--st)}.cm{color:var(--cm);font-style:italic}
.nu{color:var(--nu)}.an{color:var(--an)}
.menu-toggle{display:none}
@media(max-width:820px){
  .sidebar{position:fixed;left:-280px;z-index:100;transition:left .2s;box-shadow:2px 0 8px rgba(0,0,0,.1)}
  .sidebar.open{left:0}
  .main{padding:1.5rem}
  .menu-toggle{display:block;position:fixed;top:1rem;left:1rem;z-index:101;background:var(--accent);
    color:#fff;border:none;border-radius:6px;padding:.5rem .75rem;cursor:pointer;font-size:1rem}
}
"""

JS = """
function showPage(id){
  document.querySelectorAll('.page').forEach(p=>p.classList.remove('active'));
  document.querySelectorAll('.nav-link').forEach(l=>l.classList.remove('active'));
  var page=document.getElementById('page-'+id);
  var link=document.getElementById('nav-'+id);
  if(page)page.classList.add('active');
  if(link)link.classList.add('active');
  var sb=document.querySelector('.sidebar');if(sb)sb.classList.remove('open');
  window.scrollTo(0,0);
}
function showTab(pageId,idx){
  var page=document.getElementById('page-'+pageId);
  if(!page)return;
  page.querySelectorAll('.tab-btn').forEach((b,i)=>b.classList.toggle('active',i===idx));
  page.querySelectorAll('.tab-pane').forEach((p,i)=>p.classList.toggle('active',i===idx));
}
function route(){
  var h=location.hash.replace(/^#/,'');
  if(h && document.getElementById('page-'+h)){showPage(h);}
  else{
    var first=document.querySelector('.nav-link');
    if(first)showPage(first.id.replace(/^nav-/,''));
  }
}
window.addEventListener('hashchange',route);
window.addEventListener('DOMContentLoaded',function(){
  route();
  var t=document.querySelector('.menu-toggle');
  if(t)t.addEventListener('click',function(){document.querySelector('.sidebar').classList.toggle('open');});
});
"""


def render(sections):
    # Sidebar nav
    nav = []
    for sec in sections:
        nav.append(f'<div class="nav-section"><div class="nav-title">{esc(sec["title"])}</div>')
        for page in sec["pages"]:
            nav.append(
                f'<a class="nav-link" id="nav-{page["id"]}" '
                f'href="#{page["id"]}">{esc(page["title"])}</a>'
            )
        nav.append("</div>")
    nav_html = "\n".join(nav)

    # Pages
    pages_html = []
    for sec in sections:
        for page in sec["pages"]:
            tabs = page["tabs"]
            if len(tabs) == 1:
                body = f'<div class="content">{tabs[0]["html"]}</div>'
            else:
                btns = "".join(
                    f'<button class="tab-btn{" active" if i == 0 else ""}" '
                    f'onclick="showTab(\'{page["id"]}\',{i})">{esc(t["title"])}</button>'
                    for i, t in enumerate(tabs)
                )
                panes = "".join(
                    f'<div class="tab-pane{" active" if i == 0 else ""}">'
                    f'<div class="content">{t["html"]}</div></div>'
                    for i, t in enumerate(tabs)
                )
                body = f'<div class="tabs">{btns}</div>{panes}'
            pages_html.append(
                f'<div class="page" id="page-{page["id"]}">'
                f'<h2 class="page-title">{esc(page["title"])}</h2>{body}</div>'
            )
    pages_joined = "\n".join(pages_html)

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>System Design Prep — Staff Engineer</title>
<style>{CSS}</style>
</head>
<body>
<button class="menu-toggle" aria-label="Menu">&#9776;</button>
<div class="layout">
  <nav class="sidebar">
    <h1>System Design Prep<span class="sub">Staff Engineer</span></h1>
    {nav_html}
  </nav>
  <main class="main">
    {pages_joined}
  </main>
</div>
<script>{JS}</script>
</body>
</html>"""


def main():
    sections = discover()
    os.makedirs(OUT_DIR, exist_ok=True)
    html_out = render(sections)
    out_path = os.path.join(OUT_DIR, "index.html")
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(html_out)

    # Report
    total_pages = sum(len(s["pages"]) for s in sections)
    print(f"Generated {out_path}")
    print(f"Sections: {len(sections)}, pages: {total_pages}")
    for s in sections:
        print(f"  {s['title']}: {len(s['pages'])} page(s)")


if __name__ == "__main__":
    main()
