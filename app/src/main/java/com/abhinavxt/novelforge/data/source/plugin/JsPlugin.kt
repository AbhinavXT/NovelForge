package com.abhinavxt.novelforge.data.source.plugin

// Source plugins — the JavaScript runtime.
//
// A plugin is one JavaScript file that assigns an object to module.exports
// (see docs/PLUGINS.md for the full API). This file loads that script into
// a sandboxed Rhino scope and lets Kotlin call its four entry points.
//
// Deliberately free of Android imports so it can be unit-tested on the JVM.
//
// Sandbox
// -------
//  * initSafeStandardObjects(): no `Packages`, `java`, `JavaAdapter` or any
//    other LiveConnect global. A ClassShutter that refuses every class is
//    added on top, so even a Java object that leaks through (a wrapped
//    exception, say) cannot be reflected on.
//  * The only way out is the bridge defined here: fetch (via [PluginHttp],
//    http/https only), parseHtml (jsoup, exposed as opaque Node objects,
//    never as Java objects), and console.log.
//  * Every call has a wall-clock deadline, and the instruction observer also
//    stops the script when its thread is interrupted (coroutine cancelled,
//    via runInterruptible). Both are thrown as java.lang.Error subclasses,
//    which a plugin's try/catch cannot swallow.
//
// Threading
// ---------
// A Rhino scope is not thread-safe, so calls into one plugin are serialised
// with a lock. Different plugins run in parallel.

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.locks.ReentrantLock

/** An HTTP request a plugin made through fetch(). */
data class PluginRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: String?,
)

/** What fetch() hands back to the plugin. Header names are lower-case. */
data class PluginResponse(
    val status: Int,
    val url: String,
    val body: String,
    val headers: Map<String, String>,
)

/** Transport for fetch(). The app backs it with OkHttp; tests with a stub. */
fun interface PluginHttp {
    @Throws(IOException::class)
    fun execute(request: PluginRequest): PluginResponse
}

/** Thrown for anything a plugin did wrong: bad script, bad export, JS error, timeout. */
class PluginException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The metadata a plugin declares in its exports. */
data class PluginInfo(
    val id: String,
    val name: String,
    val version: String,
    val site: String,
    val lang: String,
    val icon: String?,
)

/**
 * The optional, static exports, read once when the script loads, so the app
 * can ask for them at any time (Browse reads filters on the main thread)
 * without taking the call lock or entering Rhino.
 */
data class PluginCapabilities(
    val canBrowse: Boolean,
    /** `filters` as plain Kotlin values (see JsPlugin.call), or null. */
    val filters: Map<*, *>?,
    val rateLimitMs: Long,
    val stripCredits: Boolean,
)

