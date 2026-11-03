// PawRead — port of the former built-in PawReadSource.
// Chapters are listed with onclick='go_to(N)' handlers instead of links.
//
// Keeps the old ids so existing libraries keep working:
//   novel   pr_<path with "/" as "~">      (pawread.com/novel/123/ -> pr_novel~123)
//   chapter pr_<that>_<chapterNumber>

var SITE = "https://pawread.com";

var HEADERS = {
  "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
  "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
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

function fixUrl(url) {
  if (url.indexOf("http") === 0) return url;
  if (url.indexOf("//") === 0) return "https:" + url;
  return url.charAt(0) === "/" ? SITE + url : SITE + "/" + url;
}

function fixUrlNull(url) {
  return url && url.trim() ? fixUrl(url) : null;
}

/** https://pawread.com/novel/123/ -> novel~123 */
function slugOf(url) {
  var path = url.indexOf(SITE) === 0 ? url.substring(SITE.length) : url;
  return path.replace(/^\/+|\/+$/g, "").split("/").join("~");
}

function parseList(doc, dedupe) {
  var novels = [];
  var seen = {};
  doc.select(".list-comic-thumbnail").forEach(function (item) {
    var node = item.selectFirst(".caption>h3>a");
    if (!node) return;
    var title = node.text().trim();
    if (!title) return;
    var href = node.attr("href") || "";
    if (!href.trim()) return;
    var url = fixUrl(href);
    var slug = slugOf(url);
    if (!slug || (dedupe && seen[slug])) return;
    seen[slug] = true;
    var img = item.selectFirst(".image-link>img");
    novels.push({
      id: "pr_" + slug,
      name: title,
      url: url,
      author: "Unknown",
      cover: img ? fixUrlNull(img.attr("src")) : null,
      summary: ""
    });
  });
  return novels;
}

module.exports = {
  id: "pawread",
  name: "PawRead",
  version: "1.0.0",
  site: SITE,
  lang: "en",
  // The built-in source kept translator/editor credit lines.
  stripCredits: false,

  // URL scheme: /list/{status}{genre}/{sort}/?page=N, e.g. /list/all-All/click/
  filters: {
    categories: [
      { label: "Completed", value: "wanjie-" },
      { label: "Ongoing", value: "lianzai-" },
      { label: "Hiatus", value: "hiatus-" }
    ],
    orderBys: [
      { label: "Clicks", value: "click" },
      { label: "Time Updated", value: "update" },
      { label: "Time Posted", value: "post" }
    ],
    tags: [
      "Fantasy", "Action", "Xuanhuan", "Romance", "Comedy", "Mystery",
      "Mature", "Harem", "Wuxia", "Xianxia", "Tragedy", "Historical",
      "Ecchi", "Adventure", "Adult", "Supernatural", "Psychological",
      "Drama"
    ].map(function (t) { return { label: t, value: t }; }).concat([{ label: "Sci-fi", value: "Scifi" }])
  },

  browseNovels: function (page, f) {
    var doc = get(SITE + "/list/" + (f.category || "all-") + (f.tag || "All") + "/" + (f.orderBy || "click") + "/?page=" + page);
    return doc ? parseList(doc, true) : [];
  },

  popularNovels: function (page) {
    return module.exports.browseNovels(page, { category: null, orderBy: null, tag: null });
  },

  searchNovels: function (query) {
    var doc = get(SITE + "/search/?keywords=" + query);
    return doc ? parseList(doc, false) : [];
  },

  parseNovel: function (url) {
    var doc = get(url);
    if (!doc) return null;
    var slug = slugOf(url);
    var board = doc.selectFirst("#tab1_board");
    if (!board) return null;
    var h1 = board.selectFirst("div>h1");
    if (!h1) return null;

    var info = doc.select("#views_info>div");
    var author = info.length > 3 ? info[3].text().trim() : "Unknown";

    // Cover: background-image in a style attribute.
    var coverDiv = board.selectFirst(">.col-md-3>div");
    var style = coverDiv ? (coverDiv.attr("style") || "") : "";
    var m = /image:url\((.*)\)/.exec(style);
    var cover = m ? fixUrlNull(m[1]) : null;

    var desc = doc.selectFirst("#simple-des");

    var tags = doc.select(".tags").map(function (t) { return t.text().trim().replace(/^#/, "").trim().toLowerCase(); });
    var status = tags.some(function (t) { return t.indexOf("completed") >= 0; }) ? "Completed"
      : tags.some(function (t) { return t.indexOf("ongoing") >= 0; }) ? "Ongoing" : "Unknown";

    var prefix = url.replace(/\/+$/, "") + "/";
    var chapters = [];
    doc.select(".item-box")
      .filter(function (box) { return !box.selectFirst("div>svg"); }) // locked chapters
      .forEach(function (box, index) {
        var titleEl = box.selectFirst("div>span.c_title");
        if (!titleEl) return;
        var m2 = /'(\d+)'/.exec(box.attr("onclick") || "");
        if (!m2) return;
        chapters.push({
          id: "pr_" + slug + "_" + m2[1],
          number: index + 1,
          name: titleEl.text().trim(),
          url: prefix + m2[1] + ".html"
        });
      });

    return {
      id: "pr_" + slug,
      name: h1.text().trim(),
      author: author,
      cover: cover,
      summary: desc ? desc.text().trim() : "",
      status: status,
      chapters: chapters
    };
  },

  parseChapter: function (url) {
    var doc = get(url);
    if (!doc) return null;

    var countdown = doc.selectFirst("#countdown");
    if (countdown) return paragraphsToHtml(["Chapter not yet released. Time remaining: " + countdown.text()]);

    var content = doc.selectFirst("#chapter_item");
    if (!content) return null;
    content.select("script, style, iframe, ins, noscript, .ads, .ad, .adsbygoogle").forEach(function (n) { n.remove(); });

    var paragraphs = content.select("p");
    var out = paragraphs.length > 3
      ? paragraphs.map(function (p) { return p.text().trim(); })
      : parseHtml(content.html().replace(/<br\s*\/?>/g, "\n")).text().split(/\n{2,}/).map(function (s) { return s.trim(); });
    return paragraphsToHtml(out.filter(function (s) { return s.length > 3; }));
  }
};
