// Example NovelForge source plugin.
//
// Copy this file, change the metadata and selectors for your site, and
// install it from Settings > Source plugins > (file icon). See
// docs/PLUGINS.md for the full API.
//
// The site below is fictional; its HTML shape is what the selectors expect:
//   list pages:   <div class="novel-item"><a href="/novel/slug"><img src="..."></a><h3>Name</h3></div>
//   novel page:   h1.title, .author, .summary, .status, ul.chapters li a
//   chapter page: #chapter-content

var SITE = "https://novels.example.com";

function parseList(doc) {
  return doc.select(".novel-item").map(function (item) {
    var link = item.selectFirst("a");
    var img = item.selectFirst("img");
    return {
      name: item.selectFirst("h3").text(),
      path: link.attr("href"),                 // relative paths are resolved against `site`
      cover: img ? img.absUrl("src") : null
    };
  });
}

module.exports = {
  id: "example-novels",
  name: "Example Novels",
  version: "1.0.0",
  site: SITE,
  lang: "en",

  popularNovels: function (page) {
    return parseList(fetchHtml(SITE + "/popular?page=" + page));
  },

  searchNovels: function (query, page) {
    var doc = fetchHtml(SITE + "/search?q=" + encodeURIComponent(query) + "&page=" + page);
    return parseList(doc);
  },

  parseNovel: function (url) {
    var doc = fetchHtml(url);
    var author = doc.selectFirst(".author");
    var status = doc.selectFirst(".status");
    return {
      name: doc.selectFirst("h1.title").text(),
      cover: (doc.selectFirst(".cover img") || { absUrl: function () { return null; } }).absUrl("src"),
      author: author ? author.text() : "",
      summary: doc.selectFirst(".summary").text(),
      status: status ? status.text() : "Unknown",
      chapters: doc.select("ul.chapters li a").map(function (a) {
        return { name: a.text(), url: a.absUrl("href") };
      })
    };
  },

  parseChapter: function (url) {
    var content = fetchHtml(url).selectFirst("#chapter-content");
    if (!content) throw new Error("no chapter content at " + url);
    content.select(".ads, script").forEach(function (n) { n.remove(); });
    return content.html();
  }
};