class JsPlugin private constructor(
    private val scope: ScriptableObject,
    private val exports: Scriptable,
    val info: PluginInfo,
    val capabilities: PluginCapabilities,
    private val callTimeoutMs: Long,
) {

    private val lock = ReentrantLock()

    /**
     * Calls `exports.<function>(args...)` and returns the result converted to
     * plain Kotlin values: String, Double, Boolean, List<Any?>, Map<String, Any?>
     * or null.
     *
     * Blocking. Run it on an IO thread, inside runInterruptible so that
     * cancellation reaches the script.
     */
    fun call(function: String, vararg args: Any?): Any? {
        lock.lockInterruptibly()
        try {
            return withContext(callTimeoutMs, "$function()") { cx ->
                val fn = ScriptableObject.getProperty(exports, function) as? Function
                    ?: throw PluginException("plugin '${info.id}' does not export $function()")
                val jsArgs = args.map { toJs(cx, scope, it) }.toTypedArray()
                toKotlin(fn.call(cx, scope, exports, jsArgs))
            }
        } finally {
            lock.unlock()
        }
    }

    /** True when the plugin exports a function with this name. */
    fun hasFunction(name: String): Boolean = export(name, raw = true) is Function

    /** Reads a non-function export (e.g. `filters`, `rateLimitMs`) as plain Kotlin values. */
    fun export(name: String): Any? = export(name, raw = false)

    private fun export(name: String, raw: Boolean): Any? {
        lock.lockInterruptibly()
        try {
            return withContext(callTimeoutMs, "reading $name") {
                val v = ScriptableObject.getProperty(exports, name)
                if (raw) v else toKotlin(v)
            }
        } finally {
            lock.unlock()
        }
    }

    companion object {
        /** Functions every plugin has to export. */
        val REQUIRED_FUNCTIONS = listOf("popularNovels", "searchNovels", "parseNovel", "parseChapter")

        const val DEFAULT_LOAD_TIMEOUT_MS = 10_000L
        const val DEFAULT_CALL_TIMEOUT_MS = 90_000L

        /**
         * Evaluates [script] in a fresh sandbox and validates its exports.
         * Throws [PluginException] if the script fails or exports the wrong shape.
         */
        fun load(
            script: String,
            fileName: String,
            http: PluginHttp,
            log: (String) -> Unit = {},
            loadTimeoutMs: Long = DEFAULT_LOAD_TIMEOUT_MS,
            callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
        ): JsPlugin = withContext(loadTimeoutMs, "loading $fileName") { cx ->
            val scope = cx.initSafeStandardObjects()
            Bridge(scope, http, log).install()
            cx.evaluateString(scope, PRELUDE, "prelude.js", 1, null)
            cx.evaluateString(scope, script, fileName, 1, null)

            val module = ScriptableObject.getProperty(scope, "module") as? Scriptable
            val exports = module?.let { ScriptableObject.getProperty(it, "exports") } as? Scriptable
                ?: throw PluginException("$fileName: module.exports is not an object")

            fun str(key: String, required: Boolean = true): String? {
                val v = ScriptableObject.getProperty(exports, key)
                val s = if (v is CharSequence) v.toString().trim() else null
                if (required && s.isNullOrEmpty()) {
                    throw PluginException("$fileName: module.exports.$key must be a non-empty string")
                }
                return s?.ifEmpty { null }
            }

            val info = PluginInfo(
                id = str("id")!!,
                name = str("name")!!,
                version = str("version")!!,
                site = str("site")!!,
                lang = str("lang", required = false) ?: "en",
                icon = str("icon", required = false),
            )
            val missing = REQUIRED_FUNCTIONS.filter {
                ScriptableObject.getProperty(exports, it) !is Function
            }
            if (missing.isNotEmpty()) {
                throw PluginException("$fileName: missing exported functions: ${missing.joinToString()}")
            }
            val capabilities = PluginCapabilities(
                canBrowse = ScriptableObject.getProperty(exports, "browseNovels") is Function,
                filters = toKotlin(ScriptableObject.getProperty(exports, "filters")) as? Map<*, *>,
                rateLimitMs = ((toKotlin(ScriptableObject.getProperty(exports, "rateLimitMs")) as? Double) ?: 0.0)
                    .toLong().coerceIn(0L, 10_000L),
                stripCredits = ScriptableObject.getProperty(exports, "stripCredits") != false,
            )
            JsPlugin(scope, exports, info, capabilities, callTimeoutMs)
        }

        // ── Context management ──────────────────────────────────────────

        private val factory = SandboxContextFactory()

        private fun <T> withContext(timeoutMs: Long, what: String, block: (Context) -> T): T {
            val cx = factory.enterContext()
            val guard = Guard(System.currentTimeMillis() + timeoutMs)
            cx.putThreadLocal(Guard::class.java, guard)
            try {
                return block(cx)
            } catch (e: PluginTimeout) {
                throw PluginException("$what timed out after ${timeoutMs / 1000}s")
            } catch (e: PluginCancelled) {
                // runInterruptible turns this into a CancellationException.
                throw InterruptedException("$what cancelled")
            } catch (e: RhinoException) {
                val where = e.sourceName()?.let { " ($it:${e.lineNumber()})" } ?: ""
                throw PluginException("$what failed: ${e.details()}$where", e)
            } finally {
                cx.removeThreadLocal(Guard::class.java)
                Context.exit()
            }
        }
    }
}

