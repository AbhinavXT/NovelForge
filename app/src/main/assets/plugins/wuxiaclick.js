// WuxiaClick — rewritten for the 2026 redesign of wuxia.click.
//
// The site is a Next.js app that embeds each page's data as JSON in
// <script id="__NEXT_DATA__">, so this reads that instead of scraping
// markup (the previous version's Mantine selectors no longer exist).
//
//   lists    /browse?order_by=&genre=&tag=&page=   props.pageProps.listing
//   search   /search?q=&page=                      props.pageProps.result
//   novel    /novel/<slug>                         props.pageProps.novel, .toc (100 per page)
//   toc page /_next/data/<buildId>/novel/<slug>.json?page=N
//   chapter  /chapter/<slug>-<number>              props.pageProps.data.chapter.paragraphs
//
// Novel and chapter URLs have the same shape as before, so ids of novels
// already in libraries (qwclick_<base64url(novel url)>) are unchanged.

var SITE = "https://wuxia.click";
var CDN = "https://cdn.wuxiaworld.eu/";

function nextData(html) {
  var m = /<script id="__NEXT_DATA__" type="application\/json">([\s\S]*?)<\/script>/.exec(html);
  if (!m) throw new Error("page has no __NEXT_DATA__");
  return JSON.parse(m[1]);
}

function getPage(path) {
  var r = fetch(SITE + path);
  if (!r.ok) throw new Error("HTTP " + r.status + " for " + path);
  return nextData(r.text());
}

/** A list page, or null past the last page (the site answers 404 there). */
function getListPage(path) {
  var r = fetch(SITE + path);
  if (r.status === 404) return null;
  if (!r.ok) throw new Error("HTTP " + r.status + " for " + path);
  return nextData(r.text());
}

