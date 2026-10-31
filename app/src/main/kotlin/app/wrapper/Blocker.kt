package app.wrapper

import android.content.Context
import android.webkit.JavascriptInterface
import org.json.JSONArray

/** JS-visible bridge: returns the CSS selectors to hide for a hostname. */
class CosmeticBridge {
    @JavascriptInterface
    fun selectors(host: String?, top: Boolean): String = Blocker.selectors(host ?: "", top)
}

/**
 * Native filter engine that reads uBlock Origin / EasyList syntax from the .txt files in assets/filters.
 *  - Cosmetic: `##sel`, `domain##sel`, `#@#sel` (plain CSS selectors only)
 *  - Network:  `||host^`, `||host^$third-party`, `@@||host^` (host-level rules only)
 */
object Blocker {
    @Volatile var ready = false
    private val generic = ArrayList<String>()
    private val globalEx = HashSet<String>()
    private val perDomain = HashMap<String, ArrayList<String>>()
    private val domainEx = HashMap<String, HashSet<String>>()
    private val blockAll = HashSet<String>()
    private val blockThird = HashSet<String>()
    private val allow = HashSet<String>()

    private val unsupported = Regex(
        ":(has-text|-abp-[a-z-]+|xpath|matches-[a-z-]+|upward|contains|style|remove|others|min-text-length|nth-ancestor|watch-attr|not-text)\\(|^(\\+js\\(|\\^)|\\{"
    )
    private val hostRe = Regex("[a-z0-9.-]+")

    @Synchronized
    fun load(ctx: Context) {
        if (ready) return
        val am = ctx.assets
        for (f in am.list("filters").orEmpty()) {
            am.open("filters/$f").bufferedReader().useLines { lines -> lines.forEach { parse(it) } }
        }
        generic.removeAll(globalEx)
        ready = true
    }

    private fun parse(raw: String) {
        val l = raw.trim()
        if (l.isEmpty() || l[0] == '!' || l[0] == '[') return
        val i = l.indexOf("##")
        val j = l.indexOf("#@#")
        if (i >= 0 || j >= 0) {
            val isEx = j >= 0 && (i < 0 || j < i)
            val idx = if (isEx) j else i
            val sel = l.substring(idx + if (isEx) 3 else 2).trim()
            val doms = l.substring(0, idx)
            if (sel.isEmpty() || doms.contains('~') || unsupported.containsMatchIn(sel)) return
            if (doms.isEmpty()) {
                if (isEx) globalEx.add(sel) else generic.add(sel)
            } else for (d in doms.split(',')) {
                val k = d.trim().lowercase()
                if (k.isEmpty() || k.contains('*')) continue
                if (isEx) domainEx.getOrPut(k) { HashSet() }.add(sel)
                else perDomain.getOrPut(k) { ArrayList() }.add(sel)
            }
        } else if (l.startsWith("||") || l.startsWith("@@||")) {
            network(l)
        }
    }

    private fun network(l: String) {
        val isAllow = l.startsWith("@@")
        var r = l.substring(if (isAllow) 4 else 2)
        var third = false
        val d = r.indexOf('$')
        if (d >= 0) {
            for (o in r.substring(d + 1).split(',')) {
                when (o) {
                    "third-party", "3p" -> third = true
                    "all", "important" -> {}
                    else -> return
                }
            }
            r = r.substring(0, d)
        }
        if (!r.endsWith("^")) return
        val host = r.dropLast(1)
        if (host.isEmpty() || !hostRe.matches(host)) return
        when {
            isAllow -> allow.add(host)
            third -> blockThird.add(host)
            else -> blockAll.add(host)
        }
    }

    fun selectors(host: String, top: Boolean): String {
        if (!ready) return "[]"
        val out = ArrayList<String>()
        val ex = HashSet<String>()
        var h = host.lowercase()
        while (true) {
            perDomain[h]?.let { out.addAll(it) }
            domainEx[h]?.let { ex.addAll(it) }
            val i = h.indexOf('.')
            if (i < 0) break
            h = h.substring(i + 1)
        }
        val list: List<String> = if (top) generic + out else out
        return JSONArray(if (ex.isEmpty()) list else list.filterNot { it in ex }).toString()
    }

    fun shouldBlock(host: String, pageHost: String?): Boolean {
        if (!ready || host.isEmpty()) return false
        var h = host.lowercase()
        var all = false
        var third = false
        while (true) {
            if (h in allow) return false
            if (h in blockAll) all = true
            if (h in blockThird) third = true
            val i = h.indexOf('.')
            if (i < 0) break
            h = h.substring(i + 1)
        }
        return all || (third && pageHost != null && !sameSite(host, pageHost))
    }

    fun sameSite(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        fun key(s: String) = s.lowercase().split('.').takeLast(2).joinToString(".")
        return key(a) == key(b)
    }

    /** Injected at document start in every frame; hides matched elements with a constructed stylesheet. */
    val SCRIPT = """
(function(){try{
if(!window.__ubo||window.__uboDone||!location.hostname)return;
window.__uboDone=1;
var s=JSON.parse(__ubo.selectors(location.hostname,window===window.top));
if(!s.length)return;
var sh=new CSSStyleSheet(),R="{display:none!important}";
for(var i=0;i<s.length;i+=40){var c=s.slice(i,i+40);
try{sh.insertRule(c.join(",")+R)}catch(e){for(var j=0;j<c.length;j++){try{sh.insertRule(c[j]+R)}catch(_){}}}}
document.adoptedStyleSheets=document.adoptedStyleSheets.concat([sh]);
}catch(e){}})();
"""
}