// ── Sandbox plumbing ───────────────────────────────────────────────────

/** Errors, not Exceptions, so a plugin's try/catch cannot swallow them. */
private class PluginTimeout : Error()
private class PluginCancelled : Error()

private class Guard(private val deadline: Long) {
    fun check() {
        if (Thread.currentThread().isInterrupted) throw PluginCancelled()
        if (System.currentTimeMillis() > deadline) throw PluginTimeout()
    }
}

private class SandboxContextFactory : ContextFactory() {
    override fun makeContext(): Context = super.makeContext().apply {
        // Interpreted mode: Android cannot load the bytecode Rhino would
        // otherwise generate, and only the interpreter honours the
        // instruction observer and stack depth limit below.
        @Suppress("DEPRECATION")
        optimizationLevel = -1
        languageVersion = Context.VERSION_ES6
        instructionObserverThreshold = 10_000
        maximumInterpreterStackDepth = 2_000
        setClassShutter { false }
    }

    override fun observeInstructionCount(cx: Context, instructionCount: Int) {
        (cx.getThreadLocal(Guard::class.java) as? Guard)?.check()
    }
}

/** A native function backed by a Kotlin lambda. */
private class NativeFn(
    scope: Scriptable,
    private val fnName: String,
    private val arity: Int,
    private val body: (cx: Context, thisObj: Scriptable?, args: Array<Any?>) -> Any?,
) : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
        body(cx, thisObj, args)

    override fun getFunctionName(): String = fnName
    override fun getArity(): Int = arity
    override fun getLength(): Int = arity
}

/** A jsoup element as seen from JS: an opaque object whose methods live on a shared prototype. */
private class JsNode(val element: Element) : ScriptableObject() {
    override fun getClassName(): String = "Node"
}

