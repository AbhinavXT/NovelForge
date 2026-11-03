// Primordial Translation — port of the former built-in PrimordialTranslationSource.
// WordPress site with a Flavor-type novel reader theme:
//   series listing /series/, novel /series/{slug}/, search /?s={query}
//
// Keeps the old ids so existing libraries keep working:
//   novel   pt_<slug>
//   chapter pt_<slug>_<chapterSlug>

var SITE = "https://primodialtranslation.com";

var HEADERS = {
  "User-Agent": "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
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

/** java.net.URLEncoder.encode(s, "UTF-8") */
function urlEncode(s) {
  return encodeURIComponent(s).replace(/%20/g, "+").replace(/[!'()~]/g, function (c) {
    return "%" + c.charCodeAt(0).toString(16).toUpperCase();
  });
}

function textOrNull(node) {
  return node ? node.text().trim() : null;
}

/** .../series/some-novel/ -> some-novel */
function slugOf(url) {
  var s = url.replace(/\/+$/, "");
  s = s.substring(s.lastIndexOf("/") + 1);
  return s.slice(-5) === ".html" ? s.slice(0, -5) : s;
}

/** img src, else data-src (both absolute), else "". */
function imgUrl(img) {
  return img.absUrl("src") || img.absUrl("data-src") || "";
}

function card(el, seen) {
  var link = el.selectFirst("a[href*=/series/]") || el.selectFirst(".tt a, .bigor a, h2 a, h3 a, .ntitle a") || el.selectFirst("a[title]");
  if (!link) return null;
  var href = link.absUrl("href") || "";
  if (!href || href.indexOf("/series/") < 0) return null;
  var title = (link.attr("title") || "").trim() ? link.attr("title").trim() : link.text().trim();
  if (!title) return null;
  var slug = slugOf(href);
  if (!slug || seen[slug]) return null;
  seen[slug] = true;
  var img = el.selectFirst("img");
  return { id: "pt_" + slug, name: title, url: href, author: "Unknown", cover: (img && imgUrl(img)) || null, summary: "" };
}

function cards(nodes, seen) {
  var out = [];
  nodes.forEach(function (n) {
    var c = card(n, seen);
    if (c) out.push(c);
  });
  return out;
}

/** A value from the info table: the row containing `label`, its <a> or the part after ":". */
function metaValue(doc, label) {
  var lower = label.toLowerCase();
  var spans = doc.select(".infox .spe span, .tsinfo .imptdt, .infotable td, .info-desc .spe span");
  for (var i = 0; i < spans.length; i++) {
    var text = spans[i].text().trim();
    if (text.toLowerCase().indexOf(lower) >= 0) {
      var a = spans[i].selectFirst("a");
      if (a) return a.text().trim();
      var colon = text.indexOf(":");
      if (colon >= 0) return text.substring(colon + 1).trim();
    }
  }
  // Fallback: a <b>/<strong> label inside its parent's text.
  var bolds = doc.select("b, strong");
  for (var j = 0; j < bolds.length; j++) {
    var boldText = bolds[j].text();
    if (boldText.toLowerCase().indexOf(lower) >= 0) {
      var parent = bolds[j].parent();
      if (!parent) continue;
      var value = parent.text().split(boldText).join("").trim().replace(/^:+/, "").trim();
      if (value) return value;
    }
  }
  return null;
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

function findLargestTextContainer(doc) {
  var best = null;
  var max = 0;
  doc.select("div, article, section").forEach(function (el) {
    var length = (el.ownText() + joinText(el.select("p"))).length;
    var cls = (el.attr("class") || "").trim().toLowerCase();
    var id = (el.attr("id") || "").toLowerCase();
    if (cls.indexOf("nav") >= 0 || cls.indexOf("header") >= 0 || cls.indexOf("footer") >= 0 ||
        cls.indexOf("sidebar") >= 0 || id.indexOf("nav") >= 0 || id.indexOf("header") >= 0 ||
        id.indexOf("footer") >= 0) return;
    if (length > max && length > 500) {
      max = length;
      best = el;
    }
  });
  return best;
}

function firstNumber(s) {
  var m = /\d+/.exec(s);
  return m ? parseInt(m[0], 10) : 0;
}

module.exports = {
  id: "primordialtranslation",
  name: "Primordial Translation",
  version: "1.0.0",
  site: SITE,
  lang: "en",
  // The built-in source kept translator/editor credit lines.
  stripCredits: false,

  popularNovels: function (page) {
    var doc = get(page === 1 ? SITE + "/series/" : SITE + "/series/?page=" + page);
    if (!doc) return [];
    var seenIds = {};
    var items = doc.select(".bsx, .bs, article.bs, .listupd .bs, .soralist .bsx");
    if (items.length > 0) return cards(items, seenIds);

    // Fallback: every series link on the listing page.
    var novels = [];
    var seen = {};
    doc.select("a[href*=/series/]").forEach(function (link) {
      var href = link.absUrl("href") || "";
      if (href === SITE + "/series/" || seen[href]) return;
      if (href.indexOf(SITE + "/series/") !== 0) return;
      seen[href] = true;
      var img = link.selectFirst("img");
      var alt = img ? (img.attr("alt") || "").trim() : null;
      var title = link.text().trim() || alt || "";
      if (!title) return;
      var slug = slugOf(href);
      if (!slug || seenIds[slug]) return;
      seenIds[slug] = true;
      novels.push({ id: "pt_" + slug, name: title, url: href, author: "Unknown", cover: (img && imgUrl(img)) || null, summary: "" });
    });
    return novels;
  },

  searchNovels: function (query) {
    var doc = get(SITE + "/?s=" + urlEncode(query));
    if (!doc) return [];
    var seen = {};
    var items = doc.select(".bsx, .bs, article.bs, .listupd .bs");
    // Fallback: standard WordPress search results.
    return cards(items.length > 0 ? items : doc.select("article, .post, .search-result"), seen);
  },

  parseNovel: function (url) {
    var doc = get(url);
    if (!doc) return null;
    var slug = slugOf(url);

    var title = textOrNull(doc.selectFirst(".entry-title")) || textOrNull(doc.selectFirst("h1.entry-title")) ||
      textOrNull(doc.selectFirst(".infox h1")) || textOrNull(doc.selectFirst(".seriestuheader h1")) ||
      textOrNull(doc.selectFirst("h1"));
    if (title == null) return null;

    var author = metaValue(doc, "Author") || metaValue(doc, "Writer") ||
      textOrNull(doc.selectFirst(".infox span:contains(Author) + span")) || "Unknown";

    // og:image is only consulted when an image was found but has no usable src.
    var cover = null;
    var img = doc.selectFirst(".thumb img, .seriesthumbnail img, .seriestucontl img, .infoanime img") ||
      doc.selectFirst("img.wp-post-image") || doc.selectFirst(".thumbook img, .novel-cover img, .summary_image img");
    if (img) {
      cover = imgUrl(img);
      if (!cover.trim()) {
        var og = doc.selectFirst("meta[property=og:image]");
        cover = og ? og.attr("content") : null;
      }
      if (cover != null && !cover.trim()) cover = null;
    }

    var description = textOrNull(doc.selectFirst(".entry-content[itemprop=description], .synp .entry-content, .seriestucontent .entry-content, .summary .entry-content")) ||
      textOrNull(doc.selectFirst(".entry-content p")) || "";

    var statusText = (metaValue(doc, "Status") || textOrNull(doc.selectFirst(".infox span:contains(Status) + span")) || "").toLowerCase();
    var status = statusText.indexOf("completed") >= 0 ? "Completed" : statusText.indexOf("ongoing") >= 0 ? "Ongoing" : "Unknown";

    var chapters = [];
    var seen = {};
    doc.select(".eplister ul li a, .chapterlist li a, #chapterlist ul li a, .bxcl ul li a").forEach(function (link, index) {
      var label = link.selectFirst(".chapternum, .epl-num, span");
      var name = label ? label.text().trim() : link.text().trim();
      if (!name) return;
      var chapterUrl = link.absUrl("href");
      if (!chapterUrl) return;
      var id = "pt_" + slug + "_" + slugOf(chapterUrl);
      if (seen[id]) return;
      seen[id] = true;
      chapters.push({ id: id, number: index + 1, name: name, url: chapterUrl });
    });

    // The theme lists chapters newest first; reverse so chapter 1 comes first.
    if (chapters.length > 1 && firstNumber(chapters[0].name.toLowerCase()) > firstNumber(chapters[chapters.length - 1].name.toLowerCase())) {
      chapters.reverse();
      chapters.forEach(function (c, i) { c.number = i + 1; });
    }

    return { id: "pt_" + slug, name: title, author: author, cover: cover, summary: description, status: status, chapters: chapters };
  },

  parseChapter: function (url) {
    var doc = get(url);
    if (!doc) return null;
    var content = doc.selectFirst(".epcontent, .entry-content, .rdminimal, #readerarea, .text-left, .chapter-content") ||
      findLargestTextContainer(doc);
    if (!content) return null;

    content.select("script, style, .ads, .ad, .advertisement, .google-auto-placed, .adsbygoogle, ins, iframe, " +
      "noscript, .hidden, [style*=display:none], .code-block, .adblock, .sharedaddy, .wp-block-buttons")
      .forEach(function (n) { n.remove(); });

    var html = content.html().split("primodialtranslation.com").join("").replace(/primordial translation/gi, "");
    var body = parseHtml(html).selectFirst("body");

    var paragraphs = body.select("p");
    var out = paragraphs.length > 3
      ? paragraphs.map(function (p) { return p.text().trim(); })
      : body.text().split(/[ \t\n\x0B\f\r]{2,}/).map(function (s) { return s.trim(); });
    return paragraphsToHtml(out.filter(function (s) { return s.length > 3; }));
  }
};
