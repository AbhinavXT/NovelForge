// Light Novel Translations — port of the former QuickNovel-based LightNovelTranslationsProvider.
// Novel ids are unchanged: qlnt_<base64url(novel url)>.

var MAIN_URL = "https://lightnovelstranslations.com";

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

function listItems(doc) {
  var out = [];
  // Items used to be <div>s with a title attribute; the site now uses
  // <article> and aria-label.
  doc.select(".read_list-story-item").forEach(function (el) {
    var link = el.selectFirst(".item_thumb a");
    if (!link) return;
    var img = el.selectFirst(".item_thumb img");
    var title = attr(link, "title") || attr(link, "aria-label");
    out.push(qnItem(title, fixUrl(attr(link, "href")), fixUrlNull(img ? img.attr("src") : null)));
  });
  return out;
}

module.exports = {
  id: "lightnoveltranslations",
  name: "Light Novel Translations",
  version: "1.1.0",
  site: MAIN_URL,
  lang: "en",

  filters: {
    categories: pairs([["Most Liked", "most-liked"], ["Most Recent", "most-recent"]]),
    tags: pairs([["All", "all"], ["Ongoing", "ongoing"], ["Completed", "completed"]])
  },

  browseNovels: function (page, f) {
    // Page 1 is the site's pages 1-3; page N after that is site page N + 2.
    var from = page === 1 ? 1 : page + 2;
    var to = page === 1 ? 3 : page + 2;
    var novels = [];
    for (var i = from; i <= to; i++) {
      novels = novels.concat(listItems(qnGet(MAIN_URL + "/read/page/" + i + "?sortby=" + f.category + "&status=" + f.tag).document()));
    }
    return novels;
  },

  popularNovels: function (page) {
    return qnPopular(page);
  },

  searchNovels: function (query) {
    if (!query.trim()) return [];
    return listItems(qnPost(MAIN_URL + "/read", { data: { "field-search": query } }).document());
  },

  parseNovel: function (url) {
    var doc = qnGet(url).document();
    var titleEl = doc.selectFirst("div.novel_title h3");
    if (!titleEl) throw new Error("Title not found");

    var chapters = [];
    doc.select("li.chapter-item.unlock").forEach(function (li) {
      var link = li.selectFirst("a");
      if (!link) return;
      chapters.push({ name: link.text().trim(), url: fixUrl(attr(link, "href")) });
    });

    var authorEl = doc.selectFirst("div.novel_detail_info li:contains(Author)");
    var img = doc.selectFirst("div.novel-image img");
    var statusEl = doc.selectFirst("div.novel_status");
    return qnNovel(titleEl.text().trim(), chapters, {
      author: authorEl ? authorEl.text().trim() : "",
      posterUrl: fixUrlNull(img ? img.attr("src") : null),
      // The provider assigned synopsis to itself, so it was always empty.
      synopsis: null,
      status: qnStatus(statusEl ? statusEl.text().trim() : null)
    });
  },

  parseChapter: function (url) {
    try {
      var content = qnGet(url).document().selectFirst("div.text_story");
      if (!content) return null;
      content.select("div.ads_content").forEach(function (n) { n.remove(); });
      return content.html();
    } catch (e) {
      console.error(e.message);
      return null;
    }
  }
};