private class Bridge(
    private val scope: ScriptableObject,
    private val http: PluginHttp,
    private val log: (String) -> Unit,
) {
    private val nodeProto = NativeObject().also {
        it.parentScope = scope
        it.prototype = ScriptableObject.getObjectPrototype(scope)
    }

    fun install() {
        defineNodeMethods()
        fn(scope, "__fetch", 4) { _, _, a -> fetch(a) }
        fn(scope, "parseHtml", 2) { _, _, a ->
            wrap(Jsoup.parse(argString(a, 0) ?: "", argString(a, 1) ?: ""))
        }
        val console = NativeObject().also {
            it.parentScope = scope
            it.prototype = ScriptableObject.getObjectPrototype(scope)
        }
        for (level in listOf("log", "info", "warn", "error")) {
            fn(console, level, 1) { _, _, a ->
                log(a.joinToString(" ") { Context.toString(it) })
                Undefined.instance
            }
        }
        ScriptableObject.putProperty(scope, "console", console)

        // AES-GCM decryption for sites that ship encrypted chapter text.
        // key: UTF-8 string (16/24/32 bytes); iv, tag, data: base64.
        fn(scope, "aesGcmDecrypt", 4) { _, _, a ->
            try {
                val key = (argString(a, 0) ?: "").toByteArray(Charsets.UTF_8)
                val iv = base64Decode(argString(a, 1) ?: "")
                val tag = base64Decode(argString(a, 2) ?: "")
                val data = base64Decode(argString(a, 3) ?: "")
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    javax.crypto.Cipher.DECRYPT_MODE,
                    javax.crypto.spec.SecretKeySpec(key, "AES"),
                    javax.crypto.spec.GCMParameterSpec(tag.size * 8, iv),
                )
                String(cipher.doFinal(data + tag), Charsets.UTF_8)
            } catch (e: java.security.GeneralSecurityException) {
                throw ScriptRuntime.constructError("Error", "aesGcmDecrypt: ${e.message ?: e.javaClass.simpleName}")
            } catch (e: IllegalArgumentException) {
                throw ScriptRuntime.constructError("Error", "aesGcmDecrypt: ${e.message}")
            }
        }
    }

    private fun fn(
        target: Scriptable,
        name: String,
        arity: Int,
        body: (Context, Scriptable?, Array<Any?>) -> Any?,
    ) {
        ScriptableObject.putProperty(target, name, NativeFn(scope, name, arity, body))
    }

    private fun wrap(element: Element?): Any? = element?.let {
        JsNode(it).also { node ->
            node.parentScope = scope
            node.prototype = nodeProto
        }
    }

    private fun array(cx: Context, items: List<Any?>): Scriptable = cx.newArray(scope, items.toTypedArray())

    private fun node(thisObj: Scriptable?): Element =
        (thisObj as? JsNode)?.element ?: throw ScriptRuntime.typeError("not a Node")

    private fun selector(a: Array<Any?>): String =
        argString(a, 0) ?: throw ScriptRuntime.typeError("selector must be a string")

    /** jsoup throws on malformed selectors; surface that as a catchable JS error. */
    /**
     * jsoup reports a malformed selector as Selector.SelectorParseException
     * (an IllegalStateException) and bad arguments as IllegalArgumentException.
     * Surface both as a JS SyntaxError the plugin can catch; a raw Java
     * exception would skip the plugin's catch block.
     */
    private fun <T> jsoupCall(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw ScriptRuntime.constructError("SyntaxError", e.message ?: "bad selector")
    } catch (e: IllegalStateException) {
        throw ScriptRuntime.constructError("SyntaxError", e.message ?: "bad selector")
    }

    private fun defineNodeMethods() {
        val p = nodeProto
        fn(p, "select", 1) { cx, t, a ->
            array(cx, jsoupCall { node(t).select(selector(a)) }.map { wrap(it) })
        }
        fn(p, "selectFirst", 1) { _, t, a -> jsoupCall { wrap(node(t).selectFirst(selector(a))) } }
        fn(p, "text", 0) { _, t, _ -> node(t).text() }
        fn(p, "ownText", 0) { _, t, _ -> node(t).ownText() }
        fn(p, "wholeText", 0) { _, t, _ -> node(t).wholeText() }
        fn(p, "html", 0) { _, t, _ -> node(t).html() }
        fn(p, "outerHtml", 0) { _, t, _ -> node(t).outerHtml() }
        fn(p, "tagName", 0) { _, t, _ -> node(t).tagName() }
        fn(p, "attr", 2) { _, t, a ->
            val el = node(t)
            val name = argString(a, 0) ?: ""
            val value = argString(a, 1)
            when {
                value != null -> {
                    el.attr(name, value)
                    t
                }
                el.hasAttr(name) -> el.attr(name)
                else -> null
            }
        }
        fn(p, "absUrl", 1) { _, t, a ->
            node(t).absUrl(argString(a, 0) ?: "").ifEmpty { null }
        }
        fn(p, "hasClass", 1) { _, t, a -> node(t).hasClass(argString(a, 0) ?: "") }
        fn(p, "children", 0) { cx, t, _ -> array(cx, node(t).children().map { wrap(it) }) }
        fn(p, "parent", 0) { _, t, _ -> wrap(node(t).parent()) }
        fn(p, "next", 0) { _, t, _ -> wrap(node(t).nextElementSibling()) }
        fn(p, "prev", 0) { _, t, _ -> wrap(node(t).previousElementSibling()) }
        fn(p, "remove", 0) { _, t, _ ->
            node(t).remove()
            Undefined.instance
        }
        fn(p, "replaceWith", 1) { _, t, a ->
            val replacement = (a.getOrNull(0) as? JsNode)?.element
                ?: throw ScriptRuntime.typeError("replaceWith: argument must be a Node")
            node(t).replaceWith(replacement)
            Undefined.instance
        }
    }

    private fun fetch(a: Array<Any?>): Any {
        val url = argString(a, 0) ?: throw ScriptRuntime.typeError("fetch: url must be a string")
        val method = (argString(a, 1) ?: "GET").uppercase()
        val headers = (a.getOrNull(2) as? Scriptable)?.let { obj ->
            obj.ids.mapNotNull { id ->
                val key = id.toString()
                val v = ScriptableObject.getProperty(obj, key)
                if (v == null || Undefined.isUndefined(v) || v == Scriptable.NOT_FOUND) null
                else key to Context.toString(v)
            }.toMap()
        } ?: emptyMap()
        val body = argString(a, 3)

        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
            throw ScriptRuntime.typeError("fetch: only http(s) URLs are allowed: $url")
        }

        val cx = Context.getCurrentContext()
        (cx.getThreadLocal(Guard::class.java) as? Guard)?.check()
        // Network failures go back as { error } and the prelude throws them as
        // a JS Error named "NetworkError" (Rhino can only construct its
        // built-in error types from Java).
        val response = try {
            http.execute(PluginRequest(url, method, headers, body))
        } catch (e: InterruptedIOException) {
            if (Thread.currentThread().isInterrupted || e.message == "Canceled") throw PluginCancelled()
            return networkError(cx, "fetch $url: ${e.message ?: "timed out"}")
        } catch (e: IOException) {
            return networkError(cx, "fetch $url: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            // OkHttp rejects e.g. non-ASCII header values; make it catchable.
            throw ScriptRuntime.typeError("fetch $url: ${e.message}")
        }

        val headerObj = cx.newObject(scope)
        response.headers.forEach { (k, v) -> ScriptableObject.putProperty(headerObj, k.lowercase(), v) }
        return cx.newObject(scope).also {
            ScriptableObject.putProperty(it, "status", response.status)
            ScriptableObject.putProperty(it, "url", response.url)
            ScriptableObject.putProperty(it, "body", response.body)
            ScriptableObject.putProperty(it, "headers", headerObj)
        }
    }

    private fun networkError(cx: Context, message: String): Scriptable =
        cx.newObject(scope).also { ScriptableObject.putProperty(it, "error", message) }

    private fun argString(a: Array<Any?>, i: Int): String? {
        val v = a.getOrNull(i)
        return if (v == null || Undefined.isUndefined(v)) null else Context.toString(v)
    }
}

