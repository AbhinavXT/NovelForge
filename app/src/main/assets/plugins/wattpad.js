// Wattpad — port of the former QuickNovel-based WattpadProvider.
// Novel ids are unchanged: qwatt_<base64url(story url)>.

var MAIN_URL = "https://www.wattpad.com";

// ── QuickNovel compatibility ─────────────────────────────────────────
// Mirrors the helpers the Kotlin provider was written against
// (data/source/nf: MainAPI, NfRequests, NfSourceAdapter), so the port can
// follow the original line by line and produce the same results.

var QN_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.93 Safari/537.36";

/**
 * app.get / app.post: default user agent, then `headers`, `cookies`
 * ("k=v;" joined by spaces), `referer`; `params` appended to the query;
 * `data` sent as a form. Like the original, a non-2xx status is not an
 * error: callers get the body either way.
 */
function qnRequest(method, url, opts) {
  opts = opts || {};
  var headers = { "user-agent": QN_USER_AGENT };
  var h = opts.headers || {};
  for (var k in h) if (Object.prototype.hasOwnProperty.call(h, k)) headers[k] = h[k];
  var c = opts.cookies || {};
  var cookie = [];
  for (var ck in c) if (Object.prototype.hasOwnProperty.call(c, ck)) cookie.push(ck + "=" + c[ck] + ";");
  if (cookie.length) headers["Cookie"] = cookie.join(" ");
  if (opts.referer) headers["referer"] = opts.referer;
  if (opts.params) {
    var q = encodeForm(opts.params);
    if (q) url += (url.indexOf("?") >= 0 ? "&" : "?") + q;
  }
  var init = { method: method, headers: headers };
  if (opts.data) init.body = opts.data;
  else if (opts.body != null) init.body = opts.body;
  else if (method === "POST") init.body = "";
  var r = fetch(url, init);
  var text = r.text();
  return {
    code: r.status,
    url: r.url,
    text: text,
    headers: r.headers,
    document: function () { return parseHtml(text, r.url); },
    json: function () { return JSON.parse(text); }
  };
}

function qnGet(url, opts) { return qnRequest("GET", url, opts); }
function qnPost(url, opts) { return qnRequest("POST", url, opts); }

function fixUrl(url) {
  if (url.indexOf("http") === 0) return url;
  if (url.indexOf("//") === 0) return "https:" + url;
  return url.charAt(0) === "/" ? MAIN_URL + url : MAIN_URL + "/" + url;
}

/** null for null or "" (not for blank), like MainAPI.fixUrlNull. */
function fixUrlNull(url) {
  return url == null || url === "" ? null : fixUrl(url);
}

/** LoadResponse.setStatus: the display name, or null when unrecognised. */
function qnStatus(s) {
  if (s == null) return null;
  switch (String(s).toLowerCase().trim()) {
    case "ongoing": case "on-going": case "on_going": case "releasing": return "Ongoing";
    case "completed": case "complete": case "done": return "Completed";
    case "hiatus": case "paused": case "pause": return "Hiatus";
    case "dropped": case "drop": return "Dropped";
    case "stub": case "stubbed": return "Stubbed";
    default: return null;
  }
}

/** newSearchResponse(name, url) as NfSourceAdapter shows it. */
function qnItem(name, url, posterUrl, latestChapter) {
  return {
    name: name,
    url: url,
    cover: posterUrl || null,
    summary: latestChapter != null ? "Latest: " + latestChapter : ""
  };
}

/** newStreamResponse(name, url, chapters) as NfSourceAdapter shows it. */
function qnNovel(name, chapters, info) {
  info = info || {};
  return {
    name: name,
    author: info.author != null ? info.author : "",
    cover: info.posterUrl || null,
    summary: info.synopsis != null ? info.synopsis : "",
    status: info.status || "Unknown",
    chapters: chapters
  };
}

/** Elements.attr(name): the first element that has it, else "". */
function qnAttr(nodes, name) {
  for (var i = 0; i < nodes.length; i++) {
    var v = nodes[i].attr(name);
    if (v != null) return v;
  }
  return "";
}

/** Elements.text(): texts joined by single spaces. */
function qnText(nodes) {
  var sb = "";
  nodes.forEach(function (n) {
    if (sb.length !== 0) sb += " ";
    sb += n.text();
  });
  return sb;
}

/** Element.attr(name): "" when missing. */
function attr(node, name) {
  var v = node ? node.attr(name) : null;
  return v == null ? "" : v;
}

/** The Browse defaults NfSourceAdapter used for getPopular: the first option of each list. */
function qnPopular(page) {
  var f = module.exports.filters || {};
  function first(list) { return list && list.length ? list[0].value : null; }
  return module.exports.browseNovels(page, { category: first(f.categories), orderBy: first(f.orderBys), tag: first(f.tags) });
}

function pairs(list) {
  return list.map(function (p) { return { label: p[0], value: p[1] }; });
}
// ─────────────────────────────────────────────────────────────────────

