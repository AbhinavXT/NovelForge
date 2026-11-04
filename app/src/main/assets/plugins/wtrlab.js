// WTR-Lab — port of the former built-in WtrLabSource.
// Machine-translated CN/KR web novels.
//
// WTR-Lab is a Next.js app, so this reads the embedded __NEXT_DATA__ JSON
// and the site's own JSON APIs instead of scraping rendered HTML:
//   search/browse   GET  /en/novel-finder?...   (props.pageProps.series,
//                        fallback /_next/data/{buildId}/en/novel-finder.json)
//   novel details   GET  /en/novel/{rawId}/{slug}  (props.pageProps.serie.serie_data)
//   chapter list    GET  /api/chapters/{rawId}?start=A&end=B   (250 per batch)
//   chapter content POST /api/reader/get  {translate:"ai"|"web", ...}
//                   -> content_url -> GET that for the chapter body
//
// Chapters without a cached translation come back as an AES-256-GCM
// encrypted ("arr:"/"str:") payload of the raw text; the key is in one of
// the site's script bundles and the site translates client-side through
// Google's public translate endpoint. This plugin does the same.
//
// Keeps the old ids so existing libraries keep working:
//   novel   wtr_<rawId>~<slug>
//   chapter wtr_<rawId>_<order>

var SITE = "https://wtr-lab.com";
var USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

// Sentry tracing headers some API routes expect, harvested from the last
// HTML page's <meta> tags (as the site itself sends them).
var baggage = "";
var sentryTrace = "";
// AES key for encrypted chapters; found once, refreshed if decryption fails.
var cachedEncKey = null;

// ── org.json semantics, so values come out exactly as before ──────────

/** JSONObject.optString(name, fallback): JSON null becomes "null". */
function optString(obj, key, fallback) {
  if (obj == null || typeof obj !== "object" || !(key in obj)) return fallback;
  var v = obj[key];
  if (v === null) return "null";
  if (typeof v === "object") return JSON.stringify(v);
  return String(v);
}

/** JSONObject.optInt/optLong(name, fallback): numbers and numeric strings. */
function optNumber(obj, key, fallback) {
  if (obj == null || typeof obj !== "object" || !(key in obj)) return fallback;
  var v = obj[key];
  if (typeof v === "number") return Math.trunc ? Math.trunc(v) : (v < 0 ? Math.ceil(v) : Math.floor(v));
  if (typeof v === "string" && v.trim() !== "" && !isNaN(Number(v))) {
    var n = Number(v);
    return n < 0 ? Math.ceil(n) : Math.floor(n);
  }
  return fallback;
}

function optObject(obj, key) {
  var v = obj != null && typeof obj === "object" ? obj[key] : undefined;
  return v != null && typeof v === "object" && !Array.isArray(v) ? v : null;
}

function optArray(obj, key) {
  var v = obj != null && typeof obj === "object" ? obj[key] : undefined;
  return Array.isArray(v) ? v : null;
}

/** JSONObject.optBoolean(name, fallback) */
function optBoolean(obj, key, fallback) {
  var v = obj[key];
  if (typeof v === "boolean") return v;
  if (typeof v === "string") {
    if (v.toLowerCase() === "true") return true;
    if (v.toLowerCase() === "false") return false;
  }
  return fallback;
}

/** JSONArray.optString(index, fallback) */
function itemString(arr, i, fallback) {
  if (i >= arr.length) return fallback;
  var v = arr[i];
  if (v === null) return "null";
  if (typeof v === "object") return JSON.stringify(v);
  return String(v);
}

// ── HTTP ──────────────────────────────────────────────────────────────

function fetchPage(url) {
  try {
    var r = fetch(url, {
      headers: {
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.5",
        "Referer": SITE + "/en"
      }
    });
    if (!r.ok) {
      console.error("HTML request failed: " + r.status + " for " + url);
      return null;
    }
    var body = r.text();
    var b = /<meta name="baggage" content="([^"]*)"/.exec(body);
    if (b) baggage = b[1];
    var t = /<meta name="sentry-trace" content="([^"]*)"/.exec(body);
    if (t) sentryTrace = t[1];
    return body;
  } catch (e) {
    console.error("fetchHtml error for " + url + ": " + e.message);
    return null;
  }
}

