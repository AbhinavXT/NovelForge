// NoBadNovel — port of the former QuickNovel-based NoBadNovelProvider.
// Novel ids are unchanged: qnbn_<base64url(novel url)>.

var MAIN_URL = "https://www.nobadnovel.com";

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

function parseCards(doc) {
  var out = [];
  doc.select(".grid > div").forEach(function (card) {
    var titleEl = card.selectFirst("h4 a");
    if (!titleEl) return;
    var title = titleEl.text().trim();
    if (!title) return;
    var link = card.selectFirst("a[href*=/series/]");
    if (!link) return;
    var img = card.selectFirst("img[src]");
    out.push(qnItem(title, fixUrl(attr(link, "href")), fixUrlNull(img ? img.attr("src") : null)));
  });
  return out;
}

module.exports = {
  id: "nobadnovel",
  name: "NoBadNovel",
  version: "1.0.0",
  site: MAIN_URL,
  lang: "en",

  // No filters, but the catalog is browsable.
  browseNovels: function (page) {
    return parseCards(qnGet(MAIN_URL + "/series/page/" + page).document());
  },

  popularNovels: function (page) {
    return qnPopular(page);
  },

  searchNovels: function (query) {
    return parseCards(qnGet(MAIN_URL + "/series?keyword=" + encodeURIComponent(query)).document());
  },

  parseNovel: function (url) {
    var doc = qnGet(url).document();
    var h1 = doc.selectFirst("h1");
    if (!h1) return null;

    var chapters = [];
    doc.select(".chapter-list a[href], .chapters-list a[href]").forEach(function (a) {
      var chUrl = attr(a, "href");
      if (!chUrl.trim()) return;
      var chName = a.text().trim();
      if (!chName) return;
      chapters.push({ name: chName, url: fixUrl(chUrl) });
    });

    var img = doc.selectFirst("img[src*=cdn.nobadnovel], div.thumb img, .series-thumb img, img.object-cover");
    var synopsis = doc.selectFirst("#intro .content, .description, .summary-content, .novel-description");
    var author = doc.selectFirst("span.author, .info-item a[href*=author], body > div > main > div.bg-base-100.sm\\:bg-base-200.py-4.astro-HWRQY3JX > div > div.mx-4.sm\\:mx-0.sm\\:flex.relative.astro-HWRQY3JX > div > div.sm\\:grow.astro-HWRQY3JX > div.mt-3.sm\\:mt-4.text-sm.md\\:text-base.astro-HWRQY3JX > spna");
    var status = doc.selectFirst("span.status, .info-item:contains(Status) span, .badge, .status-value");
    return qnNovel(h1.text().trim(), chapters, {
      posterUrl: fixUrlNull(img ? img.attr("src") : null),
      synopsis: synopsis ? synopsis.text().trim() : null,
      author: author ? author.text().trim() : null,
      status: qnStatus(status ? status.text() : null)
    });
  },

  parseChapter: function (url) {
    var doc = qnGet(url).document();
    doc.select("script, style, .ads, .adblock-service, .social-share, .prev-next").forEach(function (n) { n.remove(); });
    var el = doc.selectFirst("div.text-base.sm\\:text-lg") || doc.selectFirst("div.chapter-content") ||
      doc.selectFirst("article div.content") || doc.selectFirst("div#chapter-content");
    var html = el ? el.html() : null;
    return html && html.trim() ? html : null;
  }
};
