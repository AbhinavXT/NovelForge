// Royal Road — port of the former built-in RoyalRoadSource.
//
// Keeps the old ids so existing libraries keep working:
//   novel   rr_<fictionId>
//   chapter rr_<fictionId>_<chapterId>

var SITE = "https://www.royalroad.com";

var HEADERS = {
  "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
  "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
  "Accept-Language": "en-US,en;q=0.5"
};

/** The page as a Node, or null when the request fails. */
function get(url) {
  try {
    var r = fetch(url, { headers: HEADERS });
    if (!r.ok) {
      console.error("Request failed: " + r.status + " " + url);
      return null;
    }
    return parseHtml(r.text(), url);
  } catch (e) {
    console.error("fetch error: " + e.message);
    return null;
  }
}

function text(node) {
  return node ? node.text().trim() : null;
}

/** /fiction/12345/novel-name -> 12345 */
function fictionId(url) {
  var after = url.indexOf("/fiction/") >= 0 ? url.substring(url.indexOf("/fiction/") + 9) : url;
  var slash = after.indexOf("/");
  return slash >= 0 ? after.substring(0, slash) : after;
}

/** /chapter/67890/title -> 67890 */
function chapterIdOf(url) {
  var after = url.indexOf("/chapter/") >= 0 ? url.substring(url.indexOf("/chapter/") + 9) : url;
  var slash = after.indexOf("/");
  return slash >= 0 ? after.substring(0, slash) : after;
}

function parseNovelList(doc) {
  var novels = [];
  doc.select(".fiction-list-item").forEach(function (item) {
    var titleEl = item.selectFirst(".fiction-title a");
    if (!titleEl) return;
    var title = titleEl.text().trim();
    if (!title) return;
    var url = titleEl.absUrl("href") || "";
    var id = fictionId(url);
    if (!id) return;
    var authorEl = item.selectFirst(".author");
    var author = authorEl ? authorEl.text().replace(/^by /, "").trim() : "Unknown";
    var img = item.selectFirst("img");
    novels.push({
      id: "rr_" + id,
      name: title,
      url: url,
      author: author,
      cover: img ? img.absUrl("src") : null,
      summary: text(item.selectFirst(".description")) || ""
    });
  });
  return novels;
}

function distinctById(list) {
  var seen = {};
  return list.filter(function (n) {
    if (seen[n.id]) return false;
    seen[n.id] = true;
    return true;
  });
}

function parseChapters(doc, novelId) {
  var chapters = [];
  var seen = {};
  doc.select("#chapters tbody tr, table.chapter-list tr, .chapter-row").forEach(function (row, index) {
    var link = row.selectFirst("a[href*=/chapter/]");
    if (!link) return;
    var title = link.text().trim();
    if (!title) return;
    var url = link.absUrl("href");
    if (!url || seen[url]) return;
    seen[url] = true;
    chapters.push({
      id: "rr_" + novelId + "_" + (chapterIdOf(url) || String(index)),
      number: index + 1,
      name: title,
      url: url
    });
  });

  // No chapter table: fall back to any chapter link on the page.
  if (chapters.length === 0) {
    doc.select("a[href*=/chapter/]").forEach(function (link, index) {
      var url = link.absUrl("href") || "";
      if (seen[url]) return;
      seen[url] = true;
      var title = link.text().trim();
      if (!title || title.length < 3) return;
      chapters.push({
        id: "rr_" + novelId + "_" + (chapterIdOf(url) || String(index)),
        number: chapters.length + 1,
        name: title,
        url: url
      });
    });
  }
  return chapters;
}

