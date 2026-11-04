// NovelFull (novelfull.com) — port of the former QuickNovel-based NovelFullProvider,
// which was AllNovelProvider (allnovel.js) with a mobile user agent for lists and search.
// Novel ids are unchanged: qnful_<base64url(novel url)>.

var MAIN_URL = "https://novelfull.com";

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

var MOBILE_USER_AGENT = { "user-agent": "Mozilla/5.0 (Linux; Android 13; SM-A205U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Mobile Safari/537.36" };

/** Prevents zoomed-in covers. */
function fullPosterFix(s) {
  return s.split("fc05345726d3e134d2f7187dc70f047b").join("4d27e0af8cf6e971f7ee3c995fc55190")
    .split("9798407846f8032e6a88fa71b2c62ce9").join("9c3d392ccc7c95187a8c6e37c6bdac6f");
}

function posterOf(img) {
  var src = img ? img.attr("src") : null;
  return fixUrlNull(src == null ? null : fullPosterFix(src));
}

module.exports = {
  id: "novelfull-com",
  name: "NovelFull",
  version: "1.0.0",
  site: MAIN_URL,
  lang: "en",

  filters: {
    tags: pairs([
      ["All", "All"], ["Shounen", "Shounen"], ["Harem", "Harem"], ["Comedy", "Comedy"],
      ["Martial Arts", "Martial Arts"], ["School Life", "School Life"], ["Mystery", "Mystery"],
      ["Shoujo", "Shoujo"], ["Romance", "Romance"], ["Sci-fi", "Sci-fi"], ["Gender Bender", "Gender Bender"],
      ["Mature", "Mature"], ["Fantasy", "Fantasy"], ["Horror", "Horror"], ["Drama", "Drama"],
      ["Tragedy", "Tragedy"], ["Supernatural", "Supernatural"], ["Ecchi", "Ecchi"], ["Xuanhuan", "Xuanhuan"],
      ["Adventure", "Adventure"], ["Action", "Action"], ["Psychological", "Psychological"], ["Xianxia", "Xianxia"],
      ["Wuxia", "Wuxia"], ["Historical", "Historical"], ["Slice of Life", "Slice of Life"], ["Seinen", "Seinen"],
      ["Lolicon", "Lolicon"], ["Adult", "Adult"], ["Josei", "Josei"], ["Sports", "Sports"], ["Smut", "Smut"],
      ["Mecha", "Mecha"], ["Yaoi", "Yaoi"], ["Shounen Ai", "Shounen Ai"], ["History", "History"],
      ["Reincarnation", "Reincarnation"], ["Martial", "Martial"], ["Game", "Game"], ["Eastern", "Eastern"],
      ["FantasyHarem", "FantasyHarem"], ["Yuri", "Yuri"], ["Magical Realism", "Magical Realism"],
      ["Isekai", "Isekai"], ["Supernatural Source:Explore", "Supernatural Source:Explore"],
      ["Video Games", "Video Games"], ["Contemporary Romance", "Contemporary Romance"], ["invayne", "invayne"],
      ["LitRPG", "LitRPG"], ["LGBT", "LGBT"], ["Comedy", "Comedy"], ["Drama", "Drama"],
      ["Shounen+Ai", "Shounen+Ai"], ["Supernatural", "Supernatural"], ["Shoujo Ai", "Shoujo Ai"],
      ["Supernatura", "Supernatura"], ["Canopy", "Canopy"]
    ]),
    orderBys: pairs([
      ["Genre", ""],
      ["Latest Release", "latest-release-novel"],
      ["Hot Novel", "hot-novel"],
      ["Completed Novel", "completed-novel"],
      ["Most Popular", "most-popular"]
    ])
  },

  browseNovels: function (page, f) {
    var url = (f.orderBy === "" && f.tag !== "All")
      ? MAIN_URL + "/genre/" + f.tag + "?page=" + page
      : MAIN_URL + "/" + (f.orderBy ? f.orderBy : "hot-novel") + "?page=" + page;
    var doc = qnGet(url, { headers: MOBILE_USER_AGENT }).document();
    var out = [];
    doc.select("div.list>div.row").forEach(function (el) {
      var a = el.selectFirst("div > div > h3.truyen-title > a");
      if (!a) return;
      var href = fixUrlNull(a.attr("href"));
      if (href == null) return;
      out.push(qnItem(a.text(), fixUrl(href), posterOf(el.selectFirst("div > div > img"))));
    });
    return out;
  },

  popularNovels: function (page) {
    return qnPopular(page);
  },

  searchNovels: function (query) {
    var doc = qnGet(MAIN_URL + "/search?keyword=" + query, { headers: MOBILE_USER_AGENT }).document();
    var out = [];
    doc.select("#list-page>.archive>.list>.row").forEach(function (h) {
      var title = h.selectFirst(">div>div>.truyen-title>a") || h.selectFirst(">div>div>.novel-title>a");
      if (!title) return;
      out.push(qnItem(title.text(), fixUrl(attr(title, "href")), posterOf(h.selectFirst(">div>div>img"))));
    });
    return out;
  },

  parseNovel: function (url) {
    var doc = qnGet(url).document();
    var nameEl = doc.selectFirst("h3.title");
    if (!nameEl) throw new Error("invalid name");

    var dataNovelId = qnAttr(doc.select("#rating"), "data-novel-id");
    var chapterDoc = qnGet(MAIN_URL + "/ajax-chapter-option?novelId=" + dataNovelId).document();
    var parsed = chapterDoc.select("select > option");
    if (parsed.length === 0) parsed = chapterDoc.select("li[data-chapter-item] > a");

    var chapters = [];
    parsed.forEach(function (c) {
      var cUrl = attr(c, "value");
      if (!cUrl.trim()) cUrl = attr(c, "href");
      if (!cUrl.trim()) return;
      chapters.push({ name: c.text() || ("chapter " + c.outerHtml()), url: fixUrl(cUrl) });
    });

    var infoDivs = doc.select("div.info > div");
    if (infoDivs.length === 0) infoDivs = doc.select("ul.info > li");
    function info(label) {
      for (var i = 0; i < infoDivs.length; i++) if (infoDivs[i].text().indexOf(label) >= 0) return infoDivs[i];
      return null;
    }
    var authorInfo = info("Author:");
    var authorA = authorInfo ? authorInfo.selectFirst("a") : null;

    var img = doc.selectFirst("div.book img");
    var src = img ? img.attr("src") : null;
    var desc = doc.selectFirst("div.desc-text");
    var statusInfo = info("Status:");
    var statusA = statusInfo ? statusInfo.selectFirst("a") : null;

    return qnNovel(nameEl.text(), chapters, {
      author: authorA ? authorA.text() : null,
      posterUrl: fixUrlNull(src != null && src.trim() ? src : (img ? img.attr("data-src") : null)),
      synopsis: desc ? desc.text() : null,
      status: qnStatus(statusA ? statusA.text() : null)
    });
  },

  parseChapter: function (url) {
    var doc = qnGet(url).document();
    var content = doc.selectFirst("#chapter-content") || doc.selectFirst("#chr-content");
    if (!content) return null;
    return content.html()
      .replace(/<iframe .* src="\/\/ad.{0,2}-ads.com\/.*" style=".*"><\/iframe>/g, " ")
      .split(" If you find any errors ( broken links, non-standard content, etc.. ), Please let us know &lt; report chapter &gt; so we can fix it as soon as possible.").join(" ")
      .split("If you find any errors ( Ads popup, ads redirect, broken links, non-standard content, etc.. ), Please let us know &lt; report chapter &gt; so we can fix it as soon as possible.").join(" ")
      .split("[Updated from F r e e w e b n o v e l. c o m]").join("");
  }
};
