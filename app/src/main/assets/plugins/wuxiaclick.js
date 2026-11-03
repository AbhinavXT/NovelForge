// WuxiaClick — port of the former QuickNovel-based WuxiaClickProvider.
// Chapters are numbered from the novel's chapter count.
// Novel ids are unchanged: qwclick_<base64url(novel url)>.

var MAIN_URL = "https://wuxia.click";

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

function empty(s) {
  return s == null || s === "";
}

function grid(doc) {
  var out = [];
  doc.select("div.mantine-Grid-root > div.mantine-Grid-col > div > a").forEach(function (card) {
    var t = card.selectFirst("div.mantine-w2rcte > div");
    if (!t) return;
    var img = card.selectFirst("img");
    out.push(qnItem(t.text(), fixUrl(attr(card, "href")), img ? attr(img, "src") : null));
  });
  return out;
}

module.exports = {
  id: "wuxiaclick",
  name: "WuxiaClick",
  version: "1.0.0",
  site: MAIN_URL,
  lang: "en",

  filters: {
    categories: pairs([
      ["All", ""],
      ["Mature", "mature"],
      ["Psychological", "psychological"],
      ["Tragedy", "tragedy"],
      ["Mystery", "mystery"],
      ["Seinen", "seinen"],
      ["Harem", "harem"],
      ["Mecha", "mecha"],
      ["Xuanhuan", "xuanhuan"],
      ["Josei", "josei"],
      ["Horror", "horror"],
      ["Adult", "adult"],
      ["Sci-fi", "sci-fi"],
      ["Action", "action"],
      ["Smut", "smut"],
      ["Drama", "drama"],
      ["Yaoi", "yaoi"],
      ["School life", "school life"],
      ["Comedy", "comedy"],
      ["Gender bender", "gender bender"],
      ["Adventure", "adventure"],
      ["Shounen", "shounen"],
      ["Romance", "romance"],
      ["Fantasy", "fantasy"],
      ["Xianxia", "xianxia"],
      ["Martial arts", "martial arts"],
      ["Shounen ai", "shounen ai"],
      ["Supernatural", "supernatural"],
      ["Slice of life", "slice of life"],
      ["Ecchi", "ecchi"],
      ["Sports", "sports"],
      ["Shoujo", "shoujo"],
      ["Historical", "historical"],
      ["Wuxia", "wuxia"],
      ["Yuri", "yuri"],
      ["Shoujo ai", "shoujo ai"],
      ["Mtl", "mtl"]
    ]),
    orderBys: pairs([
      ["Translated chapters", "-num_of_chaps"], ["Rating", "-rating"], ["Name", "-name"],
      ["Old", "-created_at"], ["New", "created_at"]
    ]),
    tags: pairs([
      ["All", ""],
      ["shotacon", "shotacon"],
      ["handsome male lead", "handsome male lead"],
      ["male protagonist", "male protagonist"],
      ["beautiful female lead", "beautiful female lead"],
      ["weak to strong", "weak to strong"],
      ["transmigration", "transmigration"],
      ["calm protagonist", "calm protagonist"],
      ["clever protagonist", "clever protagonist"],
      ["female protagonist", "female protagonist"],
      ["love interest falls in love first", "love interest falls in love first"],
      ["hard-working protagonist", "hard-working protagonist"],
      ["cultivation", "cultivation"],
      ["strong love interests", "strong love interests"],
      ["modern day", "modern day"],
      ["nobles", "nobles"],
      ["schemes and conspiracies", "schemes and conspiracies"],
      ["wealthy characters", "wealthy characters"],
      ["cunning protagonist", "cunning protagonist"],
      ["misunderstandings", "misunderstandings"],
      ["royalty", "royalty"],
      ["devoted love interests", "devoted love interests"],
      ["multiple realms", "multiple realms"],
      ["character growth", "character growth"],
      ["arrogant characters", "arrogant characters"],
      ["determined protagonist", "determined protagonist"],
      ["reincarnation", "reincarnation"],
      ["past plays a big role", "past plays a big role"],
      ["romantic subplot", "romantic subplot"],
      ["magic", "magic"],
      ["time skip", "time skip"],
      ["dragons", "dragons"],
      ["adapted to manhua", "adapted to manhua"],
      ["demons", "demons"],
      ["aristocracy", "aristocracy"],
      ["alchemy", "alchemy"],
      ["hiding true identity", "hiding true identity"],
      ["special abilities", "special abilities"],
      ["multiple pov", "multiple pov"],
      ["slow romance", "slow romance"],
      ["wars", "wars"],
      ["gods", "gods"],
      ["ruthless protagonist", "ruthless protagonist"],
      ["lucky protagonist", "lucky protagonist"],
      ["monsters", "monsters"],
      ["hiding true abilities", "hiding true abilities"],
      ["overpowered protagonist", "overpowered protagonist"],
      ["polygamy", "polygamy"],
      ["game elements", "game elements"],
      ["possessive characters", "possessive characters"],
      ["older love interests", "older love interests"],
      ["sword and magic", "sword and magic"],
      ["doting love interests", "doting love interests"],
      ["beast companions", "beast companions"],
      ["caring protagonist", "caring protagonist"],
      ["death of loved ones", "death of loved ones"],
      ["loyal subordinates", "loyal subordinates"],
      ["artifacts", "artifacts"],
      ["shameless protagonist", "shameless protagonist"],
      ["sword wielder", "sword wielder"],
      ["revenge", "revenge"],
      ["second chance", "second chance"],
      ["fantasy world", "fantasy world"],
      ["tragic past", "tragic past"],
      ["cold love interests", "cold love interests"],
      ["immortals", "immortals"],
      ["marriage", "marriage"],
      ["body tempering", "body tempering"],
      ["strength-based social hierarchy", "strength-based social hierarchy"],
      ["european ambience", "european ambience"],
      ["adapted to manhwa", "adapted to manhwa"],
      ["betrayal", "betrayal"],
      ["pregnancy", "pregnancy"],
      ["genius protagonist", "genius protagonist"],
      ["underestimated protagonist", "underestimated protagonist"],
      ["first-time interc**rse", "first-time interc**rse"],
      ["hidden abilities", "hidden abilities"],
      ["kingdoms", "kingdoms"],
      ["confident protagonist", "confident protagonist"],
      ["protagonist strong from the start", "protagonist strong from the start"],
      ["bloodlines", "bloodlines"],
      ["power couple", "power couple"],
      ["friendship", "friendship"],
      ["manipulative characters", "manipulative characters"],
      ["strong to stronger", "strong to stronger"],
      ["depictions of cruelty", "depictions of cruelty"],
      ["charismatic protagonist", "charismatic protagonist"],
      ["fast cultivation", "fast cultivation"],
      ["unique cultivation technique", "unique cultivation technique"],
      ["world travel", "world travel"],
      ["academy", "academy"],
      ["dense protagonist", "dense protagonist"],
      ["elves", "elves"],
      ["previous life talent", "previous life talent"],
      ["poor to rich", "poor to rich"],
      ["alternate world", "alternate world"],
      ["cold protagonist", "cold protagonist"],
      ["politics", "politics"],
      ["pill concocting", "pill concocting"],
      ["fast learner", "fast learner"],
      ["obsessive love", "obsessive love"],
      ["r*pe", "r*pe"],
      ["long separations", "long separations"],
      ["money grubber", "money grubber"],
      ["transported to another world", "transported to another world"],
      ["cheats", "cheats"],
      ["cautious protagonist", "cautious protagonist"],
      ["mature protagonist", "mature protagonist"],
      ["familial love", "familial love"],
      ["arranged marriage", "arranged marriage"],
      ["magical space", "magical space"],
      ["comedic undertone", "comedic undertone"],
      ["mysterious past", "mysterious past"],
      ["male yandere", "male yandere"],
      ["mysterious family background", "mysterious family background"],
      ["famous protagonist", "famous protagonist"],
      ["magic formations", "magic formations"],
      ["family conflict", "family conflict"],
      ["system administrator", "system administrator"],
      ["reincarnated in another world", "reincarnated in another world"],
      ["late romance", "late romance"],
      ["heavenly tribulation", "heavenly tribulation"],
      ["proactive protagonist", "proactive protagonist"],
      ["complex family relationships", "complex family relationships"],
      ["pets", "pets"],
      ["level system", "level system"],
      ["enemies become allies", "enemies become allies"],
      ["cute children", "cute children"],
      ["death", "death"],
      ["mythical beasts", "mythical beasts"],
      ["enemies become lovers", "enemies become lovers"],
      ["soul power", "soul power"],
      ["ancient china", "ancient china"],
      ["dao comprehension", "dao comprehension"],
      ["charming protagonist", "charming protagonist"],
      ["acting", "acting"],
      ["time travel", "time travel"],
      ["multiple reincarnated individuals", "multiple reincarnated individuals"],
      ["past trauma", "past trauma"],
      ["absent parents", "absent parents"],
      ["master-disciple relationship", "master-disciple relationship"],
      ["childcare", "childcare"],
      ["r*pe victim becomes lover", "r*pe victim becomes lover"],
      ["family", "family"],
      ["secret organizations", "secret organizations"],
      ["phoenixes", "phoenixes"],
      ["appearance different from actual age", "appearance different from actual age"],
      ["curses", "curses"],
      ["age progression", "age progression"],
      ["secret identity", "secret identity"],
      ["sharp-tongued characters", "sharp-tongued characters"],
      ["naive protagonist", "naive protagonist"],
      ["knights", "knights"],
      ["kingdom building", "kingdom building"],
      ["spatial manipulation", "spatial manipulation"],
      ["demon lord", "demon lord"],
      ["tsundere", "tsundere"],
      ["appearance changes", "appearance changes"],
      ["cruel characters", "cruel characters"],
      ["fantasy creatures", "fantasy creatures"],
      ["assassins", "assassins"],
      ["popular love interests", "popular love interests"],
      ["spirit advisor", "spirit advisor"],
      ["time manipulation", "time manipulation"],
      ["dark", "dark"],
      ["business management", "business management"],
      ["heartwarming", "heartwarming"],
      ["broken engagement", "broken engagement"],
      ["destiny", "destiny"],
      ["cooking", "cooking"],
      ["religions", "religions"],
      ["protagonist with multiple bodies", "protagonist with multiple bodies"],
      ["cute protagonist", "cute protagonist"],
      ["r-18", "r-18"]
    ])
  },

  browseNovels: function (page, f) {
    var path = empty(f.category) && !empty(f.tag) ? "tag/" + f.tag
      : !empty(f.category) && empty(f.tag) ? "category/" + f.category
      : "search";
    return grid(qnGet(MAIN_URL + "/" + path + "?page=" + page + "&order_by=" + f.orderBy).document());
  },

  popularNovels: function (page) {
    return qnPopular(page);
  },

  searchNovels: function (query) {
    return grid(qnGet(MAIN_URL + "/search/" + encodeURIComponent(query)).document());
  },

  parseNovel: function (url) {
    var doc = qnGet(url).document();
    var info = doc.selectFirst("div.mantine-Container-root > div.mantine-Paper-root.mantine-Card-root > div");
    var h5 = info ? info.selectFirst("h5") : null;
    if (!h5) throw new Error("Title not found");

    var chapters = [];
    var countEl = info.selectFirst("div.mantine-Group-root div.mantine-19n0k2t");
    if (countEl) {
      var text = countEl.text();
      var head = text.indexOf(" C") >= 0 ? text.substring(0, text.indexOf(" C")) : text;
      if (/^[+-]?\d+$/.test(head)) {
        var slug = url.substring(url.lastIndexOf("/") + 1);
        for (var n = 1; n <= parseInt(head, 10); n++) {
          chapters.push({ name: "Chapter " + n, url: fixUrl(MAIN_URL + "/chapter/" + slug + "-" + n) });
        }
      }
    }

    var img = info.selectFirst("img");
    var synopsis = info.selectFirst("div.mantine-Spoiler-root > div.mantine-Spoiler-content > div > div.mantine-Text-root");
    var authorEl = info.selectFirst("div.mantine-lqk3v2 > div");
    var author = "";
    if (authorEl) {
      var a = authorEl.text();
      var i = a.lastIndexOf("By ");
      author = i >= 0 ? a.substring(i + 3) : a;
    }
    var status = info.selectFirst("div.mantine-Group-root.mantine-1uxmzbt > div.mantine-1huvzos");
    return qnNovel(h5.text(), chapters, {
      posterUrl: img ? attr(img, "src") : null,
      synopsis: synopsis ? synopsis.text() : "",
      author: author,
      status: qnStatus(status ? status.text() : null)
    });
  },

  parseChapter: function (url) {
    var el = qnGet(url).document().selectFirst("#__next .mantine-Container-root .mantine-Paper-root > div:nth-child(4) > div");
    return el ? el.html() : "";
  }
};
