// Inori (lnori.com) — port of the former QuickNovel-based LnoriProvider.
// The whole library is one page; filtering and sorting happen locally.
// Novel ids are unchanged: qlno_<base64url(novel url)>.

var MAIN_URL = "https://lnori.com";

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

/** Stable merge sort (Kotlin's sortedBy is stable; Rhino's Array.sort is not guaranteed to be). */
function stableSort(arr, cmp) {
  if (arr.length < 2) return arr;
  var mid = arr.length >> 1;
  var a = stableSort(arr.slice(0, mid), cmp), b = stableSort(arr.slice(mid), cmp);
  var out = [], i = 0, j = 0;
  while (i < a.length && j < b.length) out.push(cmp(b[j], a[i]) < 0 ? b[j++] : a[i++]);
  return out.concat(a.slice(i), b.slice(j));
}

/** Strip combining marks, lower-case, keep [a-z0-9]. */
function normalize(s) {
  return s.replace(/[̀-ͯ]+/g, "").toLowerCase().replace(/[^a-z0-9]/g, "");
}

function card(c) {
  var a = c.selectFirst("a");
  if (!a) return null;
  var h2 = c.selectFirst("h2");
  if (!h2) return null;
  var img = c.selectFirst("img");
  // The provider passed the src through unchanged.
  return qnItem(h2.text(), fixUrl(attr(a, "href")), img ? attr(img, "src") : null);
}

