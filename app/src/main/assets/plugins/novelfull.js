// NovelFull (novelfull.net) — port of the former built-in NovelFullNetSource.
// Uses the "AllNovel" HTML structure shared with allnovel.org, novelfull.com, etc.
//
// Keeps the old ids so existing libraries keep working:
//   novel   nfn_<slug>
//   chapter nfn_<slug>_<chapterSlug>

var SITE = "https://novelfull.net";

// The site needs a mobile user agent.
var HEADERS = {
  "User-Agent": "Mozilla/5.0 (Linux; Android 13; SM-A205U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Mobile Safari/537.36",
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

/** .../some-novel.html/ -> some-novel */
function slugOf(url) {
  var s = url.replace(/\/+$/, "");
  s = s.substring(s.lastIndexOf("/") + 1);
  return s.slice(-5) === ".html" ? s.slice(0, -5) : s;
}

/** Elements.attr(): the attribute of the first element that has it, else "". */
function firstAttr(nodes, name) {
  for (var i = 0; i < nodes.length; i++) {
    var v = nodes[i].attr(name);
    if (v != null) return v;
  }
  return "";
}

function item(titleEl, imgEl) {
  var title = titleEl.text().trim();
  if (!title) return null;
  var href = titleEl.attr("href") || "";
  if (!href.trim()) return null;
  var url = fixUrl(href);
  var slug = slugOf(url);
  if (!slug) return null;
  return {
    id: "nfn_" + slug,
    name: title,
    url: url,
    author: "Unknown",
    cover: imgEl ? fixUrlNull(imgEl.attr("src")) : null,
    summary: ""
  };
}

module.exports = {
  id: "novelfull-net",
  name: "NovelFull",
  version: "1.0.0",
  site: SITE,
  lang: "en",
  // The built-in source kept translator/editor credit lines.
  stripCredits: false,

  // The novelfull engine's standard list pages. Genre pages are unverified, so not offered.
  filters: {
    orderBys: [
      { label: "Hot Novels", value: "hot-novel" },
      { label: "Most Popular", value: "most-popular" },
      { label: "Latest Releases", value: "latest-release-novel" },
      { label: "Completed", value: "completed-novel" }
    ]
  },

  browseNovels: function (page, f) {
    var doc = get(SITE + "/" + (f.orderBy || "hot-novel") + "?page=" + page);
    if (!doc) return [];
    var novels = [];
    var seen = {};
    doc.select("div.list>div.row").forEach(function (row) {
      var a = row.selectFirst("div > div > h3.truyen-title > a");
      if (!a) return;
      var n = item(a, row.selectFirst("div > div > img"));
      if (!n || seen[n.id]) return;
      seen[n.id] = true;
      novels.push(n);
    });
    return novels;
  },

  popularNovels: function (page) {
    return module.exports.browseNovels(page, { category: null, orderBy: null, tag: null });
  },

  searchNovels: function (query) {
    var doc = get(SITE + "/search?keyword=" + query);
    if (!doc) return [];
    var novels = [];
    doc.select("#list-page > .archive > .list > .row").forEach(function (row) {
      var a = row.selectFirst(">div>div>.truyen-title>a") || row.selectFirst(">div>div>.novel-title>a");
      if (!a) return;
      var n = item(a, row.selectFirst(">div>div>img"));
      if (n) novels.push(n);
    });
    return novels;
  },

  parseNovel: function (url) {
    var doc = get(url);
    if (!doc) return null;
    var slug = slugOf(url);

    var titleEl = doc.selectFirst("h3.title");
    if (!titleEl) return null;
    var title = titleEl.text().trim();

    var dataNovelId = firstAttr(doc.select("#rating"), "data-novel-id");

    var infos = doc.select("div.info > div");
    if (infos.length === 0) infos = doc.select("ul.info > li");
    function info(label) {
      for (var i = 0; i < infos.length; i++) {
        if (infos[i].text().indexOf(label) >= 0) return infos[i];
      }
      return null;
    }

    var authorInfo = info("Author:");
    var authorA = authorInfo ? authorInfo.selectFirst("a") : null;
    var author = authorA ? authorA.text().trim() : "Unknown";

    var cover = null;
    var img = doc.selectFirst("div.book img");
    if (img) {
      var src = img.attr("src");
      cover = fixUrlNull(src && src.trim() ? src : img.attr("data-src"));
    }

    var descEl = doc.selectFirst("div.desc-text");

    var statusInfo = info("Status:");
    var statusA = statusInfo ? statusInfo.selectFirst("a") : null;
    var statusText = statusA ? statusA.text().trim().toLowerCase() : "";
    var status = (statusText === "ongoing" || statusText === "on-going") ? "Ongoing"
      : (statusText === "completed" || statusText === "complete") ? "Completed" : "Unknown";

    var chapters = [];
    if (dataNovelId.trim()) {
      var ajax = get(SITE + "/ajax-chapter-option?novelId=" + dataNovelId);
      if (ajax) {
        // <option> tags (older format), otherwise list links.
        var parsed = ajax.select("select > option");
        if (parsed.length === 0) parsed = ajax.select(".list-chapter>li>a");
        parsed.forEach(function (c, index) {
          var chapterUrl = c.attr("value");
          if (!chapterUrl || !chapterUrl.trim()) chapterUrl = c.attr("href");
          if (!chapterUrl || !chapterUrl.trim()) return;
          chapterUrl = fixUrl(chapterUrl);
          chapters.push({
            id: "nfn_" + slug + "_" + slugOf(chapterUrl),
            number: index + 1,
            name: c.text().trim() || ("Chapter " + (index + 1)),
            url: chapterUrl
          });
        });
      }
    }

    return {
      id: "nfn_" + slug,
      name: title,
      author: author,
      cover: cover,
      summary: descEl ? descEl.text().trim() : "",
      status: status,
      chapters: chapters
    };
  },

  parseChapter: function (url) {
    var doc = get(url);
    if (!doc) return null;
    var content = doc.selectFirst("#chapter-content") || doc.selectFirst("#chr-content");
    if (!content) return null;

    content.select("script, style, iframe, ins, noscript, .ads, .ad, .adsbygoogle").forEach(function (n) { n.remove(); });

    var html = content.html()
      .split("[Updated from F r e e w e b n o v e l. c o m]").join("")
      .replace(/<iframe .* src="\/\/ad.{0,2}-ads.com\/.*" style=".*"><\/iframe>/g, " ");
    var body = parseHtml(html).selectFirst("body");

    var paragraphs = body.select("p");
    if (paragraphs.length > 3) {
      return paragraphsToHtml(paragraphs
        .map(function (p) { return p.text().trim(); })
        .filter(function (s) { return s.length > 3; }));
    }
    return paragraphsToHtml([body.text()]);
  }
};