function fetchJson(url) {
  try {
    var headers = { "User-Agent": USER_AGENT, "Accept": "application/json", "Referer": SITE + "/en" };
    if (baggage) headers["baggage"] = baggage;
    if (sentryTrace) headers["sentry-trace"] = sentryTrace;
    var r = fetch(url, { headers: headers });
    if (!r.ok) {
      console.error("JSON request failed: " + r.status + " for " + url);
      return null;
    }
    var v = JSON.parse(r.text());
    return v != null && typeof v === "object" && !Array.isArray(v) ? v : null;
  } catch (e) {
    console.error("fetchJson error for " + url + ": " + e.message);
    return null;
  }
}

/** reader/get can answer with a JSON error body and a non-2xx status; read it anyway. */
function postJson(url, payload, referer) {
  try {
    var r = fetch(url, {
      method: "POST",
      headers: { "User-Agent": USER_AGENT, "Accept": "application/json", "Referer": referer, "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    var text = r.text();
    if (!text || !text.trim()) {
      console.error("POST empty body: " + r.status + " for " + url);
      return null;
    }
    var v = JSON.parse(text);
    return v != null && typeof v === "object" && !Array.isArray(v) ? v : null;
  } catch (e) {
    console.error("postJson error for " + url + ": " + e.message);
    return null;
  }
}

function fetchContent(url, referer) {
  try {
    var r = fetch(url, { headers: { "User-Agent": USER_AGENT, "Accept": "application/json", "Referer": referer } });
    if (!r.ok) {
      console.error("content request failed: " + r.status + " for " + url);
      return null;
    }
    var v = JSON.parse(r.text());
    return v != null && typeof v === "object" && !Array.isArray(v) ? v : null;
  } catch (e) {
    console.error("content error for " + url + ": " + e.message);
    return null;
  }
}

function extractNextData(html) {
  try {
    var script = parseHtml(html).selectFirst("script#__NEXT_DATA__");
    if (!script) return null;
    return JSON.parse(script.html());
  } catch (e) {
    console.error("extractNextData parse error: " + e.message);
    return null;
  }
}

/** java.net.URLEncoder.encode(s, "UTF-8") */
function urlEncode(s) {
  return encodeURIComponent(s).replace(/%20/g, "+").replace(/[!'()~]/g, function (c) {
    return "%" + c.charCodeAt(0).toString(16).toUpperCase();
  });
}

// ── Lists ─────────────────────────────────────────────────────────────

function parseSeries(series) {
  var novels = [];
  var seen = {};
  series.forEach(function (serie) {
    if (serie == null || typeof serie !== "object" || Array.isArray(serie)) return;
    var rawId = optNumber(serie, "raw_id", -1);
    var slug = optString(serie, "slug", "");
    if (rawId <= 0 || !slug.trim() || seen[rawId]) return;
    seen[rawId] = true;
    var data = optObject(serie, "data");
    if (!data) return;
    var title = optString(data, "title", "").trim();
    if (!title) return;
    var image = optString(data, "image", "");
    novels.push({
      id: "wtr_" + rawId + "~" + slug,
      name: title,
      url: SITE + "/en/novel/" + rawId + "/" + slug,
      author: optString(data, "author", "").trim(),
      cover: image.trim() ? image : null,
      summary: optString(data, "description", "").trim()
    });
  });
  return novels;
}

function fetchFinder(params) {
  var html = fetchPage(SITE + "/en/novel-finder?" + params);
  if (html == null) return [];
  var nextData = extractNextData(html);
  if (!nextData) return [];

  var series = optArray(optObject(optObject(nextData, "props"), "pageProps"), "series");
  if (series) return parseSeries(series);

  // Fallback: the JSON data route for the live buildId (it changes with every deploy).
  var buildId = optString(nextData, "buildId", "");
  if (!buildId.trim()) return [];
  var json = fetchJson(SITE + "/_next/data/" + buildId + "/en/novel-finder.json?" + params);
  var fromRoute = json ? optArray(optObject(json, "pageProps"), "series") : null;
  return fromRoute ? parseSeries(fromRoute) : [];
}

// ── Encrypted chapters ────────────────────────────────────────────────

/** The key is a string literal in a head script: new TextEncoder().encode("<32 chars>..."). */
function findEncryptionKey(chapterUrl) {
  var html = fetchPage(chapterUrl);
  if (html == null) return null;
  var doc = parseHtml(html, chapterUrl);
  var marker = 'TextEncoder().encode("';
  var srcs = [];
  doc.select("head script[src]").forEach(function (s) {
    var src = s.absUrl("src");
    if (src && srcs.indexOf(src) < 0) srcs.push(src);
  });
  for (var i = 0; i < srcs.length; i++) {
    var script;
    try {
      var r = fetch(srcs[i], { headers: { "User-Agent": USER_AGENT } });
      if (!r.ok) continue;
      script = r.text();
    } catch (e) {
      continue;
    }
    var index = script.indexOf(marker);
    if (index >= 0) {
      var start = index + marker.length;
      if (start + 32 <= script.length) return script.substring(start, start + 32);
    }
  }
  console.error("Encryption key not found in " + srcs.length + " scripts");
  return null;
}

/** "iv:tag:ciphertext", all base64. */
function decrypt(payload, key) {
  var parts = payload.split(":");
  if (parts.length !== 3) throw new Error("Invalid encrypted data format");
  return aesGcmDecrypt(key.substring(0, 32), parts[0], parts[1], parts[2]);
}

/** Raw zh-CN lines through Google's public web translate endpoint, as the site does. */
function translateLines(lines) {
  var out = [];
  for (var start = 0; start < lines.length; start += 100) {
    var contained = lines.slice(start, start + 100).map(function (line, i) { return "<a i=" + i + ">" + line + "</a>"; });
    var body = "[[" + JSON.stringify(contained) + ",\"zh-CN\",\"en\"],\"te_lib\"]";
    var r;
    try {
      r = fetch("https://translate-pa.googleapis.com/v1/translateHtml", {
        method: "POST",
        headers: {
          "Content-Type": "application/json+protobuf",
          // Public web API key of translate.google.com's embedded widget (te_lib); not a secret.
          "X-Goog-API-Key": "AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520",
          "Referer": SITE + "/"
        },
        body: body
      });
    } catch (e) {
      console.error("translateLines error: " + e.message);
      return null;
    }
    var text = r.text();
    if (!r.ok || !text || !text.trim()) {
      console.error("Translate failed: " + r.status);
      return null;
    }
    var parsed = JSON.parse(text);
    var translated = Array.isArray(parsed) && Array.isArray(parsed[0]) ? parsed[0] : null;
    if (!translated) return null;
    for (var i = 0; i < translated.length; i++) out.push(itemString(translated, i, ""));
  }
  return out;
}

function decryptAndTranslate(encrypted, chapterUrl) {
  var key = cachedEncKey || (cachedEncKey = findEncryptionKey(chapterUrl));
  if (!key) {
    console.error("Encryption key not found; cannot decode raw chapter");
    return null;
  }
  var isArray = encrypted.indexOf("arr:") === 0;
  var payload = encrypted.substring(encrypted.indexOf(":") + 1);
  var decrypted;
  try {
    decrypted = decrypt(payload, key);
  } catch (e) {
    // The key may have rotated with a site deploy: refresh once.
    cachedEncKey = null;
    var fresh = findEncryptionKey(chapterUrl);
    if (!fresh) return null;
    cachedEncKey = fresh;
    try {
      decrypted = decrypt(payload, fresh);
    } catch (e2) {
      console.error("Decrypt failed after key refresh: " + e2.message);
      return null;
    }
  }
  var rawLines;
  if (isArray) {
    var arr = JSON.parse(decrypted);
    rawLines = [];
    for (var i = 0; i < arr.length; i++) rawLines.push(itemString(arr, i, ""));
  } else {
    rawLines = [decrypted];
  }
  return translateLines(rawLines);
}

// ── Plugin ────────────────────────────────────────────────────────────

var GENRES = [
  ["Action", "1"], ["Adult", "2"], ["Adventure", "3"], ["Anime", "4"],
  ["Arts", "5"], ["Comedy", "6"], ["Drama", "7"], ["Eastern", "8"],
  ["Fan-fiction", "9"], ["Fantasy", "10"], ["Game", "11"],
  ["Gender-bender", "12"], ["Harem", "13"], ["Historical", "14"],
  ["Horror", "15"], ["Isekai", "16"], ["Josei", "17"], ["LGBT", "18"],
  ["Magic", "19"], ["Magical Realism", "20"], ["Manhua", "21"],
  ["Martial Arts", "22"], ["Mature", "23"], ["Mecha", "24"],
  ["Military", "25"], ["Modern Life", "26"], ["Movies", "27"],
  ["Mystery", "28"], ["Other", "29"], ["Psychological", "30"],
  ["Realistic Fiction", "31"], ["Reincarnation", "32"],
  ["Romance", "33"], ["School Life", "34"], ["Sci-fi", "35"],
  ["Seinen", "36"], ["Shoujo", "37"], ["Shoujo-ai", "38"],
  ["Shounen", "39"], ["Shounen-ai", "40"], ["Slice of Life", "41"],
  ["Smut", "42"], ["Sports", "43"], ["Supernatural", "44"],
  ["System", "45"], ["Tragedy", "46"]
];

module.exports = {
  id: "wtrlab",
  name: "WTR-Lab",
  version: "1.1.0",
  site: SITE,
  lang: "en",
  // The built-in source kept translator/editor credit lines.
  stripCredits: false,

  // Finder API params: status / orderBy / genre ids.
  filters: {
    categories: [
      { label: "Ongoing", value: "ongoing" },
      { label: "Completed", value: "completed" }
    ],
    orderBys: [
      { label: "Weekly Rank", value: "weekly_rank" },
      { label: "Views", value: "view" },
      { label: "Readers", value: "reader" },
      { label: "Date", value: "date" },
      { label: "Chapters", value: "chapter" },
      { label: "Name", value: "name" }
    ],
    tags: GENRES.map(function (g) { return { label: g[0], value: g[1] }; })
  },

  browseNovels: function (page, f) {
    var params = "orderBy=" + (f.orderBy || "weekly_rank") + "&order=desc&page=" + page;
    if (f.category) params += "&status=" + f.category;
    if (f.tag) params += "&genre=" + f.tag;
    return fetchFinder(params);
  },

  popularNovels: function (page) {
    return module.exports.browseNovels(page, { category: null, orderBy: null, tag: null });
  },

  searchNovels: function (query) {
    return fetchFinder("text=" + urlEncode(query) + "&orderBy=view&order=desc&page=1");
  },

  parseNovel: function (url) {
    var html = fetchPage(url);
    if (html == null) return null;
    var serie = optObject(optObject(optObject(optObject(extractNextData(html), "props"), "pageProps"), "serie"), "serie_data");
    if (!serie) {
      console.error("No serie_data in __NEXT_DATA__ for " + url);
      return null;
    }
    var rawId = optNumber(serie, "raw_id", -1);
    var slug = optString(serie, "slug", "");
    if (rawId <= 0 || !slug.trim()) {
      console.error("Missing raw_id/slug for " + url);
      return null;
    }
    var data = optObject(serie, "data") || {};
    var statusCode = optNumber(serie, "status", -1);

    // chapter_count can lag reality, so probe at least one batch.
    var target = Math.max(optNumber(serie, "chapter_count", 0), 1);
    var all = [];
    for (var start = 1; start <= target; start += 250) {
      var end = Math.min(start + 249, target);
      var json = fetchJson(SITE + "/api/chapters/" + rawId + "?start=" + start + "&end=" + end);
      var batch = json ? (optArray(json, "chapters") || optArray(optObject(json, "data"), "chapters")) : null;
      if (!batch) {
        console.error("Chapter batch " + start + "-" + end + " failed for " + rawId);
        break;
      }
      batch.forEach(function (ch) {
        if (ch == null || typeof ch !== "object" || Array.isArray(ch)) return;
        var order = optNumber(ch, "order", -1);
        if (order <= 0) return;
        // "title" is the translated title, "name" the raw one.
        var title = optString(ch, "title", "").trim() || optString(ch, "name", "").trim() || ("Chapter " + order);
        all.push({ id: "wtr_" + rawId + "_" + order, number: order, name: title, url: SITE + "/en/novel/" + rawId + "/" + slug + "/chapter-" + order });
      });
      if (batch.length < 250) break;
    }
    var seen = {};
    var chapters = all.filter(function (c) {
      if (seen[c.number]) return false;
      seen[c.number] = true;
      return true;
    }).sort(function (a, b) { return a.number - b.number; });

    var image = optString(data, "image", "");
    return {
      id: "wtr_" + rawId + "~" + slug,
      name: optString(data, "title", "").trim(),
      author: optString(data, "author", "").trim(),
      cover: image.trim() ? image : null,
      summary: optString(data, "description", "").trim(),
      status: statusCode === 0 ? "Ongoing" : statusCode === 1 ? "Completed" : "Unknown",
      chapters: chapters
    };
  },

  parseChapter: function (url) {
    // Current (/en/novel/{id}/{slug}) and legacy (/en/serie-{id}/{slug}) URL shapes.
    var m = /(?:novel\/|serie-)(\d+)\/[^\/]+\/chapter-(\d+)/.exec(url);
    if (!m) {
      console.error("Cannot parse chapter URL: " + url);
      return null;
    }
    var rawId = parseInt(m[1], 10);
    var chapterNo = parseInt(m[2], 10);

    // Premium AI translation first (works when cached/unlocked), then the free web MTL.
    var reader = null;
    var modes = ["ai", "web"];
    for (var i = 0; i < modes.length; i++) {
      var json = postJson(SITE + "/api/reader/get", {
        translate: modes[i], language: "en", raw_id: rawId, chapter_no: chapterNo, retry: false, force_retry: false
      }, url);
      if (json != null && optBoolean(json, "success", true) && !("error" in json)) {
        reader = json;
        break;
      }
    }
    if (!reader) {
      console.error("reader/get failed for " + url);
      return null;
    }
    // The v2 reader API answers with a content_url instead of the chapter
    // itself; that URL returns the old { data: { data: { body } } } shape.
    if (!optObject(reader, "data") && typeof reader.content_url === "string") {
      reader = fetchContent(SITE + reader.content_url, url);
      if (!reader) return null;
    }
    var chapterData = optObject(optObject(reader, "data"), "data");
    if (!chapterData) return null;

    // Paragraphs carry ※N⛬ placeholders for per-novel glossary terms.
    var glossary = [];
    var terms = optArray(optObject(chapterData, "glossary_data"), "terms");
    if (terms) {
      terms.forEach(function (t) { glossary.push(Array.isArray(t) ? itemString(t, 0, "") : ""); });
    }

    if (!("body" in chapterData)) return null;
    var body = chapterData.body;
    var paragraphs;
    if (Array.isArray(body)) {
      paragraphs = [];
      for (var j = 0; j < body.length; j++) paragraphs.push(itemString(body, j, ""));
    } else if (typeof body === "string") {
      if (body.indexOf("arr:") === 0 || body.indexOf("str:") === 0) {
        paragraphs = decryptAndTranslate(body, url);
        if (!paragraphs) return null;
      } else {
        paragraphs = [body];
      }
    } else {
      return null;
    }

    var text = paragraphs.map(function (p) {
      var resolved = glossary.length > 0
        ? p.replace(/(?:wtr-lab[ \t\n\x0B\f\r]+)?※([0-9]+)[⛬〓]/g, function (whole, n) {
            var term = glossary[parseInt(n, 10)];
            return term !== undefined ? term : whole;
          })
        : p;
      // Strip residual markup (the translate fallback wraps lines in <a i=N>).
      return parseHtml(resolved).text().trim();
    }).filter(function (s) { return s.length > 0; });

    return text.length === 0 ? null : paragraphsToHtml(text);
  }
};