module.exports = {
  id: "royalroad",
  name: "Royal Road",
  version: "1.0.0",
  site: SITE,
  lang: "en",
  // The built-in source kept translator/editor credit lines.
  stripCredits: false,

  filters: {
    orderBys: [
      { label: "Best Rated", value: "best-rated" },
      { label: "Trending", value: "trending" },
      { label: "Ongoing", value: "active-popular" },
      { label: "Completed", value: "complete" },
      { label: "Popular this week", value: "weekly-popular" },
      { label: "Latest Updates", value: "latest-updates" },
      { label: "New Releases", value: "new-releases" },
      { label: "Rising Stars", value: "rising-stars" }
    ],
    tags: [
      { label: "Action", value: "action" },
      { label: "Adventure", value: "adventure" },
      { label: "Anti-Hero Lead", value: "anti-hero_lead" },
      { label: "Comedy", value: "comedy" },
      { label: "Contemporary", value: "contemporary" },
      { label: "Cyberpunk", value: "cyberpunk" },
      { label: "Drama", value: "drama" },
      { label: "Fantasy", value: "fantasy" },
      { label: "Female Lead", value: "female_lead" },
      { label: "GameLit", value: "gamelit" },
      { label: "Gender Bender", value: "gender_bender" },
      { label: "Grimdark", value: "grimdark" },
      { label: "Harem", value: "harem" },
      { label: "High Fantasy", value: "high_fantasy" },
      { label: "Historical", value: "historical" },
      { label: "Horror", value: "horror" },
      { label: "LitRPG", value: "litrpg" },
      { label: "Low Fantasy", value: "low_fantasy" },
      { label: "Magic", value: "magic" },
      { label: "Male Lead", value: "male_lead" },
      { label: "Martial Arts", value: "martial_arts" },
      { label: "Mystery", value: "mystery" },
      { label: "Mythos", value: "mythos" },
      { label: "Portal Fantasy / Isekai", value: "summoned_hero" },
      { label: "Progression", value: "progression" },
      { label: "Psychological", value: "psychological" },
      { label: "Reincarnation", value: "reincarnation" },
      { label: "Romance", value: "romance" },
      { label: "Satire", value: "satire" },
      { label: "Sci-fi", value: "sci_fi" },
      { label: "Secret Identity", value: "secret_identity" },
      { label: "Short Story", value: "one_shot" },
      { label: "Slice of Life", value: "slice_of_life" },
      { label: "Space Opera", value: "space_opera" },
      { label: "Strategy", value: "strategy" },
      { label: "Strong Lead", value: "strong_lead" },
      { label: "Time Loop", value: "loop" },
      { label: "Time Travel", value: "time_travel" },
      { label: "Tragedy", value: "tragedy" },
      { label: "Virtual Reality", value: "virtual_reality" },
      { label: "War and Military", value: "war_and_military" },
      { label: "Wuxia", value: "wuxia" }
    ]
  },

  browseNovels: function (page, f) {
    var list = f.orderBy || "best-rated";
    // Royal Road's trending and rising-stars lists are single-page.
    if (page > 1 && (list === "trending" || list === "rising-stars")) return [];
    var genre = f.tag ? "&genre=" + f.tag : "";
    var doc = get(SITE + "/fictions/" + list + "?page=" + page + genre);
    return doc ? distinctById(parseNovelList(doc)) : [];
  },

  popularNovels: function (page) {
    return module.exports.browseNovels(page, { category: null, orderBy: null, tag: null });
  },

  searchNovels: function (query) {
    // Up to three result pages at once, as the built-in source did.
    var all = [];
    for (var page = 1; page <= 3; page++) {
      var doc = get(SITE + "/fictions/search?title=" + query.split(" ").join("+") + "&page=" + page);
      if (!doc) break;
      var novels = parseNovelList(doc);
      if (novels.length === 0) break;
      all = all.concat(novels);
    }
    return distinctById(all);
  },

  parseNovel: function (url) {
    var doc = get(url);
    if (!doc) return null;
    var id = fictionId(url);

    var title = text(doc.selectFirst("h1.font-white")) || text(doc.selectFirst("h1"));
    if (!title) return null;

    var author = text(doc.selectFirst("h4.font-white a")) || text(doc.selectFirst("a[href*=/profile/]")) || "Unknown";

    var coverEl = doc.selectFirst(".cover-art-container img") || doc.selectFirst(".fic-header img");

    var description = text(doc.selectFirst(".description .hidden-content")) || text(doc.selectFirst(".description")) || "";

    var status = "Unknown";
    var labels = doc.select(".fiction-info .label");
    for (var i = 0; i < labels.length; i++) {
      var t = labels[i].text().toUpperCase();
      if (t.indexOf("ONGOING") >= 0 || t.indexOf("COMPLETED") >= 0 || t.indexOf("HIATUS") >= 0) {
        status = labels[i].text().trim();
        break;
      }
    }

    return {
      id: "rr_" + id,
      name: title,
      author: author,
      cover: coverEl ? coverEl.absUrl("src") : null,
      summary: description,
      status: status,
      chapters: parseChapters(doc, id)
    };
  },

  parseChapter: function (url) {
    var doc = get(url);
    if (!doc) return null;
    var content = doc.selectFirst(".chapter-content") || doc.selectFirst(".chapter-inner") || doc.selectFirst("[class*=chapter]");
    if (!content) return null;

    // Author notes and ads.
    content.select(".author-note, .author-note-portlet, script, .ad-wrapper, .ads, style, .advertisement, .hidden, noscript")
      .forEach(function (n) { n.remove(); });

    var paragraphs = content.select("p");
    if (paragraphs.length > 0) return content.html();
    // No paragraphs: the whole block is one paragraph, as before.
    return "<p>" + content.text() + "</p>";
  }
};