/**
 * Standard or URL-safe base64, padding optional. Hand-rolled because
 * java.util.Base64 needs API 26 and android.util.Base64 isn't on the JVM.
 */
private fun base64Decode(input: String): ByteArray {
    val clean = input.trim().trimEnd('=')
    val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
    var buffer = 0
    var bits = 0
    for (c in clean) {
        val v = when (c) {
            in 'A'..'Z' -> c - 'A'
            in 'a'..'z' -> c - 'a' + 26
            in '0'..'9' -> c - '0' + 52
            '+', '-' -> 62
            '/', '_' -> 63
            '\n', '\r', ' ' -> continue
            else -> throw IllegalArgumentException("invalid base64 character '$c'")
        }
        buffer = (buffer shl 6) or v
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.write((buffer shr bits) and 0xFF)
        }
    }
    return out.toByteArray()
}

/**
 * Kotlin → JS. Maps and lists become real JS objects and arrays; handing
 * them over as Java objects would run into the ClassShutter.
 */
private fun toJs(cx: Context, scope: Scriptable, value: Any?): Any? = when (value) {
    null -> null
    is Map<*, *> -> cx.newObject(scope).also { obj ->
        value.forEach { (k, v) -> ScriptableObject.putProperty(obj, k.toString(), toJs(cx, scope, v)) }
    }
    is List<*> -> cx.newArray(scope, value.map { toJs(cx, scope, it) }.toTypedArray())
    else -> Context.javaToJS(value, scope)
}

