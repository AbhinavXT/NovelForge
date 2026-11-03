// ReadHive — port of the former QuickNovel-based ReadhiveProvider.
// Novel ids are unchanged: qrhv_<base64url(novel url)>.

var MAIN_URL = "https://readhive.org";

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

var SERIES = "section.relative.grid.grid-cols-1.lg\\:grid-areas-series__body.lg\\:grid-cols-series.gap-x-4.px-4.py-2.sm\\:px-8";

function required(v, what) {
  // Gson filled missing fields with null and the provider then crashed on them.
  if (v == null) throw new Error("search: missing " + what);
  return String(v);
}

module.exports = {
  id: "readhive",
  name: "ReadHive",
  version: "1.0.0",
  site: MAIN_URL,
  lang: "en",
  rateLimitMs: 500,

  filters: {
    tags: pairs([
      ["All", ""],
      ["Abandoned Children", "abandoned-children"],
      ["Absent Parents", "absent-parents"],
      ["abusement", "abusement"],
      ["Abusive Characters", "abusive-characters"],
      ["Academy", "academy"],
      ["Accelerated Growth", "accelerated-growth"],
      ["Acting", "acting"],
      ["actor", "actor"],
      ["Adapted to Manhwa", "adapted-to-manhwa"],
      ["Adopted Children", "adopted-children"],
      ["Adopted Protagonist", "adopted-protagonist"],
      ["Adultery", "adultery"],
      ["Adventure", "adventure"],
      ["Age Progression", "age-progression"],
      ["Age Regression", "age-regression"],
      ["Amnesia", "amnesia"],
      ["and Peasants", "and-peasants"],
      ["Animal Characteristics", "animal-characteristics"],
      ["Appearance Changes", "appearance-changes"],
      ["Appearance Different from Actual Age", "appearance-different-from-actual-age"],
      ["archduke", "archduke"],
      ["Aristocracy", "aristocracy"],
      ["Aristocrats", "aristocrats"],
      ["Army Building", "army-building"],
      ["Arranged Marriage", "arranged-marriage"],
      ["Arrogant Characters", "arrogant-characters"],
      ["Arrogant ML", "arrogant-ml"],
      ["Artifacts", "artifacts"],
      ["Artificial Intelligence", "artificial-intelligence"],
      ["Artists", "artists"],
      ["Assassins", "assassins"],
      ["baby", "baby"],
      ["Badass FL", "badass-fl"],
      ["Badass ML", "badass-ml"],
      ["beast", "beast"],
      ["Beasts", "beasts"],
      ["Beautiful Female Lead", "beautiful-female-lead"],
      ["Betrayal", "betrayal"],
      ["Bickering Couple", "bickering-couple"],
      ["bilateral salvation", "bilateral-salvation"],
      ["BL", "bl"],
      ["BL. Yaoi", "bl-yaoi"],
      ["blood", "blood"],
      ["Bloodlines", "bloodlines"],
      ["blue-haired-fl", "blue-haired-fl"],
      ["Book Possessed", "book-possessed"],
      ["boss", "boss"],
      ["Boss-subordinate", "boss-subordinate"],
      ["Brainwashing", "brainwashing"]
    ])
  },

  browseNovels: function (page, f) {
    var url = MAIN_URL + "/" + (f.tag ? "genre/" + f.tag + "/" : "") + "page/" + page + "/";
    var out = [];
    qnGet(url).document().select("a.peer").forEach(function (card) {
      var img = card.selectFirst("img");
      if (!img) return;
      out.push(qnItem(attr(img, "alt"), fixUrl(attr(card, "href")), fixUrlNull(attr(img, "src"))));
    });
    return out;
  },

  popularNovels: function (page) {
    return qnPopular(page);
  },

  searchNovels: function (query) {
    var root = qnPost(MAIN_URL + "/ajax", { data: { query: query, action: "search" } }).json();
    return root.data.map(function (card) {
      var href = required(card.url, "url").split("\\").join("");
      var title = required(card.title, "title");
      return qnItem(title, fixUrl(href), fixUrlNull(required(card.thumb, "thumb").split("\\").join("")));
    });
  },

  parseNovel: function (url) {
    var doc = qnGet(url).document();
    var main = doc.selectFirst("main");
    // No <main>: the provider returned an empty novel named after its url.
    if (!main) return qnNovel(url, []);
    var h1 = main.selectFirst("h1");
    if (!h1) throw new Error("Title not found");

    var chapters = [];
    doc.select(SERIES + " > div.lg\\:grid-in-content.mt-4 > div:nth-child(1) > div:nth-child(3) > div > div > a").forEach(function (li) {
      if (li.selectFirst("> span")) return; // locked
      var name = li.selectFirst("div > div > span");
      if (!name) return;
      chapters.push({ name: name.text(), url: fixUrl(attr(li, "href")) });
    });
    chapters.reverse();

    var img = main.selectFirst("img.object-cover");
    var author = main.selectFirst("span.leading-7");
    return qnNovel(h1.text(), chapters, {
      posterUrl: fixUrlNull(img ? img.attr("src") : null),
      synopsis: doc.select(SERIES + " div.mb-4 > p").map(function (p) { return p.text(); }).join("\n"),
      author: author ? author.text() : ""
    });
  },

  parseChapter: function (url) {
    var reader = qnGet(url).document().selectFirst("main > div.justify-center.flex-grow.mx-auto.prose.md\\:max-w-4xl.lg\\:relative");
    return reader ? reader.html() : null;
  }
};
