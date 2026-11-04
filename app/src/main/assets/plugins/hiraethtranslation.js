// HiraethTranslation — port of the former QuickNovel-based HiraethTranslationProvider.
// WordPress "wp-manga" (Madara) site.
// Novel ids are unchanged: qhir_<base64url(novel url)>.

var MAIN_URL = "https://hiraethtranslation.com";

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

/** Kotlin String.toFloat() (Java Float.parseFloat): throws on anything else, "" included. */
function toFloat(s) {
  var t = s.replace(/^[\u0000- ]+|[\u0000- ]+$/g, "");
  if (!/^[+-]?(NaN|Infinity|((\d+\.?\d*|\.\d+)([eE][+-]?\d+)?)[fFdD]?)$/.test(t)) {
    throw new Error("NumberFormatException: For input string: \"" + s + "\"");
  }
  return parseFloat(t);
}

/** Kotlin String.toInt(): throws on anything that isn't an integer. */
function toInt(s) {
  if (!/^[+-]?\d+$/.test(s)) throw new Error("NumberFormatException: For input string: \"" + s + "\"");
  return parseInt(s, 10);
}

function listItems(doc) {
  var out = [];
  doc.select("div.c-tabs-item > div.row.c-tabs-item__content").forEach(function (h) {
    var h3 = h.selectFirst("h3.h4 > a");
    if (!h3) return;
    var img = h.selectFirst("div.c-image-hover > a > img");
    out.push(qnItem(h3.text(), fixUrl(attr(h3, "href")), fixUrlNull(img ? img.attr("src") : null),
      qnText(h.select("div.latest-chap > span.chapter > a"))));
  });
  return out;
}

function chapterLinks(doc, selector) {
  return doc.select(selector).reverse().map(function (c) {
    return { name: c.text(), url: fixUrl(attr(c, "href")) };
  });
}

module.exports = {
  id: "hiraethtranslation",
  name: "HiraethTranslation",
  version: "1.0.0",
  site: MAIN_URL,
  lang: "en",

  filters: {
    orderBys: pairs([
      ["Latest Release", "latest"],
      ["Relevance", ""],
      ["A-Z", "alphabet"],
      ["Rating", "rating"],
      ["Trending", "trending"],
      ["Most Views", "views"],
      ["New", "new-manga"]
    ])
  },

  browseNovels: function (page, f) {
    return listItems(qnGet(MAIN_URL + "/page/" + page + "/?s&post_type=wp-manga&m_orderby=" + f.orderBy).document());
  },

  popularNovels: function (page) {
    return qnPopular(page);
  },

  searchNovels: function (query) {
    return listItems(qnGet(MAIN_URL + "/?s=" + query + "&post_type=wp-manga&m_orderby=latest").document());
  },

  parseNovel: function (url) {
    var doc = qnGet(url).document();
    var h1 = doc.selectFirst("div.post-title > h1");
    if (!h1) return null;

    var data = chapterLinks(doc, "ul.main.version-chap > li.wp-manga-chapter.free-chap > a")
      .concat(chapterLinks(doc, "ul.sub-chap-list > li.wp-manga-chapter.free-chap > a"));

    var authorEl = doc.selectFirst("div.author-content > a");
    var synopsisEl = doc.selectFirst("div.summary__content");
    // As in the original, an empty or unparsable rating fails the whole load.
    var votes = doc.select("div.summary-content.vote-details");
    var allVotes = [];
    votes.forEach(function (v) { allVotes = allVotes.concat(v.select("span#averagerate")); });
    toFloat(qnText(allVotes));
    var counts = [];
    votes.forEach(function (v) { counts = counts.concat(v.select("span#countrate")); });
    toInt(qnText(counts));
    var statusHeader = doc.selectFirst("div.summary-content");

    return qnNovel(h1.text(), data, {
      author: authorEl ? authorEl.text() : null,
      posterUrl: fixUrlNull(qnAttr(doc.select("div.summary_image > a > img.img-responsive"), "src")),
      synopsis: synopsisEl ? synopsisEl.text() : null,
      status: qnStatus(statusHeader ? statusHeader.text() : null)
    });
  },

  parseChapter: function (url) {
    var text = qnGet(url).text
      .replace(new RegExp("𝓵𝒊𝒃𝙧𝙚𝒂𝓭\\.𝓬𝙤𝙢", "gi"), "")
      .replace(/libread\.com/gi, "");
    var content = parseHtml(text).selectFirst("div.text-left");
    return content ? content.html() : null;
  }
};