function cover(n, size) {
  if (n.coverKey) return CDN + n.coverKey + "-" + size + ".webp";
  if (n.coverOriginal && /^https?:\/\//.test(n.coverOriginal)) return n.coverOriginal;
  return null;
}

function item(n) {
  return {
    url: SITE + "/novel/" + n.slug,
    name: n.title,
    cover: cover(n, 240),
    author: n.authorName || "",
    summary: n.latestChapterTitle ? "Latest: " + n.latestChapterTitle : ""
  };
}

function query(params) {
  var parts = [];
  for (var k in params) {
    if (params[k] != null && params[k] !== "") parts.push(k + "=" + encodeURIComponent(params[k]));
  }
  return parts.length ? "?" + parts.join("&") : "";
}

var STATUS = { ONGOING: "Ongoing", COMPLETED: "Completed", HIATUS: "Hiatus", DROPPED: "Dropped" };

module.exports = {
  id: "wuxiaclick",
  name: "WuxiaClick",
  version: "2.0.0",
  site: SITE,
  lang: "en",

  filters: {
    categories: [{ label: "All", value: "" }].concat([
      {"label": "Action", "value": "action"},
      {"label": "Adult", "value": "adult"},
      {"label": "Adventure", "value": "adventure"},
      {"label": "Comedy", "value": "comedy"},
      {"label": "Drama", "value": "drama"},
      {"label": "Ecchi", "value": "ecchi"},
      {"label": "Fantasy", "value": "fantasy"},
      {"label": "Gender Bender", "value": "gender-bender"},
      {"label": "Harem", "value": "harem"},
      {"label": "Historical", "value": "historical"},
      {"label": "Horror", "value": "horror"},
      {"label": "Josei", "value": "josei"},
      {"label": "Martial Arts", "value": "martial-arts"},
      {"label": "Mature", "value": "mature"},
      {"label": "Mecha", "value": "mecha"},
      {"label": "MTL", "value": "mtl"},
      {"label": "Mystery", "value": "mystery"},
      {"label": "Psychological", "value": "psychological"},
      {"label": "Romance", "value": "romance"},
      {"label": "School Life", "value": "school-life"},
      {"label": "Sci-fi", "value": "sci-fi"},
      {"label": "Seinen", "value": "seinen"},
      {"label": "Shoujo", "value": "shoujo"},
      {"label": "Shoujo Ai", "value": "shoujo-ai"},
      {"label": "Shounen", "value": "shounen"},
      {"label": "Shounen Ai", "value": "shounen-ai"},
      {"label": "Slice of Life", "value": "slice-of-life"},
      {"label": "Smut", "value": "smut"},
      {"label": "Sports", "value": "sports"},
      {"label": "Supernatural", "value": "supernatural"},
      {"label": "Tragedy", "value": "tragedy"},
      {"label": "Wuxia", "value": "wuxia"},
      {"label": "Xianxia", "value": "xianxia"},
      {"label": "Xuanhuan", "value": "xuanhuan"},
      {"label": "Yaoi", "value": "yaoi"},
      {"label": "Yuri", "value": "yuri"}
    ]),
    orderBys: [
      { label: "Popular", value: "popular" },
      { label: "Trending", value: "trending" },
      { label: "Popular this month", value: "monthly" },
      { label: "Most views", value: "views" },
      { label: "Rating", value: "rating" },
      { label: "Latest updates", value: "latest" },
      { label: "New", value: "new" },
      { label: "Most chapters", value: "chapters" },
      { label: "Name", value: "name" }
    ],
    tags: [{ label: "All", value: "" }].concat([
      {"label": "Abandoned Children", "value": "abandoned-children"},
      {"label": "Absent Parents", "value": "absent-parents"},
      {"label": "Abusive Characters", "value": "abusive-characters"},
      {"label": "Academy", "value": "academy"},
      {"label": "Acting", "value": "acting"},
      {"label": "Adapted to Anime", "value": "adapted-to-anime"},
      {"label": "Adapted to Drama", "value": "adapted-to-drama"},
      {"label": "Adapted to Drama CD", "value": "adapted-to-drama-cd"},
      {"label": "Adapted to Manga", "value": "adapted-to-manga"},
      {"label": "Adapted to Manhua", "value": "adapted-to-manhua"},
      {"label": "Adapted to Manhwa", "value": "adapted-to-manhwa"},
      {"label": "Adopted Children", "value": "adopted-children"},
      {"label": "Adventurers", "value": "adventurers"},
      {"label": "Age Progression", "value": "age-progression"},
      {"label": "Age Regression", "value": "age-regression"},
      {"label": "Alchemy", "value": "alchemy"},
      {"label": "Alternate World", "value": "alternate-world"},
      {"label": "Amnesia", "value": "amnesia"},
      {"label": "Ancient China", "value": "ancient-china"},
      {"label": "Ancient Times", "value": "ancient-times"},
      {"label": "Animal Characteristics", "value": "animal-characteristics"},
      {"label": "Antihero Protagonist", "value": "antihero-protagonist"},
      {"label": "Apocalypse", "value": "apocalypse"},
      {"label": "Appearance Changes", "value": "appearance-changes"},
      {"label": "Appearance Different from Actual Age", "value": "appearance-different-from-actual-age"},
      {"label": "Aristocracy", "value": "aristocracy"},
      {"label": "Arranged Marriage", "value": "arranged-marriage"},
      {"label": "Arrogant Characters", "value": "arrogant-characters"},
      {"label": "Artifacts", "value": "artifacts"},
      {"label": "Artificial Intelligence", "value": "artificial-intelligence"},
      {"label": "Assassins", "value": "assassins"},
      {"label": "Average-looking Protagonist", "value": "average-looking-protagonist"},
      {"label": "Beast Companions", "value": "beast-companions"},
      {"label": "Beastkin", "value": "beastkin"},
      {"label": "Beasts", "value": "beasts"},
      {"label": "Beautiful Female Lead", "value": "beautiful-female-lead"},
      {"label": "Betrayal", "value": "betrayal"},
      {"label": "Bickering Couple", "value": "bickering-couple"},
      {"label": "Black Belly", "value": "black-belly"},
      {"label": "Bloodlines", "value": "bloodlines"},
      {"label": "Body Tempering", "value": "body-tempering"},
      {"label": "Broken Engagement", "value": "broken-engagement"},
      {"label": "Brother Complex", "value": "brother-complex"},
      {"label": "Bullying", "value": "bullying"},
      {"label": "Business Management", "value": "business-management"},
      {"label": "Businessmen", "value": "businessmen"},
      {"label": "Calm Protagonist", "value": "calm-protagonist"},
      {"label": "Carefree Protagonist", "value": "carefree-protagonist"},
      {"label": "Caring Protagonist", "value": "caring-protagonist"},
      {"label": "Cautious Protagonist", "value": "cautious-protagonist"},
      {"label": "Celebrities", "value": "celebrities"},
      {"label": "Character Growth", "value": "character-growth"},
      {"label": "Charismatic Protagonist", "value": "charismatic-protagonist"},
      {"label": "Charming Protagonist", "value": "charming-protagonist"},
      {"label": "Cheats", "value": "cheats"},
      {"label": "Child Abuse", "value": "child-abuse"},
      {"label": "Child Protagonist", "value": "child-protagonist"},
      {"label": "Childcare", "value": "childcare"},
      {"label": "Childhood Friends", "value": "childhood-friends"},
      {"label": "Childhood Love", "value": "childhood-love"},
      {"label": "Clever Protagonist", "value": "clever-protagonist"},
      {"label": "Clingy Lover", "value": "clingy-lover"},
      {"label": "Cohabitation", "value": "cohabitation"},
      {"label": "Cold Love Interests", "value": "cold-love-interests"},
      {"label": "Cold Protagonist", "value": "cold-protagonist"},
      {"label": "College/University", "value": "collegeuniversity"},
      {"label": "Comedic Undertone", "value": "comedic-undertone"},
      {"label": "Complex Family Relationships", "value": "complex-family-relationships"},
      {"label": "Confident Protagonist", "value": "confident-protagonist"},
      {"label": "Contracts", "value": "contracts"},
      {"label": "Cooking", "value": "cooking"},
      {"label": "Couple Growth", "value": "couple-growth"},
      {"label": "Cross-dressing", "value": "cross-dressing"},
      {"label": "Cruel Characters", "value": "cruel-characters"},
      {"label": "Cultivation", "value": "cultivation"},
      {"label": "Cunning Protagonist", "value": "cunning-protagonist"},
      {"label": "Cute Children", "value": "cute-children"},
      {"label": "Cute Protagonist", "value": "cute-protagonist"},
      {"label": "Cute Story", "value": "cute-story"},
      {"label": "Dark", "value": "dark"},
      {"label": "Death", "value": "death"},
      {"label": "Death of Loved Ones", "value": "death-of-loved-ones"},
      {"label": "Demi-Humans", "value": "demi-humans"},
      {"label": "Demon Lord", "value": "demon-lord"},
      {"label": "Demons", "value": "demons"},
      {"label": "Dense Protagonist", "value": "dense-protagonist"},
      {"label": "Depictions of Cruelty", "value": "depictions-of-cruelty"},
      {"label": "Depression", "value": "depression"},
      {"label": "Determined Protagonist", "value": "determined-protagonist"},
      {"label": "Devoted Love Interests", "value": "devoted-love-interests"},
      {"label": "Different Social Status", "value": "different-social-status"},
      {"label": "Discrimination", "value": "discrimination"},
      {"label": "Doctors", "value": "doctors"},
      {"label": "Doting Love Interests", "value": "doting-love-interests"},
      {"label": "Doting Older Siblings", "value": "doting-older-siblings"},
      {"label": "Doting Parents", "value": "doting-parents"},
      {"label": "Dragons", "value": "dragons"},
      {"label": "Dungeons", "value": "dungeons"},
      {"label": "Dwarfs", "value": "dwarfs"},
      {"label": "Early Romance", "value": "early-romance"},
      {"label": "Elemental Magic", "value": "elemental-magic"},
      {"label": "Elves", "value": "elves"},
      {"label": "Empires", "value": "empires"},
      {"label": "Enemies Become Allies", "value": "enemies-become-allies"},
      {"label": "Enemies Become Lovers", "value": "enemies-become-lovers"},
      {"label": "Engagement", "value": "engagement"},
      {"label": "European Ambience", "value": "european-ambience"},
      {"label": "F*llatio", "value": "fllatio"},
      {"label": "Familial Love", "value": "familial-love"},
      {"label": "Family", "value": "family"},
      {"label": "Family Conflict", "value": "family-conflict"},
      {"label": "Famous Protagonist", "value": "famous-protagonist"},
      {"label": "Fantasy Creatures", "value": "fantasy-creatures"},
      {"label": "Fantasy World", "value": "fantasy-world"},
      {"label": "Farming", "value": "farming"},
      {"label": "Fast Cultivation", "value": "fast-cultivation"},
      {"label": "Fast Learner", "value": "fast-learner"},
      {"label": "Fated Lovers", "value": "fated-lovers"},
      {"label": "Female Protagonist", "value": "female-protagonist"},
      {"label": "First Love", "value": "first-love"},
      {"label": "First-time Interc**rse", "value": "first-time-intercrse"},
      {"label": "Friendship", "value": "friendship"},
      {"label": "Futuristic Setting", "value": "futuristic-setting"},
      {"label": "Game Elements", "value": "game-elements"},
      {"label": "Gamers", "value": "gamers"},
      {"label": "Genius Protagonist", "value": "genius-protagonist"},
      {"label": "Ghosts", "value": "ghosts"},
      {"label": "Goddesses", "value": "goddesses"},
      {"label": "Godly Powers", "value": "godly-powers"},
      {"label": "Gods", "value": "gods"},
      {"label": "Gore", "value": "gore"},
      {"label": "Guilds", "value": "guilds"},
      {"label": "Handsome Male Lead", "value": "handsome-male-lead"},
      {"label": "Hard-Working Protagonist", "value": "hard-working-protagonist"},
      {"label": "Hated Protagonist", "value": "hated-protagonist"},
      {"label": "Heartwarming", "value": "heartwarming"},
      {"label": "Heroes", "value": "heroes"},
      {"label": "Hidden Abilities", "value": "hidden-abilities"},
      {"label": "Hiding True Abilities", "value": "hiding-true-abilities"},
      {"label": "Hiding True Identity", "value": "hiding-true-identity"},
      {"label": "Human-Nonhuman Relationship", "value": "human-nonhuman-relationship"},
      {"label": "Humanoid Protagonist", "value": "humanoid-protagonist"},
      {"label": "Immortals", "value": "immortals"},
      {"label": "Incest", "value": "incest"},
      {"label": "Jealousy", "value": "jealousy"},
      {"label": "Kidnappings", "value": "kidnappings"},
      {"label": "Kind Love Interests", "value": "kind-love-interests"},
      {"label": "Kingdom Building", "value": "kingdom-building"},
      {"label": "Kingdoms", "value": "kingdoms"},
      {"label": "Knights", "value": "knights"},
      {"label": "Lack of Common Sense", "value": "lack-of-common-sense"},
      {"label": "Late Romance", "value": "late-romance"},
      {"label": "Lazy Protagonist", "value": "lazy-protagonist"},
      {"label": "Leadership", "value": "leadership"},
      {"label": "Level System", "value": "level-system"},
      {"label": "Loli", "value": "loli"},
      {"label": "Loner Protagonist", "value": "loner-protagonist"},
      {"label": "Love at First Sight", "value": "love-at-first-sight"},
      {"label": "Love Interest Falls in Love First", "value": "love-interest-falls-in-love-first"},
      {"label": "Love Triangles", "value": "love-triangles"},
      {"label": "Lovers Reunited", "value": "lovers-reunited"},
      {"label": "Loyal Subordinates", "value": "loyal-subordinates"},
      {"label": "Lucky Protagonist", "value": "lucky-protagonist"},
      {"label": "Magic", "value": "magic"},
      {"label": "Magic Beasts", "value": "magic-beasts"},
      {"label": "Magical Space", "value": "magical-space"},
      {"label": "Maids", "value": "maids"},
      {"label": "Male Protagonist", "value": "male-protagonist"},
      {"label": "Male Yandere", "value": "male-yandere"},
      {"label": "Manipulative Characters", "value": "manipulative-characters"},
      {"label": "Manly Gay Couple", "value": "manly-gay-couple"},
      {"label": "Marriage", "value": "marriage"},
      {"label": "Marriage of Convenience", "value": "marriage-of-convenience"},
      {"label": "Master-Disciple Relationship", "value": "master-disciple-relationship"},
      {"label": "Master-Servant Relationship", "value": "master-servant-relationship"},
      {"label": "Mature Protagonist", "value": "mature-protagonist"},
      {"label": "Medical Knowledge", "value": "medical-knowledge"},
      {"label": "Military", "value": "military"},
      {"label": "Misunderstandings", "value": "misunderstandings"},
      {"label": "Mob Protagonist", "value": "mob-protagonist"},
      {"label": "Modern Day", "value": "modern-day"},
      {"label": "Modern Knowledge", "value": "modern-knowledge"},
      {"label": "Monsters", "value": "monsters"},
      {"label": "Mpreg", "value": "mpreg"},
      {"label": "Multiple POV", "value": "multiple-pov"},
      {"label": "Multiple Realms", "value": "multiple-realms"},
      {"label": "Multiple Reincarnated Individuals", "value": "multiple-reincarnated-individuals"},
      {"label": "Multiple Transported Individuals", "value": "multiple-transported-individuals"},
      {"label": "Murders", "value": "murders"},
      {"label": "Mysterious Family Background", "value": "mysterious-family-background"},
      {"label": "Mysterious Past", "value": "mysterious-past"},
      {"label": "Mystery Solving", "value": "mystery-solving"},
      {"label": "Naive Protagonist", "value": "naive-protagonist"},
      {"label": "Near-Death Experience", "value": "near-death-experience"},
      {"label": "Nobles", "value": "nobles"},
      {"label": "Non-humanoid Protagonist", "value": "non-humanoid-protagonist"},
      {"label": "Obsessive Love", "value": "obsessive-love"},
      {"label": "Older Love Interests", "value": "older-love-interests"},
      {"label": "Omegaverse", "value": "omegaverse"},
      {"label": "Oneshot", "value": "oneshot"},
      {"label": "Orphans", "value": "orphans"},
      {"label": "Otome Game", "value": "otome-game"},
      {"label": "Overpowered Protagonist", "value": "overpowered-protagonist"},
      {"label": "Past Plays a Big Role", "value": "past-plays-a-big-role"},
      {"label": "Past Trauma", "value": "past-trauma"},
      {"label": "Persistent Love Interests", "value": "persistent-love-interests"},
      {"label": "Personality Changes", "value": "personality-changes"},
      {"label": "Pets", "value": "pets"},
      {"label": "Politics", "value": "politics"},
      {"label": "Polygamy", "value": "polygamy"},
      {"label": "Poor Protagonist", "value": "poor-protagonist"},
      {"label": "Poor to Rich", "value": "poor-to-rich"},
      {"label": "Popular Love Interests", "value": "popular-love-interests"},
      {"label": "Possessive Characters", "value": "possessive-characters"},
      {"label": "Power Couple", "value": "power-couple"},
      {"label": "Power Struggle", "value": "power-struggle"},
      {"label": "Pragmatic Protagonist", "value": "pragmatic-protagonist"},
      {"label": "Pregnancy", "value": "pregnancy"},
      {"label": "Previous Life Talent", "value": "previous-life-talent"},
      {"label": "Proactive Protagonist", "value": "proactive-protagonist"},
      {"label": "Protagonist Falls in Love First", "value": "protagonist-falls-in-love-first"},
      {"label": "Protagonist Strong from the Start", "value": "protagonist-strong-from-the-start"},
      {"label": "Quirky Characters", "value": "quirky-characters"},
      {"label": "R*pe", "value": "rpe"},
      {"label": "R*pe Victim Becomes Lover", "value": "rpe-victim-becomes-lover"},
      {"label": "R-15", "value": "r-15"},
      {"label": "R-18", "value": "r-18"},
      {"label": "Reincarnated in a Game World", "value": "reincarnated-in-a-game-world"},
      {"label": "Reincarnated in Another World", "value": "reincarnated-in-another-world"},
      {"label": "Reincarnation", "value": "reincarnation"},
      {"label": "Religions", "value": "religions"},
      {"label": "Revenge", "value": "revenge"},
      {"label": "Reverse Harem", "value": "reverse-harem"},
      {"label": "Romantic Subplot", "value": "romantic-subplot"},
      {"label": "Royalty", "value": "royalty"},
      {"label": "Ruthless Protagonist", "value": "ruthless-protagonist"},
      {"label": "S*aves", "value": "saves"},
      {"label": "S*xual Abuse", "value": "sxual-abuse"},
      {"label": "Schemes And Conspiracies", "value": "schemes-and-conspiracies"},
      {"label": "Second Chance", "value": "second-chance"},
      {"label": "Secret Identity", "value": "secret-identity"},
      {"label": "Secrets", "value": "secrets"},
      {"label": "Seme Protagonist", "value": "seme-protagonist"},
      {"label": "Shameless Protagonist", "value": "shameless-protagonist"},
      {"label": "Sharp-tongued Characters", "value": "sharp-tongued-characters"},
      {"label": "Short Story", "value": "short-story"},
      {"label": "Showbiz", "value": "showbiz"},
      {"label": "Siblings", "value": "siblings"},
      {"label": "Siblings Not Related by Blood", "value": "siblings-not-related-by-blood"},
      {"label": "Sickly Characters", "value": "sickly-characters"},
      {"label": "Slow Romance", "value": "slow-romance"},
      {"label": "Smart Couple", "value": "smart-couple"},
      {"label": "Special Abilities", "value": "special-abilities"},
      {"label": "Spirits", "value": "spirits"},
      {"label": "Strength-based Social Hierarchy", "value": "strength-based-social-hierarchy"},
      {"label": "Strong Love Interests", "value": "strong-love-interests"},
      {"label": "Strong to Stronger", "value": "strong-to-stronger"},
      {"label": "Survival", "value": "survival"},
      {"label": "Sword And Magic", "value": "sword-and-magic"},
      {"label": "Sword Wielder", "value": "sword-wielder"},
      {"label": "System Administrator", "value": "system-administrator"},
      {"label": "Threesome", "value": "threesome"},
      {"label": "Time Skip", "value": "time-skip"},
      {"label": "Time Travel", "value": "time-travel"},
      {"label": "Torture", "value": "torture"},
      {"label": "Tragic Past", "value": "tragic-past"},
      {"label": "Transmigration", "value": "transmigration"},
      {"label": "Transported into a Game World", "value": "transported-into-a-game-world"},
      {"label": "Transported to Another World", "value": "transported-to-another-world"},
      {"label": "Tsundere", "value": "tsundere"},
      {"label": "Unconditional Love", "value": "unconditional-love"},
      {"label": "Underestimated Protagonist", "value": "underestimated-protagonist"},
      {"label": "Unique Cultivation Technique", "value": "unique-cultivation-technique"},
      {"label": "Unreliable Narrator", "value": "unreliable-narrator"},
      {"label": "Unrequited Love", "value": "unrequited-love"},
      {"label": "Vampires", "value": "vampires"},
      {"label": "Villainess Noble Girls", "value": "villainess-noble-girls"},
      {"label": "Virtual Reality", "value": "virtual-reality"},
      {"label": "Wars", "value": "wars"},
      {"label": "Weak Protagonist", "value": "weak-protagonist"},
      {"label": "Weak to Strong", "value": "weak-to-strong"},
      {"label": "Wealthy Characters", "value": "wealthy-characters"},
      {"label": "World Hopping", "value": "world-hopping"},
      {"label": "World Travel", "value": "world-travel"},
      {"label": "Yandere", "value": "yandere"},
      {"label": "Younger Love Interests", "value": "younger-love-interests"},
      {"label": "Younger Sisters", "value": "younger-sisters"},
      {"label": "Zombies", "value": "zombies"}
    ])
  },

  browseNovels: function (page, f) {
    var data = getListPage("/browse" + query({
      order_by: f.orderBy === "popular" ? null : f.orderBy,
      genre: f.category,
      tag: f.tag,
      page: page > 1 ? page : null
    }));
    var listing = data && data.props.pageProps.listing;
    if (!listing || listing.page < page) return []; // past the last page
    return listing.items.map(item);
  },

  popularNovels: function (page) {
    return module.exports.browseNovels(page, { category: null, orderBy: null, tag: null });
  },

  searchNovels: function (q, page) {
    var data = getListPage("/search" + query({ q: q, page: page > 1 ? page : null }));
    var result = data && data.props.pageProps.result;
    return result ? result.items.map(item) : [];
  },

  parseNovel: function (url) {
    var data = getPage(url.replace(SITE, ""));
    var props = data.props.pageProps;
    var n = props.novel;
    if (!n) return null;

    // The table of contents is paged; fetch the rest through the lighter
    // JSON data route (its build id changes with every deploy).
    var chapters = [];
    function add(toc) {
      toc.items.forEach(function (c) {
        chapters.push({ name: c.title || ("Chapter " + c.number), url: SITE + "/chapter/" + n.slug + "-" + c.number, number: c.number });
      });
    }
    add(props.toc);
    (props.toc.ranges || []).forEach(function (range) {
      if (range.page === props.toc.range.page) return;
      var r = fetch(SITE + "/_next/data/" + data.buildId + "/novel/" + n.slug + ".json?page=" + range.page);
      var toc = r.ok ? JSON.parse(r.text()).pageProps.toc : getPage("/novel/" + n.slug + "?page=" + range.page).props.pageProps.toc;
      add(toc);
    });

    return {
      name: n.title,
      cover: cover(n, 480),
      author: (n.author && n.author.name) || n.authorName || "",
      summary: n.description || "",
      status: STATUS[n.status] || "Unknown",
      chapters: chapters
    };
  },

  parseChapter: function (url) {
    var data = getPage(url.replace(SITE, "")).props.pageProps.data;
    var paragraphs = data && data.chapter && data.chapter.paragraphs;
    if (!paragraphs || paragraphs.length === 0) return null;
    return paragraphsToHtml(paragraphs);
  }
};