/**
 * Most values one call may hand back; shared references are counted every
 * time they're reached. About 5x the largest real result (a 10,000-chapter
 * novel is ~40,000 values), low enough to fail before exhausting memory.
 */
private const val MAX_RESULT_NODES = 250_000

private class Budget(var left: Int = MAX_RESULT_NODES)

/**
 * JS → plain Kotlin. Functions and anything nested deeper than 32 levels
 * become null. Converting runs no JS, so the instruction observer can't stop
 * it; a node budget does instead (objects sharing references could otherwise
 * take exponential time).
 */
private fun toKotlin(value: Any?, depth: Int = 0, budget: Budget = Budget()): Any? {
    if (depth > 32) return null
    if (--budget.left < 0) throw PluginException("result is too large or too deeply shared to convert")
    return when {
        value == null || Undefined.isUndefined(value) || value == Scriptable.NOT_FOUND -> null
        value is CharSequence -> value.toString()
        value is Boolean -> value
        value is Number -> value.toDouble()
        value is Function -> null
        value is NativeArray -> (0 until value.length.toInt()).map { toKotlin(value.get(it, value), depth + 1, budget) }
        value is JsNode -> value.element.outerHtml()
        value is Scriptable -> buildMap {
            for (id in value.ids) {
                val key = id.toString()
                put(key, toKotlin(ScriptableObject.getProperty(value, key), depth + 1, budget))
            }
        }
        else -> value.toString()
    }
}

/**
 * Globals written in JS because they are easier to express there.
 * fetch() is synchronous: plugins call it and use the result directly.
 */
private val PRELUDE = """
var module = { exports: {} };
var exports = module.exports;

function encodeForm(obj) {
  var parts = [];
  for (var k in obj) {
    if (Object.prototype.hasOwnProperty.call(obj, k) && obj[k] != null) {
      parts.push(encodeURIComponent(k) + "=" + encodeURIComponent(String(obj[k])));
    }
  }
  return parts.join("&");
}

function fetch(url, init) {
  init = init || {};
  var headers = {};
  var h = init.headers || {};
  for (var k in h) { if (Object.prototype.hasOwnProperty.call(h, k)) headers[k] = String(h[k]); }
  var body = init.body;
  if (body != null && typeof body === "object") {
    body = encodeForm(body);
    var hasType = false;
    for (var n in headers) { if (n.toLowerCase() === "content-type") hasType = true; }
    if (!hasType) headers["Content-Type"] = "application/x-www-form-urlencoded";
  }
  var r = __fetch(String(url), init.method || "GET", headers, body == null ? null : String(body));
  if (r.error != null) {
    var err = new Error(r.error);
    err.name = "NetworkError";
    throw err;
  }
  return {
    status: r.status,
    ok: r.status >= 200 && r.status < 300,
    url: r.url,
    headers: { get: function (name) { var v = r.headers[String(name).toLowerCase()]; return v == null ? null : v; } },
    text: function () { return r.body; },
    json: function () { return JSON.parse(r.body); }
  };
}

function escapeHtml(s) {
  return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

/** Chapter text you assembled yourself -> HTML the reader keeps paragraph for paragraph. */
function paragraphsToHtml(paragraphs) {
  return paragraphs.map(function (p) { return "<p>" + escapeHtml(p) + "</p>"; }).join("\n");
}

function fetchText(url, init) {
  var r = fetch(url, init);
  if (!r.ok) throw new Error("HTTP " + r.status + " for " + url);
  return r.text();
}

function fetchHtml(url, init) {
  var r = fetch(url, init);
  if (!r.ok) throw new Error("HTTP " + r.status + " for " + url);
  return parseHtml(r.text(), r.url);
}
""".trimIndent()
