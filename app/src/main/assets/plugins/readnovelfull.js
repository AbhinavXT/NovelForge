// ReadNovelFull — port of the former built-in ReadNovelFullSource.
//
// Keeps the old ids so existing libraries keep working:
//   novel   rnf_<slug>
//   chapter rnf_<slug>_<chapterSlug>

var SITE = "https://readnovelfull.com";

var HEADERS = {
  "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
  "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
  "Accept-Language": "en-US,en;q=0.5",
  "Referer": SITE
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

/** .../some-novel.html -> some-novel (also used for chapter urls) */
function lastSegment(url) {
  var s = url.substring(url.lastIndexOf("/") + 1);
  return s.slice(-5) === ".html" ? s.slice(0, -5) : s;
}

/** Elements.text(): texts joined by single spaces. */
function joinText(nodes) {
  var sb = "";
  nodes.forEach(function (n) {
    if (sb.length !== 0) sb += " ";
    sb += n.text();
  });
  return sb;
}

function parseList(doc) {
  var novels = [];
  doc.select("div.col-novel-main > div.list-novel > div.row").forEach(function (row) {
    var divs = row.select("> div > div");
    if (divs.length < 2) return;
    var img = divs[0].selectFirst("img");
    var poster = img ? (img.attr("src") || "").split("t-200x89").join("t-300x439") : null;
    var header = divs[1].selectFirst("h3.novel-title > a");
    if (!header) return;
    var href = header.absUrl("href") || "";
    var title = header.text().trim();
    if (!title) return;
    var slug = lastSegment(href);
    if (!slug) return;
    novels.push({ id: "rnf_" + slug, name: title, url: href, author: "Unknown", cover: poster, summary: "" });
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

function infoMeta(doc, label) {
  var items = doc.select("ul.info-meta > li");
  for (var i = 0; i < items.length; i++) {
    var h3 = items[i].selectFirst("h3");
    if (h3 && h3.text().toLowerCase().indexOf(label.toLowerCase()) >= 0) return items[i];
  }
  return null;
}

function chaptersFrom(links, slug, requireChapterInUrl) {
  var chapters = [];
  var seen = {};
  links.forEach(function (link, index) {
    var span = link.selectFirst("span");
    var title = span ? span.text().trim() : link.text().trim();
    if (!title) return;
    var url = link.absUrl("href") || (SITE + (link.attr("href") || ""));
    if (!url || (requireChapterInUrl && url.indexOf("chapter") < 0)) return;
    var id = "rnf_" + slug + "_" + lastSegment(url);
    if (seen[id]) return;
    seen[id] = true;
    chapters.push({ id: id, number: index + 1, name: title, url: url });
  });
  return chapters;
}

function findLargestTextContainer(doc) {
  var best = null;
  var max = 0;
  doc.select("div, article, section").forEach(function (el) {
    var length = (el.ownText() + joinText(el.select("p"))).length;
    var cls = (el.attr("class") || "").trim().toLowerCase();
    var id = (el.attr("id") || "").toLowerCase();
    if (cls.indexOf("nav") >= 0 || cls.indexOf("header") >= 0 || cls.indexOf("footer") >= 0 ||
        cls.indexOf("menu") >= 0 || cls.indexOf("sidebar") >= 0 ||
        id.indexOf("nav") >= 0 || id.indexOf("header") >= 0 || id.indexOf("footer") >= 0) return;
    if (length > max && length > 500) {
      max = length;
      best = el;
    }
  });
  return best;
}

var WATERMARKS = [
  "[Updated from F r e e w e b n o v e l. c o m]",
  "Read latest chapters at f(r)eewebnovel Only",
  "freewebnovel.com",
  "readnovelfull.com",
  "ƒreewebnovel.com",
  "Find authorized novels in Webnovel，faster updates, better experience，Please click www.webnovel.com for visiting."
];

module.exports = {
  id: "readnovelfull",
  name: "ReadNovelFull",
  version: "1.0.0",
  site: SITE,
  lang: "en",
  // The built-in source kept translator/editor credit lines.
  stripCredits: false,

  // Genre listing URLs are unverified for this site, so only sort lists are offered.
  filters: {
    orderBys: [
      { label: "Most Popular", value: "most-popular-novel" },
      { label: "Hot Novels", value: "hot-novel" },
      { label: "Completed", value: "completed-novel" },
      { label: "Latest Releases", value: "latest-release-novel" }
    ]
  },

  browseNovels: function (page, f) {
    var doc = get(SITE + "/novel-list/" + (f.orderBy || "most-popular-novel") + "?page=" + page);
    return doc ? distinctById(parseList(doc)) : [];
  },

  popularNovels: function (page) {
    return module.exports.browseNovels(page, { category: null, orderBy: null, tag: null });
  },

  searchNovels: function (query) {
    var all = [];
    for (var page = 1; page <= 3; page++) {
      var doc = get(SITE + "/novel-list/search?keyword=" + query.split(" ").join("+") + "&page=" + page);
      if (!doc) break;
      var novels = parseList(doc);
      if (novels.length === 0) break;
      all = all.concat(novels);
    }
    return distinctById(all);
  },

  parseNovel: function (url) {
    var doc = get(url);
    if (!doc) return null;
    var slug = lastSegment(url);

    var rating = doc.selectFirst("div#rating");
    var novelId = rating ? rating.attr("data-novel-id") : null;
    if (novelId == null) {
      var any = doc.selectFirst("[data-novel-id]");
      novelId = any ? any.attr("data-novel-id") : null;
    }

    var bookInfo = doc.selectFirst("div.col-info-desc > div.info-holder > div.books");
    var title = (bookInfo && text(bookInfo.selectFirst("div.desc > h3.title"))) || text(doc.selectFirst("h3.title"));
    if (!title) return null;

    var authorMeta = infoMeta(doc, "Author:");
    var author = (authorMeta && text(authorMeta.selectFirst("a"))) || "Unknown";

    var coverEl = (bookInfo && bookInfo.selectFirst("div.book > img")) || doc.selectFirst(".book img");

    var statusMeta = infoMeta(doc, "Status:");
    var statusText = ((statusMeta && text(statusMeta.selectFirst("a"))) || "").toLowerCase();
    var status = statusText.indexOf("completed") >= 0 ? "Completed"
      : statusText.indexOf("ongoing") >= 0 ? "Ongoing" : "Unknown";

    var chapters;
    if (novelId) {
      // Full list from the AJAX archive, keyed by the numeric novel id.
      var archive = get(SITE + "/ajax/chapter-archive?novelId=" + novelId);
      chapters = archive
        ? chaptersFrom(archive.select("div.panel-body > div.row > div > ul.list-chapter > li > a"), slug, false)
        : [];
    } else {
      chapters = chaptersFrom(doc.select("#list-chapter a, .list-chapter a, ul.list-chapter li a"), slug, true);
    }

    return {
      id: "rnf_" + slug,
      name: title,
      author: author,
      cover: coverEl ? coverEl.absUrl("src") : null,
      summary: text(doc.selectFirst("div.desc-text")) || "",
      status: status,
      chapters: chapters
    };
  },

  parseChapter: function (url) {
    var doc = get(url);
    if (!doc) return null;
    var content = doc.selectFirst("div#chr-content") || doc.selectFirst("#chr-content") ||
      doc.selectFirst("#chapter-content") || doc.selectFirst(".chr-c") || findLargestTextContainer(doc);
    if (!content) return null;

    content.select("script, style, .ads, .ad, .advertisement, .google-auto-placed, .adsbygoogle, ins, iframe, " +
      "noscript, .hidden, [style*=display:none]").forEach(function (n) { n.remove(); });

    var html = content.html();
    WATERMARKS.forEach(function (w) { html = html.split(w).join(""); });
    var cleaned = parseHtml(html);

    var paragraphs = cleaned.select("p");
    var out;
    if (paragraphs.length > 3) {
      out = paragraphs.map(function (p) { return p.text().trim(); });
    } else {
      // Few or no <p>: split the text on runs of whitespace.
      out = cleaned.selectFirst("body").text().split(/[ \t\n\x0B\f\r]{2,}/).map(function (s) { return s.trim(); });
    }
    return paragraphsToHtml(out.filter(function (s) { return s.length > 5; }));
  }
};
