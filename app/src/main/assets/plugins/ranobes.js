// Ranobes — port of the former QuickNovel-based RanobesProvider.
// Novel ids are unchanged: qrnb_<base64url(novel url)>.
//
// Long novels list only 25 chapters per page, so (as before) chapters past
// 25 are generated as "<list url>-------<index>-------<total>" and
// parseChapter works out which list page holds the real link.

var MAIN_URL = "https://ranobes.net";

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

var BASE_HEADERS = { "User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:101.0) Gecko/20100101 Firefox/101.0" };

/** Kotlin String.toInt(): throws on anything that isn't an integer. */
function toInt(s) {
  if (!/^[+-]?\d+$/.test(s)) throw new Error("NumberFormatException: For input string: \"" + s + "\"");
  return parseInt(s, 10);
}

function after(s, delim) {
  var i = s.indexOf(delim);
  return i >= 0 ? s.substring(i + delim.length) : s;
}

function posterOf(h) {
  var fig = h.selectFirst("div.cont.showcont > div > a > figure");
  if (!fig) return null;
  return fixUrlNull(attr(fig, "style").split("background-image: url(").join("").split(");").join(""));
}

function shorts(doc) {
  var out = [];
  doc.select("div.short-cont").forEach(function (h) {
    var h2 = h.selectFirst("h2.title > a");
    if (!h2) return;
    out.push(qnItem(h2.text(), fixUrl(attr(h2, "href")), posterOf(h)));
  });
  return out;
}

/** The chapter links embedded as window.__DATA__ on a chapter-list page, oldest first. */
function chapterLinks(doc) {
  var script = doc.selectFirst("script:containsData(window.__DATA__)");
  if (!script) return [];
  var data = after(script.html(), "window.__DATA__ =");
  var last = data.lastIndexOf("}");
  var json = (last >= 0 ? data.substring(0, last) : data).trim() + "}";
  return JSON.parse(json).chapters.map(function (c) { return c.link; }).reverse();
}

function chapterHtml(url) {
  var dc = qnGet(url, { headers: BASE_HEADERS }).document();
  var h1 = dc.selectFirst("#dle-content > article > div.block.story.shortstory > h1");
  var content = dc.selectFirst("#arrticle");
  return { title: h1 ? h1.html() : "", content: content };
}

