// FreeWebNovel — port of the former built-in FreeWebNovelSource.
// Sister site of LibRead with identical HTML; only the domain and watermarks differ.
//
// Keeps the old ids so existing libraries keep working:
//   novel   fwn_<slug>
//   chapter fwn_<slug>_<chapterSlug>

var SITE = "https://freewebnovel.com";
var PREFIX = "fwn";
var USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.93 Safari/537.36";

/** Chapter watermarks: [text, ignoreCase]. */
var WATERMARKS = [["New novel chapters are published on Freewebnovel.com.", false], ["The source of this content is Freewebnᴏvel.com.", false], ["☞ We are moving Freewebnovel.com to Libread.com, Please visit libread.com for more chapters! ☜", false], ["freewebnovel.com", true]];

/** The page as a Node, or null when the request fails. */
function get(url) {
  try {
    var r = fetch(url, {
      headers: {
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.5",
        "Referer": SITE
      }
    });
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

function textOrNull(node) {
  return node ? node.text().trim() : null;
}

function itemFrom(h3, coverSrc) {
  var title = (h3.attr("title") || "").trim() ? h3.attr("title").trim() : h3.text().trim();
  if (!title) return null;
  var href = h3.attr("href") || "";
  if (!href.trim()) return null;
  var url = fixUrl(href);
  var slug = slugOf(url);
  if (!slug) return null;
  return { id: PREFIX + "_" + slug, name: title, url: url, author: "Unknown", cover: fixUrlNull(coverSrc), summary: "" };
}

function parseListing(doc) {
  var novels = [];
  var seen = {};
  doc.select("div.ul-list1.ul-list1-2.ss-custom > div.li-row").forEach(function (item) {
    var h3 = item.selectFirst("h3.tit > a");
    if (!h3) return;
    var img = item.selectFirst("div.pic > a > img");
    var n = itemFrom(h3, img ? img.attr("src") : null);
    if (!n || seen[n.id]) return;
    seen[n.id] = true;
    novels.push(n);
  });
  return novels;
}

function escapeRegExp(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

module.exports = {
  id: "freewebnovel",
  name: "FreeWebNovel",
  version: "1.0.0",
  site: SITE,
  lang: "en",
  // The built-in source kept translator/editor credit lines.
  stripCredits: false,

  filters: {
    orderBys: [
      { label: "Latest Release", value: "latest-release" },
      { label: "Latest Novels", value: "latest-novel" },
      { label: "Completed Novels", value: "completed-novel" }
    ],
    tags: [
      "Action", "Adult", "Adventure", "Comedy", "Drama", "Ecchi",
      "Fantasy", "Gender Bender", "Harem", "Historical", "Horror",
      "Josei", "Martial Arts", "Mature", "Mecha", "Mystery",
      "Psychological", "Reincarnation", "Romance", "School Life",
      "Sci-fi", "Seinen", "Shoujo", "Shounen Ai", "Shounen",
      "Slice of Life", "Smut", "Sports", "Supernatural", "Tragedy",
      "Wuxia", "Xianxia", "Xuanhuan", "Yaoi"
    ].map(function (t) { return { label: t, value: t }; })
  },

  browseNovels: function (page, f) {
    // Genre pages and sort pages are separate URL trees: a genre overrides the sort list.
    var url = f.tag
      ? SITE + "/genre/" + f.tag.split(" ").join("+") + "/" + page
      : SITE + "/sort/" + (f.orderBy || "latest-release") + "/" + page;
    var doc = get(url);
    return doc ? parseListing(doc) : [];
  },

  popularNovels: function (page) {
    return module.exports.browseNovels(page, { category: null, orderBy: null, tag: null });
  },

  searchNovels: function (query) {
    var r;
    try {
      r = fetch(SITE + "/search", {
        method: "POST",
        headers: {
          "User-Agent": USER_AGENT,
          "Referer": SITE,
          "X-Requested-With": "XMLHttpRequest",
          "Content-Type": "application/x-www-form-urlencoded",
          "Accept": "*/*"
        },
        body: "searchkey=" + encodeURIComponent(query)
      });
    } catch (e) {
      console.error("search error: " + e.message);
      return [];
    }
    if (!r.ok) {
      console.error("Search request failed: " + r.status);
      return [];
    }
    var doc = parseHtml(r.text(), SITE);
    var novels = [];
    doc.select("div.li-row > div.li > div.con").forEach(function (item) {
      var h3 = item.selectFirst("div.txt > h3.tit > a");
      if (!h3) return;
      var img = item.selectFirst("div.pic img");
      var n = itemFrom(h3, img ? img.attr("src") : null);
      if (n) novels.push(n);
    });
    return novels;
  },

  parseNovel: function (url) {
    var doc = get(url);
    if (!doc) return null;
    var slug = slugOf(url);

    var title = textOrNull(doc.selectFirst("h1.tit"));
    if (!title) return null;

    var userIcon = doc.selectFirst("span.glyphicon.glyphicon-user");
    var authorEl = userIcon ? userIcon.next() : null;
    var author = authorEl ? authorEl.text().trim() : "Unknown";

    // Elements.attr(): the first element that has the attribute.
    var cover = null;
    var imgs = doc.select("div.pic > img");
    for (var i = 0; i < imgs.length; i++) {
      if (imgs[i].attr("src") != null) { cover = fixUrlNull(imgs[i].attr("src")); break; }
    }

    var s3 = doc.selectFirst("span.s1.s3");
    var s2 = doc.selectFirst("span.s1.s2");
    var s3a = s3 ? s3.selectFirst("a") : null;
    var s2a = s2 ? s2.selectFirst("a") : null;
    var statusText = (s3a ? s3a.text() : s2a ? s2a.text() : "").toLowerCase().trim();
    var status = statusText === "ongoing" ? "Ongoing"
      : (statusText === "completed" || statusText === "complete") ? "Completed" : "Unknown";

    var chapters = [];
    doc.select("div.m-newest2 ul.ul-list5 li").forEach(function (li, index) {
      var a = li.selectFirst("a");
      if (!a) return;
      var name = a.text().trim();
      if (!name) return;
      var chapterUrl = fixUrl(a.attr("href") || "");
      chapters.push({ id: PREFIX + "_" + slug + "_" + slugOf(chapterUrl), number: index + 1, name: name, url: chapterUrl });
    });

    return {
      id: PREFIX + "_" + slug,
      name: title,
      author: author,
      cover: cover,
      summary: textOrNull(doc.selectFirst("div.inner")) || "",
      status: status,
      chapters: chapters
    };
  },

  parseChapter: function (url) {
    var doc = get(url);
    if (!doc) return null;
    var content = doc.selectFirst("div.txt");
    if (!content) return null;

    content.select(".notice-text").forEach(function (n) { n.remove(); });
    content.select("script, style, .ads, .ad, .adsbygoogle, ins, iframe, noscript").forEach(function (n) { n.remove(); });

    var html = content.html();
    WATERMARKS.forEach(function (w) {
      html = w[1] ? html.replace(new RegExp(escapeRegExp(w[0]), "gi"), "") : html.split(w[0]).join("");
    });
    var body = parseHtml(html).selectFirst("body");

    var paragraphs = body.select("p");
    var out = paragraphs.length > 3
      ? paragraphs.map(function (p) { return p.text().trim(); })
      : body.text().split(/\n{2,}/).map(function (s) { return s.trim(); });
    return paragraphsToHtml(out.filter(function (s) { return s.length > 3; }));
  }
};
