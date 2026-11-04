// WuxiaBox — port of the former QuickNovel-based WuxiaBoxProvider.
// FanMTL (fanmtl.js) runs on the same site engine.
// Novel ids are unchanged: qwbox_<base64url(novel url)>.

var MAIN_URL = "https://www.wuxiabox.com";

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

/** java.net.URLEncoder.encode(s, "UTF-8") */
function urlEncode(s) {
  return encodeURIComponent(s).replace(/%20/g, "+").replace(/[!'()~]/g, function (c) {
    return "%" + c.charCodeAt(0).toString(16).toUpperCase();
  });
}

function coverOf(el) {
  var img = el.selectFirst("img");
  if (!img) return null;
  var dataSrc = attr(img, "data-src");
  return dataSrc.trim() ? dataSrc : attr(img, "src");
}

module.exports = {
  id: "wuxiabox",
  name: "WuxiaBox",
  version: "1.0.0",
  site: MAIN_URL,
  lang: "en",

  filters: {
    categories: pairs([["All", "all"], ["Completed", "Completed"], ["Ongoing", "Ongoing"]]),
    tags: pairs([
      ["All", "all"], ["Fan-Fic", "fan-fiction"], ["Faloo", "faloo"], ["Action", "action"],
      ["Adventure", "adventure"], ["Comedy", "comedy"], ["CRomance", "contemporary-romance"],
      ["Drama", "drama"], ["Eastern Fantasy", "eastern-fantasy"], ["Fantasy", "fantasy"],
      ["Fantasy Romance", "fantasy-romance"], ["Gender Bender", "gender-bender"], ["Harem", "harem"],
      ["Historical", "historical"], ["Horror", "horror"], ["Josei", "josei"], ["Lolicon", "lolicon"],
      ["Magical Realism", "magical-realism"], ["Martial Arts", "martial-arts"], ["Mecha", "mecha"],
      ["Mystery", "mystery"], ["Psychological", "psychological"], ["Romance", "romance"],
      ["School Life", "school-life"], ["Sci-fi", "sci-fi"], ["Seinen", "seinen"], ["Shoujo", "shoujo"],
      ["Shounen", "shounen"], ["Shounen Ai", "shounen-ai"], ["Slice of Life", "slice-of-life"],
      ["Sports", "sports"], ["Supernatural", "supernatural"], ["Tragedy", "tragedy"],
      ["Video Games", "video-games"], ["Wuxia", "wuxia"], ["Xianxia", "xianxia"], ["Xuanhuan", "xuanhuan"],
      ["Yaoi", "yaoi"], ["Two-D", "two-dimensional"], ["Erciyuan", "erciyuan"], ["Game", "game"],
      ["Military", "military"], ["Urban Life", "urban-life"], ["Yuri", "yuri"], ["Chinese", "chinese"],
      ["Japanese", "japanese"], ["Hentai", "hentai"], ["Isekai", "isekai"], ["Magic", "magic"],
      ["Shoujo Ai", "shoujo-ai"], ["Urban", "urban"], ["VR", "virtual-reality"],
      ["Wuxia Xianxia", "wuxia_xianxia"], ["Official", "official_circles"], ["Sci-fi", "science_fiction"],
      ["Thriller", "suspense_thriller"], ["Travel Through Time", "travel_through_time"]
    ]),
    orderBys: pairs([["New", "newstime"], ["Popular", "onclick"], ["Updates", "lastdotime"]])
  },

  browseNovels: function (page, f) {
    var url = MAIN_URL + "/list/" + f.tag + "/" + f.category + "-" + f.orderBy + "-" + page + ".html";
    var doc = qnGet(url).document();
    var out = [];
    doc.select("li.novel-item").forEach(function (el) {
      var a = el.selectFirst("a[title]");
      if (!a) return;
      var t = attr(a, "title");
      var h4 = a.selectFirst("h4.novel-title");
      var title = t.trim() ? t : (h4 ? h4.text() : null);
      if (title == null) return;
      out.push(qnItem(title, fixUrl(attr(a, "href")), fixUrlNull(coverOf(el))));
    });
    return out;
  },

  popularNovels: function (page) {
    return qnPopular(page);
  },

  searchNovels: function (query) {
    var response = qnPost(MAIN_URL + "/e/search/index.php", {
      data: { show: "title", tempid: "1", tbname: "news", keyboard: urlEncode(query) }
    });
    // The server redirects to result/?searchid=NNN.
    var m = /searchid=(\d+)/.exec(response.url);
    var searchId = m ? m[1] : null;

    var results = [];
    for (var page = 0; ; page++) {
      var doc = qnGet(MAIN_URL + "/e/search/result/index.php?page=" + page + "&searchid=" + searchId).document();
      var pageResults = [];
      doc.select("li.novel-item").forEach(function (el) {
        var node = el.selectFirst("a[title]");
        if (!node) return;
        var href = fixUrl(attr(node, "href"));
        var t = attr(node, "title");
        var h4 = el.selectFirst("h4.novel-title");
        var title = t.trim() ? t : (h4 ? h4.text() : null);
        if (title == null) return;
        pageResults.push(qnItem(title, href, fixUrlNull(coverOf(el))));
      });
      if (pageResults.length === 0) break;
      results = results.concat(pageResults);
    }
    return results;
  },

  parseNovel: function (url) {
    var doc = qnGet(url).document();
    var titleEl = doc.selectFirst("h1.novel-title");
    if (!titleEl) return null;
    var authorEl = doc.selectFirst("div.author [itemprop=author]");
    var meta = doc.selectFirst("meta[itemprop=description]");
    var summaryEl = doc.selectFirst("div.summary, div.desc, #intro");
    var synopsis = meta ? attr(meta, "content") : summaryEl ? summaryEl.text() : "";
    var coverEl = doc.selectFirst("div.fixed-img img");
    var cover = null;
    if (coverEl) {
      var ds = attr(coverEl, "data-src");
      cover = ds.trim() ? ds : attr(coverEl, "src");
    }
    var statusEl = doc.selectFirst("div.header-stats strong:matches(Ongoing|Completed)");

    var file = url.substring(url.lastIndexOf("/") + 1);
    var bookId = file.indexOf(".html") >= 0 ? file.substring(0, file.indexOf(".html")) : file;

    var chapters = [];
    for (var page = 0; ; page++) {
      var listDoc = qnGet(MAIN_URL + "/e/extend/fy.php?page=" + page + "&wjm=" + bookId + "&X-Requested-With=XMLHttpRequest&_=" + Date.now()).document();
      var items = listDoc.select("ul.chapter-list li");
      if (items.length === 0) break;
      items.forEach(function (li) {
        var link = li.selectFirst("a");
        if (!link) return;
        var strong = link.selectFirst("strong.chapter-title");
        chapters.push({ name: strong ? strong.text().trim() : "", url: fixUrl(attr(link, "href")) });
      });
    }

    return qnNovel(titleEl.text(), chapters, {
      author: authorEl ? authorEl.text() : null,
      posterUrl: fixUrlNull(cover),
      synopsis: synopsis,
      status: qnStatus(statusEl ? statusEl.text() : null)
    });
  },

  parseChapter: function (url) {
    var doc = qnGet(fixUrl(url)).document();
    var content = doc.selectFirst("div.chapter-content");
    if (!content) return null;
    // Ad placeholders.
    content.select("img[src*=disable-blocker.jpg], script, div[align=center]").forEach(function (n) { n.remove(); });
    return content.html();
  }
};