var LANG_HEADERS = {
  "": "en-US,en;q=0.9", "5": "es-ES,es;q=0.9", "14": "pl-PL,pl;q=0.9", "16": "ar-EG,ar;q=0.9",
  "24": "cs-CZ,cs;q=0.9", "4": "de-DE,de;q=0.9", "18": "tl-PH,tl;q=0.9", "2": "fr-FR,fr;q=0.9",
  "21": "hi-IN,hi;q=0.9", "20": "id-ID,id;q=0.9", "3": "it-IT,it;q=0.9", "17": "he-IL,he;q=0.9",
  "22": "ms-MY,ms;q=0.9", "13": "nl-NL,nl;q=0.9", "6": "pt-PT,pt;q=0.9", "15": "ro-RO,ro;q=0.9",
  "7": "ru-RU,ru;q=0.9", "23": "tr-TR,tr;q=0.9", "46": "uk-UA,uk;q=0.9", "19": "vi-VN,vi;q=0.9"
};

// The language last browsed; search uses it as Accept-Language (as before).
var currentLang = "en-US,en;q=0.9";

/** A non-null field, or the NullPointerException the Kotlin data classes would have thrown. */
function required(v, what) {
  if (v == null) throw new Error("missing " + what);
  return v;
}

module.exports = {
  id: "wattpad",
  name: "Wattpad",
  version: "1.0.0",
  site: MAIN_URL,
  lang: "en",
  rateLimitMs: 500,

  filters: {
    categories: pairs([
      ["English", ""], ["Español", "5"], ["Polski", "14"], ["العربية", "16"], ["Česky", "24"],
      ["Deutsch", "4"], ["Filipino", "18"], ["Français", "2"], ["हिन्दी", "21"], ["Bahasa Indonesia", "20"],
      ["Italiano", "3"], ["עברית", "17"], ["Bahasa Melayu", "22"], ["Nederlands", "13"], ["Português", "6"],
      ["Română", "15"], ["Русский", "7"], ["Türkçe", "23"], ["Українська", "46"], ["Tiếng Việt", "19"]
    ])
  },

  browseNovels: function (page, f) {
    var languageId = f.category != null ? f.category : "";
    currentLang = LANG_HEADERS[languageId] || "en-US,en;q=0.9";
    var url = "https://api.wattpad.com/v5/hotlist?tags=romance&" + (f.category ? "language=" + f.category + "&" : "") +
      "offset=" + (page * 20 - 20) + "&limit=20";
    var root = qnGet(url).json();
    var out = [];
    (root.stories || []).forEach(function (s) {
      if (s.title == null || s.url == null) return;
      out.push(qnItem(String(s.title), fixUrl(String(s.url)), s.cover == null ? null : String(s.cover)));
    });
    return out;
  },

  popularNovels: function (page) {
    return qnPopular(page);
  },

  searchNovels: function (query) {
    var doc = qnGet(MAIN_URL + "/search/" + query, { headers: { "accept-language": currentLang } }).document();
    var out = [];
    doc.select(".story-card").forEach(function (el) {
      var href = fixUrlNull(attr(el, "href"));
      if (href == null) return;
      var img = el.selectFirst(".story-card-data > .cover > img");
      var info = el.selectFirst(".story-card-data > .story-info");
      if (!info) return;
      var title = info.selectFirst(".sr-only");
      if (!title) return;
      out.push(qnItem(title.text(), href, fixUrlNull(img ? img.attr("src") : null)));
    });
    return out;
  },

  parseNovel: function (url) {
    var text = qnGet(url).text;
    var script = required(/<script>.*?window._+remix.*?<\/script>/.exec(text), "remix script")[0];
    var state = JSON.parse(required(/\{.*\}/.exec(script), "remix json")[0]).state.loaderData;
    var keys = Object.keys(state);
    // The story's route is the last loader (key order is preserved).
    var route = state[keys[keys.length - 1]];
    var novel = typeof route === "string" ? JSON.parse(route) : route;
    var story = required(novel.story, "story");

    var chapters = (story.parts || []).map(function (p) {
      return { name: String(required(p.title, "part title")), url: fixUrl(String(required(p.url, "part url"))) };
    });
    return qnNovel(String(required(novel.title, "title")), chapters, {
      author: story.author && story.author.username != null ? String(story.author.username) : null,
      posterUrl: story.cover == null ? null : String(story.cover),
      synopsis: story.description == null ? null : String(story.description)
    });
  },

  parseChapter: function (url) {
    var m = /window\.prefetched\s*=\s*(\{.*\})/.exec(qnGet(url).text);
    if (!m) throw new Error("Failed to get Data");
    var parsed = JSON.parse(m[1]);
    var data = parsed[Object.keys(parsed)[0]].data;
    var html = "";
    for (var page = 1; page <= data.pages; page++) html += qnGet(data.text_url.text + page).text;
    return html;
  }
};