module.exports = {
  id: "lnori",
  name: "Inori",
  version: "1.0.0",
  site: MAIN_URL,
  lang: "en",

  filters: {
    orderBys: pairs([["Relevance", ""], ["Title", "data-t"], ["Year Released", "data-d"], ["Volumes", "data-v"]]),
    tags: pairs([
      ["All", ""],
      ["Academy", "academy"],
      ["Action", "action"],
      ["Adult Protagonist", "adult-protagonist"],
      ["Adventure", "adventure"],
      ["Age Gap", "age-gap"],
      ["Airhead", "airhead"],
      ["Alchemy", "alchemy"],
      ["Animals", "animals"],
      ["Anime Tie-in", "anime-tie-in"],
      ["Aristocracy", "aristocracy"],
      ["Battle", "battle"],
      ["Books", "books"],
      ["Boys Love", "boys-love"],
      ["Business", "business"],
      ["Camping", "camping"],
      ["Childhood Friend", "childhood-friend"],
      ["Chuunibyou", "chuunibyou"],
      ["Combat", "combat"],
      ["Comedy", "comedy"],
      ["Contract Marriage", "contract-marriage"],
      ["Cooking", "cooking"],
      ["Crime", "crime"],
      ["Cross-dressing", "cross-dressing"],
      ["Dark", "dark"],
      ["Dark Fantasy", "dark-fantasy"],
      ["Demon Lord", "demon-lord"],
      ["Demons", "demons"],
      ["Dragons", "dragons"],
      ["Drama", "drama"],
      ["Dungeon", "dungeon"],
      ["Dungeon Diving", "dungeon-diving"],
      ["Dystopian", "dystopian"],
      ["Ecchi", "ecchi"],
      ["Elf", "elf"],
      ["Enemies to Lovers", "enemies-to-lovers"],
      ["Fairies", "fairies"],
      ["Familiars", "familiars"],
      ["Family", "family"],
      ["Fanservice", "fanservice"],
      ["Fantasy", "fantasy"],
      ["Fantasy World", "fantasy-world"],
      ["Female Protagonist", "female-protagonist"],
      ["First Person", "first-person"],
      ["Fish Out of Water", "fish-out-of-water"],
      ["Food", "food"],
      ["Friendship", "friendship"],
      ["Futuristic", "futuristic"],
      ["Game Elements", "game-elements"],
      ["Gamer Protagonist", "gamer-protagonist"],
      ["Gender Bender", "gender-bender"],
      ["Genius", "genius"],
      ["Girls Love", "girls-love"],
      ["Guns", "guns"],
      ["Harem", "harem"],
      ["Heartwarming", "heartwarming"],
      ["High Fantasy", "high-fantasy"],
      ["High School", "high-school"],
      ["Historical", "historical"],
      ["Historical Fantasy", "historical-fantasy"],
      ["Horror", "horror"],
      ["Humor", "humor"],
      ["Invention", "invention"],
      ["Isekai", "isekai"],
      ["Josei", "josei"],
      ["Knights", "knights"],
      ["Lgbtq", "lgbtq"],
      ["Lighthearted", "lighthearted"],
      ["Literary", "literary"],
      ["Magic", "magic"],
      ["Magic Academy", "magic-academy"],
      ["Magical Weapons", "magical-weapons"],
      ["Maid", "maid"],
      ["Male Protagonist", "male-protagonist"],
      ["Marriage", "marriage"],
      ["Martial Arts", "martial-arts"],
      ["Master And Servant", "master-and-servant"],
      ["Mature", "mature"],
      ["Mecha", "mecha"],
      ["Medieval", "medieval"],
      ["Military", "military"],
      ["Modern Day", "modern-day"],
      ["Moe", "moe"],
      ["Monster Girls", "monster-girls"],
      ["Monster Taming", "monster-taming"],
      ["Monsters", "monsters"],
      ["Multiple Pov", "multiple-pov"],
      ["Mystery", "mystery"],
      ["Nobility", "nobility"],
      ["Not The Hero", "not-the-hero"],
      ["Op Power", "op-power"],
      ["Op Protagonist", "op-protagonist"],
      ["Ordinary Protagonist", "ordinary-protagonist"],
      ["Otaku", "otaku"],
      ["Otome", "otome"],
      ["Otome Game", "otome-game"],
      ["Overpowered", "overpowered"],
      ["Paranormal", "paranormal"],
      ["Past Life", "past-life"],
      ["Period Piece", "period-piece"],
      ["Personal Growth", "personal-growth"],
      ["Political Marriage", "political-marriage"],
      ["Politics", "politics"],
      ["Princess", "princess"],
      ["Reincarnation", "reincarnation"],
      ["Revenge", "revenge"],
      ["Reverse Harem", "reverse-harem"],
      ["Rewriting History", "rewriting-history"],
      ["Romance", "romance"],
      ["Romantic Fantasy", "romantic-fantasy"],
      ["Rpg", "rpg"],
      ["Satire", "satire"],
      ["School", "school"],
      ["School Life", "school-life"],
      ["Sci-fi", "sci-fi"],
      ["Seinen", "seinen"],
      ["Shoujo", "shoujo"],
      ["Shounen", "shounen"],
      ["Slice Of Life", "slice-of-life"],
      ["Slow Life", "slow-life"],
      ["Snarky Protagonist", "snarky-protagonist"],
      ["Sorcery", "sorcery"],
      ["Strategy", "strategy"],
      ["Strong Female Lead", "strong-female-lead"],
      ["Supernatural", "supernatural"],
      ["Superpowers", "superpowers"],
      ["Survival", "survival"],
      ["Sword And Sorcery", "sword-and-sorcery"],
      ["Thriller", "thriller"],
      ["Time Travel", "time-travel"],
      ["Tsundere", "tsundere"],
      ["Underdog", "underdog"],
      ["Unique Ability", "unique-ability"],
      ["Vampire", "vampire"],
      ["Video Game", "video-game"],
      ["Video Game Related", "video-game-related"],
      ["Video Game Tie-in", "video-game-tie-in"],
      ["Villainess", "villainess"],
      ["Violence", "violence"],
      ["Vrmmo", "vrmmo"],
      ["War", "war"],
      ["Weak Protagonist", "weak-protagonist"],
      ["Witch", "witch"],
      ["Zero To Hero", "zero-to-hero"]
    ])
  },

  browseNovels: function (page, f) {
    var doc = qnGet(MAIN_URL + "/library").document();
    var cards = doc.select(f.tag ? "article.card[data-tags*=" + f.tag + "]" : "article.card");
    if (f.orderBy) {
      cards = stableSort(cards, function (x, y) {
        var a = attr(x, f.orderBy), b = attr(y, f.orderBy);
        return a < b ? -1 : a > b ? 1 : 0;
      });
    }
    var out = [];
    cards.forEach(function (c) {
      var item = card(c);
      if (item) out.push(item);
    });
    return out;
  },

  popularNovels: function (page) {
    return qnPopular(page);
  },

  searchNovels: function (query) {
    var doc = qnGet(MAIN_URL + "/library").document();
    var q = normalize(query);
    var out = [];
    doc.select("section article.card").forEach(function (c) {
      var h2 = c.selectFirst("h2");
      if (!h2 || normalize(h2.text()).indexOf(q) < 0) return;
      var item = card(c);
      if (item) out.push(item);
    });
    return out;
  },

  parseNovel: function (url) {
    var doc = qnGet(url).document();
    var info = doc.selectFirst("section.hero-section");
    if (!info) throw new Error("No Containes");
    var h1 = info.selectFirst("h1");
    if (!h1) throw new Error("Title not found");

    var chapters = [];
    doc.select("section.content-section > section.vol-grid > article").forEach(function (li) {
      var t = li.selectFirst("h3.card-title");
      if (!t) return;
      var a = li.selectFirst("figure > a");
      if (!a) return;
      chapters.push({ name: t.text(), url: fixUrl(attr(a, "href")) });
    });

    var author = info.selectFirst("header.s-info p");
    var img = info.selectFirst("article > figure > img");
    var desc = doc.selectFirst("p.description.desc-wrapper");
    return qnNovel(h1.text(), chapters, {
      author: author ? author.text() : null,
      posterUrl: img ? attr(img, "src") : null,
      synopsis: desc ? desc.text() : null
    });
  },

  parseChapter: function (url) {
    var doc = qnGet(url).document();
    var sections = doc.select("main > article > section");
    doc.select("picture").forEach(function (picture) {
      var img = picture.selectFirst("img");
      if (img) picture.replaceWith(img);
    });
    return sections.map(function (s) { return s.outerHtml(); }).join("\n");
  }
};
