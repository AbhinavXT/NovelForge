// MtlNovel — port of the former QuickNovel-based MtlNovelProvider.
// Novel ids are unchanged: qmtln_<base64url(novel url)>.

var MAIN_URL = "https://mtlnovel.me";

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

/** Kotlin String.toFloat() (Java Float.parseFloat): throws on anything else. */
function toFloat(s) {
  var t = s.replace(/^[\u0000- ]+|[\u0000- ]+$/g, "");
  if (!/^[+-]?(NaN|Infinity|((\d+\.?\d*|\.\d+)([eE][+-]?\d+)?)[fFdD]?)$/.test(t)) {
    throw new Error("NumberFormatException: For input string: \"" + s + "\"");
  }
  return parseFloat(t);
}

function boxes(doc, requireUrl) {
  var out = [];
  doc.select("div.novel-box").forEach(function (h) {
    var h3 = h.selectFirst("h3");
    if (!h3) return;
    var a = h.selectFirst("a");
    var cUrl = fixUrlNull(a ? a.attr("href") : null);
    if (cUrl == null) {
      if (requireUrl) return;
      cUrl = "";
    }
    var img = h.selectFirst("img");
    out.push(qnItem(h3.text(), fixUrl(cUrl), fixUrlNull(img ? img.attr("src") : null)));
  });
  return out;
}

module.exports = {
  id: "mtlnovel",
  name: "MtlNovel",
  version: "1.0.0",
  site: MAIN_URL,
  lang: "en",

  filters: {
    tags: pairs([
      ["All", ""],
      ["Action", "action"],
      ["Adult", "adult"],
      ["Adventure", "adventure"],
      ["Comedy", "comedy"],
      ["Drama", "drama"],
      ["Ecchi", "ecchi"],
      ["Erciyuan", "erciyuan"],
      ["Fan-Fiction", "fan-fiction"],
      ["Fantasy", "fantasy"],
      ["Game", "game"],
      ["Gender Bender", "gender-bender"],
      ["Harem", "harem"],
      ["Historical", "historical"],
      ["Horror", "horror"],
      ["Josei", "josei"],
      ["Martial Arts", "martial-arts"],
      ["Mature", "mature"],
      ["Mecha", "mecha"],
      ["Military", "military"],
      ["Mystery", "mystery"],
      ["Psychological", "psychological"],
      ["Romance", "romance"],
      ["School Life", "school-life"],
      ["Sci-fi", "sci-fi"],
      ["Seinen", "seinen"],
      ["Shoujo", "shoujo"],
      ["Shoujo Ai", "shoujo-ai"],
      ["Shounen", "shounen"],
      ["Shounen Ai", "shounen-ai"],
      ["Slice of Life", "slice-of-life"],
      ["Smut", "smut"],
      ["Sports", "sports"],
      ["Supernatural", "supernatural"],
      ["Tragedy", "tragedy"],
      ["Two-dimensional", "two-dimensional-novel"],
      ["Urban Life", "urban-fiction"],
      ["Wuxia", "wuxia"],
      ["Xianxia", "xianxia"],
      ["Xuanhuan", "xuanhuan"],
      ["Yaoi", "yaoi"],
      ["Yuri", "yuri"]
    ])
  },

  browseNovels: function (page, f) {
    var url = f.tag ? MAIN_URL + "/category/" + f.tag + "/?page=" + page : MAIN_URL + "/list/?page=" + page;
    return boxes(qnGet(url).document(), false);
  },

  popularNovels: function (page) {
    return qnPopular(page);
  },

  searchNovels: function (query) {
    return boxes(qnGet(MAIN_URL + "/search/?keyword=" + query).document(), true);
  },

  parseNovel: function (url) {
    var doc = qnGet(url).document();
    var h5 = doc.selectFirst("h5");
    if (!h5) return null;
    // (?<=\?slug=)([^']+) on the page source; Rhino has no lookbehind, a group does the same.
    var m = /\?slug=([^']+)/.exec(doc.outerHtml());
    var slug = m ? m[1] : null;
    var chapters = [];
    qnGet(MAIN_URL + "/ajax/chapters/?slug=" + slug).document().select("p.update-box-chapter").forEach(function (c) {
      var a = c.selectFirst("a");
      if (!a) return;
      chapters.push({ name: c.text(), url: fixUrl(attr(a, "href")) });
    });

    var lis = doc.select("div.m-card li");
    // The provider looked for a <pull-right> element (a selector typo), so author was normally empty.
    var authorEl = lis.length > 2 ? lis[2].selectFirst("pull-right") : null;
    var img = doc.selectFirst("div.content-main-image img");
    var synopsisEl = doc.selectFirst("div.m-card.text-break");
    var rating = doc.selectFirst("span.rating");
    if (rating) toFloat(rating.text().trim()); // an unparsable rating failed the load
    return qnNovel(h5.text(), chapters, {
      author: authorEl ? authorEl.text() : null,
      posterUrl: fixUrlNull(img ? img.attr("src") : null),
      synopsis: synopsisEl ? synopsisEl.ownText() : null
    });
  },

  parseChapter: function (url) {
    var content = qnGet(url).document().selectFirst("div.content.text-break");
    return content ? content.html() : null;
  }
};