module.exports = {
  id: "ranobes",
  name: "Ranobes",
  version: "1.0.0",
  site: MAIN_URL,
  lang: "en",
  rateLimitMs: 500,

  filters: {
    tags: pairs([
      ["Action", "Action"],
      ["Adult", "Adult"],
      ["Adventure", "Adventure"],
      ["Comedy", "Comedy"],
      ["Drama", "Drama"],
      ["Ecchi", "Ecchi"],
      ["Fantasy", "Fantasy"],
      ["Game", "Game"],
      ["Gender Bender", "Gender%20Bender"],
      ["Harem", "Harem"],
      ["Josei", "Josei"],
      ["Historical", "Historical"],
      ["Horror", "Horror"],
      ["Martial Arts", "Martial%20Arts"],
      ["Mature", "Mature"],
      ["Mecha", "Mecha"],
      ["Mystery", "Mystery"],
      ["Psychological", "Psychological"],
      ["Romance", "Romance"],
      ["School Life", "School%20Life"],
      ["Sci-fi", "Sci-fi"],
      ["Seinen", "Seinen"],
      ["Shoujo", "Shoujo"],
      ["Shounen", "Shounen"],
      ["Slice of Life", "Slice%20of%20Life"],
      ["Shounen Ai", "Shounen%20Ai"],
      ["Sports", "Sports"],
      ["Supernatural", "Supernatural"],
      ["Smut", "Smut"],
      ["Tragedy", "Tragedy"],
      ["Xianxia", "Xianxia"],
      ["Xuanhuan", "Xuanhuan"],
      ["Wuxia", "Wuxia"],
      ["Yaoi", "Yaoi"]
    ])
  },

  browseNovels: function (page, f) {
    var url = page <= 1 ? MAIN_URL + "/tags/genre/" + f.tag + "/" : MAIN_URL + "/tags/genre/" + f.tag + "/page/" + page + "/";
    return shorts(qnGet(url, { headers: BASE_HEADERS }).document());
  },

  popularNovels: function (page) {
    return qnPopular(page);
  },

  searchNovels: function (query) {
    var doc = qnPost(MAIN_URL + "/index.php?do=search/", {
      headers: { "referer": MAIN_URL, "content-type": "application/x-www-form-urlencoded" },
      data: {
        "do": "search", "subaction": "search", "search_start": "0", "full_search": "0",
        "result_from": "1", "story": query, "dosearch": "Start search", "dofullsearch\\": "Advanced"
      }
    }).document();
    return shorts(doc);
  },

  parseNovel: function (url) {
    var doc = qnGet(url).document();
    var h1 = doc.selectFirst("h1.title");
    if (!h1) return null;

    var chapters;
    var listLink = doc.selectFirst("a.read-continue");
    var chapterListUrl = fixUrlNull(listLink ? listLink.attr("href") : null);
    var countEl = doc.selectFirst("li[title=\"Glossary + illustrations + division of chapters, etc.\"] span");
    var digits = countEl ? countEl.text().replace(/[^0-9]/g, "") : "";
    var total = /^\d+$/.test(digits) ? parseInt(digits, 10) : 0;
    if (total > 25) {
      chapters = [];
      for (var n = 0; n < total; n++) {
        chapters.push({ name: "Chapter " + (n + 1), url: fixUrl(chapterListUrl + "-------" + n + "-------" + total) });
      }
    } else {
      chapters = doc.select("ul.chapters-scroll-list li").reverse().map(function (li, index) {
        var t = li.selectFirst("span.title");
        var a = li.selectFirst("a");
        return { name: t ? t.text() : "Chapter " + index, url: fixUrl(a ? attr(a, "href") : "") };
      });
    }

    var authors = doc.select("h1.title > span");
    var synopsis = doc.selectFirst("div.moreless");
    var src = qnAttr(doc.select("div.poster > a > img"), "src");
    var status = doc.selectFirst("li[title=Original status in: Chinese, Japanese, English, etc.] > span");
    return qnNovel(h1.ownText(), chapters, {
      author: authors.length ? authors[authors.length - 1].text() : null,
      synopsis: synopsis ? synopsis.text() : null,
      posterUrl: fixUrl(after(src, "/")),
      status: qnStatus(status ? status.text() : null)
    });
  },

  parseChapter: function (url) {
    var parts = url.split("-------");
    if (parts.length < 3) {
      var direct = chapterHtml(url);
      if (!direct.content) return null;
      return direct.title + direct.content.html();
    }

    var baseUrl = parts[0].replace(/\/$/, "");
    var bigIndex = toInt(parts[1]);
    var total = toInt(parts[2]);
    var perPage = 25;
    var totalPages = Math.trunc((total + perPage - 1) / perPage);
    var page = Math.trunc((total - 1 - bigIndex) / perPage) + 1;
    // The provider's expression parses as total % 25.
    var inLastPage = total % perPage;
    var index = page === totalPages ? bigIndex % perPage : (bigIndex - inLastPage) % perPage;

    var listDoc = qnGet(page <= 1 ? baseUrl + "/" : baseUrl + "/page/" + page + "/", { headers: BASE_HEADERS }).document();
    var chapterUrl = chapterLinks(listDoc)[index];
    if (chapterUrl == null) return null;

    var chapter = chapterHtml(chapterUrl);
    if (!chapter.content) return null;
    chapter.content.select("img").forEach(function (img) {
      var s = attr(img, "src");
      if (s.trim()) {
        var fixed = fixUrlNull(s);
        if (fixed != null) img.attr("src", fixed);
      }
    });
    return chapter.title + chapter.content.html();
  }
};
