// SonicMTL — port of the former QuickNovel-based SonicMTLProvider.
// WordPress "wp-manga" (Madara) site; comics are filtered out.
// Novel ids are unchanged: qsonic_<base64url(novel url)>.

var MAIN_URL = "https://www.sonicmtl.com";

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

/** data-src when present, else src. Not made absolute (the provider didn't either). */
function image(el) {
  if (!el) return null;
  var ds = el.attr("data-src");
  return ds != null ? ds : attr(el, "src");
}

/** Java \s+ -> " ", trimmed. */
function clean(s) {
  return s.replace(/[ \t\n\x0B\f\r]+/g, " ").trim();
}

function isComic(name) {
  return name.toLowerCase().indexOf("comic") >= 0;
}

var ADS = ["myboxnovel.com", "BoxNovel.Com", "Read latest Chapters at", "If you have problems with this website"];

module.exports = {
  id: "sonicmtl",
  name: "SonicMTL",
  version: "1.0.0",
  site: MAIN_URL,
  lang: "en",

  filters: {
    tags: pairs([
      ["All", ""],
      ["Action", "action"],
      ["Adventure", "adventure"],
      ["Comedy", "comedy"],
      ["Drama", "genre"],
      ["Ecchi", "ecchi"],
      ["Fantasy", "fantasy"],
      ["Harem", "harem"],
      ["Josei", "josei"],
      ["Martial Arts", "martial-arts"],
      ["Gender Bender", "gender-bender"],
      ["Historical", "historical"],
      ["Horror", "horror"],
      ["Mature", "mature"],
      ["Mecha", "mecha"],
      ["Mystery", "mystery"],
      ["Psychological", "psychological"],
      ["Romance", "romance"],
      ["School Life", "school-life"],
      ["Sci-fi", "sci-fi"],
      ["Seinen", "seinen"],
      ["Shoujo", "shoujo"],
      ["Shounen", "shounen"],
      ["Slice of Life", "slice-of-life"],
      ["Sports", "sports"],
      ["Supernatural", "supernatural"],
      ["Tragedy", "tragedy"],
      ["Wuxia", "wuxia"],
      ["Xianxia", "xianxia"],
      ["Xuanhuan", "xuanhuan"]
    ]),
    orderBys: pairs([
      ["New", "new-manga"], ["Most Views", "views"], ["Trending", "trending"],
      ["Rating", "rating"], ["A-Z", "alphabet"], ["Latest", "latest"]
    ])
  },

  browseNovels: function (page, f) {
    var order = f.tag ? "novel-genre/" + f.tag : "novel";
    var query = f.orderBy ? "?m_orderby=" + f.orderBy : "";
    var out = [];
    qnGet(MAIN_URL + "/" + order + "/page/" + page + "/" + query).document().select("div.page-item-detail").forEach(function (h) {
      var a = h.selectFirst("div.item-thumb > a");
      if (!a) return;
      var name = attr(a, "title");
      if (isComic(name)) return;
      out.push(qnItem(name, fixUrl(attr(a, "href")), image(a.selectFirst("img"))));
    });
    return out;
  },

  popularNovels: function (page) {
    return qnPopular(page);
  },

  searchNovels: function (query) {
    var out = [];
    qnGet(MAIN_URL + "/?s=" + query + "&post_type=wp-manga").document().select("div.c-tabs-item__content").forEach(function (h) {
      var a = h.selectFirst("div.post-title h3 a");
      if (!a) return;
      var name = a.text();
      if (isComic(name)) return;
      out.push(qnItem(name, fixUrl(attr(a, "href")), image(h.selectFirst("div.tab-thumb img"))));
    });
    return out;
  },

  parseNovel: function (url) {
    var doc = qnGet(url).document();
    var h1 = doc.selectFirst("div.post-title > h1");
    if (!h1) return null;

    var chapters = [];
    qnPost(url.replace(/\/$/, "") + "/ajax/chapters/").document().select("li.wp-manga-chapter").forEach(function (c) {
      var a = c.selectFirst("a");
      if (!a) return;
      chapters.push({ name: clean(a.text()), url: fixUrl(attr(a, "href")) });
    });
    chapters.reverse();

    var author = doc.selectFirst("div.author-content > a");
    var mainLower = MAIN_URL.toLowerCase();
    var synopsis = doc.select(".summary__content p, #editdescription p, .j_synopsis p")
      .map(function (p) { return p.text().trim(); })
      .filter(function (t) { return t.length > 0 && t.toLowerCase().indexOf(mainLower) < 0; })
      .join("\n\n");
    var statuses = doc.select("div.post-status .summary-content");
    return qnNovel(clean(h1.text()), chapters, {
      author: author ? author.text() : null,
      synopsis: synopsis,
      status: qnStatus(statuses.length ? statuses[statuses.length - 1].text() : null),
      posterUrl: image(doc.selectFirst("div.summary_image img"))
    });
  },

  parseChapter: function (url) {
    var content = qnGet(url).document().selectFirst("div.text-left");
    if (!content || !content.text().trim()) return null;
    // Paragraphs with site spam.
    content.select("p").forEach(function (p) {
      var t = p.text().toLowerCase();
      if (ADS.some(function (ad) { return t.indexOf(ad.toLowerCase()) >= 0; })) p.remove();
    });
    return content.html();
  }
};
