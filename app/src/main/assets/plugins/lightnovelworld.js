// LightNovelWorld — port of the former QuickNovel-based LightNovelWorldProvider.
// Novel ids are unchanged: qlnw_<base64url(novel url)>.

var MAIN_URL = "https://lightnovelworld.org";

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

function withPageParam(url, page) {
  return url.indexOf("?") >= 0 ? url + "&page=" + page : url + "?page=" + page;
}

function lastOf(list) {
  return list.length ? list[list.length - 1] : null;
}

/** Kotlin String.toIntOrNull() */
function toIntOrNull(s) {
  return /^[+-]?\d+$/.test(s) ? parseInt(s, 10) : null;
}

module.exports = {
  id: "lightnovelworld",
  name: "LightNovelWorld",
  version: "1.0.0",
  site: MAIN_URL,
  lang: "en",

  filters: {
    categories: pairs([
      ["New Update", "/updates/"],
      ["Top Ranked", "/ranking/?sort=rank"],
      ["Top Reviews", "/ranking/?sort=reviews"],
      ["Top Comments", "/ranking/?sort=comments"],
      ["Top Collections", "/ranking/?sort=collections"]
    ])
  },

  browseNovels: function (page, f) {
    var doc = qnGet(withPageParam(fixUrl(f.category || "/updates/"), page)).document();
    var seen = {};
    var cards = doc.select("div.ranking-list > a").concat(doc.select("a.card-link")).filter(function (c) {
      var href = attr(c, "href");
      if (seen[href]) return false;
      seen[href] = true;
      return true;
    });
    var out = [];
    cards.forEach(function (card) {
      var href = attr(card, "href");
      if (!href.trim()) return;
      var t = card.selectFirst("h4.ranking-item-title, h3.card-title, h4");
      if (!t) return;
      var cover = card.selectFirst("div.ranking-item-cover > img");
      var bg = card.selectFirst("div.card-cover");
      var img = card.selectFirst("img");
      var poster = cover ? attr(cover, "src") : bg ? attr(bg, "data-bg-image") : img ? attr(img, "src") : null;
      out.push(qnItem(t.text().trim(), fixUrl(href), fixUrlNull(poster)));
    });
    return out;
  },

  popularNovels: function (page) {
    return qnPopular(page);
  },

  searchNovels: function (query) {
    var response = qnGet(MAIN_URL + "/api/search/?q=" + encodeURIComponent(query)).json();
    var novels = response && response.novels;
    if (!novels) return [];
    var out = [];
    novels.forEach(function (n) {
      if (n == null || n.title == null || n.slug == null) return;
      out.push(qnItem(String(n.title).trim(), fixUrl("/novel/" + String(n.slug).trim() + "/"), fixUrlNull(n.cover_path == null ? null : String(n.cover_path))));
    });
    return out;
  },

  parseNovel: function (url) {
    var doc = qnGet(url).document();
    var t = doc.selectFirst("h1.novel-title, meta[property=og:title]");
    if (!t) return null;
    var title = (t.tagName() === "meta" ? attr(t, "content") : t.text()).trim();

    var cleanUrl = url.replace(/\/$/, "");
    var first = qnGet(cleanUrl + "/chapters/?page=1").document();
    var chapters = null;
    var pagination = first.selectFirst("select#pageSelect");
    if (pagination) {
      var lastOption = lastOf(pagination.select("option"));
      var lastPage = (lastOption && toIntOrNull(lastOption.text())) || 1;
      // Number the chapters from the last card's chapter number.
      var lastDoc = qnGet(cleanUrl + "/chapters/?page=" + lastPage).document();
      var lastCard = lastOf(lastDoc.select("div.chapters-grid > div.chapter-card"));
      var numEl = lastCard ? lastCard.selectFirst("div.chapter-number") : null;
      var total = numEl ? toIntOrNull(numEl.text()) : null;
      if (total != null) {
        chapters = [];
        for (var n = 1; n <= total; n++) chapters.push({ name: "Chapter " + n, url: fixUrl(cleanUrl + "/chapter/" + n) });
      }
    }
    if (chapters == null) {
      chapters = [];
      first.select("div.chapters-grid > div.chapter-card").forEach(function (li) {
        var h3 = li.selectFirst("h3");
        if (!h3) return;
        var onClick = attr(li, "onClick");
        var i = onClick.indexOf("location.href='");
        var after = i >= 0 ? onClick.substring(i + "location.href='".length) : "";
        var j = after.indexOf("'");
        var chapterUrl = fixUrlNull(j >= 0 ? after.substring(0, j) : "") || "";
        chapters.push({ name: h3.text(), url: fixUrl(chapterUrl) });
      });
    }

    var authorEl = doc.selectFirst("p.novel-author > a");
    var og = doc.selectFirst("meta[property=og:image]");
    var cover = doc.selectFirst("img.novel-cover");
    var summary = doc.selectFirst("div.summary-content");
    var status = doc.selectFirst("span.status-badge");
    return qnNovel(title, chapters, {
      author: authorEl ? authorEl.text().trim() : null,
      posterUrl: fixUrlNull(og ? attr(og, "content") : cover ? attr(cover, "src") : null),
      synopsis: summary ? summary.text().trim() : null,
      status: qnStatus(status ? status.text().trim() : null)
    });
  },

  parseChapter: function (url) {
    var root = qnGet(url).document().selectFirst("div.chapter-text");
    if (!root) return null;
    root.select("script, style, .ads, .ad, .advertisement, .chapter-nav").forEach(function (n) { n.remove(); });
    var paragraphs = root.select("> p");
    if (paragraphs.length) return paragraphs.map(function (p) { return p.outerHtml(); }).join("");
    var html = root.html();
    return html.trim() ? html : null;
  }
};
