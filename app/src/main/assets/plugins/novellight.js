// Novel Light (novelight.net) — port of the former QuickNovel-based NovelLightProvider.
// Novel ids are unchanged: qnlig_<base64url(novel url)>.

var MAIN_URL = "https://novelight.net";
var AJAX_URL = MAIN_URL + "/book/ajax/read-chapter";

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

function ajaxHeaders(url) {
  return { "X-Requested-With": "XMLHttpRequest", "Referer": url, "Accept": "application/json, text/javascript, */*; q=0.01" };
}

function between(s, start, end) {
  var i = s.indexOf(start);
  var after = i >= 0 ? s.substring(i + start.length) : s;
  var j = after.indexOf(end);
  return j >= 0 ? after.substring(0, j) : after;
}

function cards(doc) {
  var out = [];
  doc.select("div.manga-grid-list > a").forEach(function (card) {
    var href = fixUrlNull(card.attr("href"));
    if (href == null) return;
    var t = card.selectFirst("div.title");
    if (!t) return;
    var img = card.selectFirst("img");
    out.push(qnItem(t.text(), fixUrl(href), fixUrlNull(img ? img.attr("src") : null)));
  });
  return out;
}

function getChapters(doc) {
  var csrfScript = doc.selectFirst("script:containsData(window.CSRF_TOKEN)");
  if (!csrfScript) return [];
  var csrf = between(csrfScript.html(), "window.CSRF_TOKEN = \"", "\";");
  var bookScript = doc.selectFirst("script:containsData(const BOOK_ID)");
  if (!bookScript) return [];
  var bookId = between(bookScript.html(), "const BOOK_ID = \"", "\"");
  var url = MAIN_URL + "/book/ajax/chapter-pagination?csrfmiddlewaretoken=" + csrf + "&book_id=" + bookId + "&page=1&pagination=0";
  var response = qnGet(url, { headers: ajaxHeaders(url) }).json();
  if (response == null || response.html == null) throw new Error("chapter-pagination: no html");
  var out = [];
  parseHtml(String(response.html)).select("a").forEach(function (li) {
    if (li.selectFirst("span.cost")) return; // paid chapter
    var t = li.selectFirst("div.title");
    var href = fixUrlNull(li.attr("href"));
    if (href == null) return;
    out.push({ name: t ? t.text() : "", url: fixUrl(href) });
  });
  return out.reverse();
}

module.exports = {
  id: "novellight",
  name: "Novel Light",
  version: "1.0.0",
  site: MAIN_URL,
  lang: "en",

  browseNovels: function (page) {
    return cards(qnGet(MAIN_URL + "/catalog/?page=" + page).document());
  },

  popularNovels: function (page) {
    return qnPopular(page);
  },

  searchNovels: function (query) {
    return cards(qnGet(MAIN_URL + "/catalog/?search=" + encodeURIComponent(query.trim()).split("%20").join("+")).document());
  },

  parseNovel: function (url) {
    var doc = qnGet(url).document();
    var info = doc.selectFirst("div.container > div.flex-content");
    if (!info) throw new Error("No Containes");
    var h1 = doc.selectFirst("header h1");
    if (!h1) throw new Error("Title not found");

    var chapters = getChapters(doc);
    var author = null, status = null;
    doc.select("div.block.mini-info > a").forEach(function (a) {
      var value = a.selectFirst("div.info");
      if (a.text().indexOf("Author") >= 0) author = value ? value.text() : "";
      else if (a.text().indexOf("Status") >= 0) status = qnStatus(value ? value.text() : null) || status;
    });
    var img = info.selectFirst("div.poster > img");
    return qnNovel(h1.text(), chapters, {
      posterUrl: fixUrlNull(img ? img.attr("src") : null),
      synopsis: doc.select("section.text-info.section > p").map(function (p) { return p.text(); }).join("\n"),
      author: author,
      status: status
    });
  },

  parseChapter: function (url) {
    var i = url.lastIndexOf("/book/chapter/");
    var rest = i >= 0 ? url.substring(i + "/book/chapter/".length) : url;
    var json = qnGet(AJAX_URL + "/" + rest, { headers: ajaxHeaders(url) }).json();
    return json && json.content != null ? String(json.content) : null;
  }
};
