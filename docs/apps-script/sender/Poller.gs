/**
 * Paramanu Seniors Notices - RSS poller
 *
 * A second file in the SENDER Apps Script project, driven by a time-based trigger rather than by a
 * person. It reads the alerts feed published by the website and sends any alert it has not sent
 * before to the notices topic.
 *
 * It does not touch sendNotice, doGet, or anything the console page calls. The web app keeps doing
 * exactly one thing; this file is a separate entry point that happens to live in the same project
 * so it can share the credentials and the Firebase plumbing that are already set up here.
 *
 * NAMING
 *   Apps Script gives every .gs file in a project one shared global scope, so two files declaring
 *   the same function name is not an error - the second silently replaces the first, and the
 *   symptom appears somewhere else entirely. Everything this file owns is therefore prefixed
 *   `poller`. The only names it reuses from Code.gs are the shared plumbing it deliberately does
 *   not duplicate:
 *
 *     property_()      accessToken_()     firebase_()     nextLogId_()     TOPIC     TOPIC_OVERRIDE
 *
 *   If you rename any of those in Code.gs, this file has to follow.
 *
 * WHAT IT DELIBERATELY DOES NOT REUSE
 *   sendNotice(), because it calls requireEditor_() and checkPin_(). A trigger has no signed-in
 *   caller and cannot type a PIN. Under an owner-installed trigger Session.getActiveUser() happens
 *   to return the owner, so requireEditor_ would pass today and start failing the day somebody else
 *   reinstalls the trigger - a failure that surfaces months later, in a cron, as silence. The
 *   poller has its own delivery function instead, and the PIN stays what it is: a human gate on the
 *   human path.
 *
 * SETUP (once)
 *  1. Paste this into the sender project as a new file, Poller.gs.
 *  2. Script Properties. SERVICE_ACCOUNT_JSON, DATABASE_URL and PROJECT_ID are already set for the
 *     sender and are reused as-is. Optionally add:
 *       ALERTS_FEED_URL    = https://parmanuseniorhealth-github-io.vercel.app/alerts/index.xml   (the default)
 *       ALERTS_MAX_PER_RUN = 5                                                    (see the ceiling)
 *  3. Run testPollerParseFixture(). No network, no credentials, sends nothing.
 *  4. Run pollerDryRun(). Reads the real feed, logs what a real run would send, sends nothing.
 *  5. Run pollerSeedFeed() ONCE. Marks every alert currently in the feed as already handled,
 *     without sending. Skip it and the first trigger run broadcasts the whole back catalogue.
 *  6. Run pollerInstallTrigger() to create the 15-minute trigger.
 *
 * Adding this file does not change the web app. There is no need to redeploy it - and redeploying
 * is worth avoiding here, since a new version resets nothing but is the step most easily done
 * half-way.
 */

var POLLER_VERSION = '2026-09-08-poller';

var POLLER_DEFAULT_FEED_URL = 'https://parmanuseniorhealth-github-io.vercel.app/alerts/index.xml';

/**
 * Most alerts the poller will send in one run.
 *
 * A ceiling, not a throttle. If something goes wrong upstream - the feed is regenerated with new
 * guids, /alertsSeen is cleared, a bulk import lands in content/alerts/ - the damage is five
 * notifications to four hundred phones instead of fifty. The rest stay unsent and visible in the
 * log, which is a problem somebody can look at rather than one that has already happened.
 */
var POLLER_MAX_PER_RUN = 5;

/** A repeat of the same guid is impossible, but a feed can still carry a title twice. */
var POLLER_SEEN_PATH = '/alertsSeen';

function pollerFeedUrl_() {
  return PropertiesService.getScriptProperties().getProperty('ALERTS_FEED_URL') || POLLER_DEFAULT_FEED_URL;
}

function pollerMaxPerRun_() {
  var configured = parseInt(PropertiesService.getScriptProperties().getProperty('ALERTS_MAX_PER_RUN'), 10);
  return (configured > 0) ? configured : POLLER_MAX_PER_RUN;
}

// ---------------------------------------------------------------- reading the feed

/**
 * Fetches the feed.
 *
 * muteHttpExceptions so a bad response is reported as a poller problem with the URL and the body
 * attached, rather than as an opaque Apps Script exception that says nothing about which fetch
 * failed.
 */
function pollerFetchFeed_(url) {
  var response = UrlFetchApp.fetch(url, { muteHttpExceptions: true, followRedirects: true });
  var code = response.getResponseCode();
  if (code !== 200) {
    throw new Error('Feed fetch failed: ' + url + ' returned ' + code + ' ' +
                    response.getContentText().slice(0, 300));
  }
  return response.getContentText();
}

/**
 * Turns the feed XML into plain objects, oldest first.
 *
 * Oldest first because they are sent in that order, so a phone that was switched off for two days
 * shows Tuesday's notice above Wednesday's rather than the reverse.
 *
 * An enclosure of type application/pdf becomes a pdfUrl, and any image/* enclosure becomes an
 * imageUrl. They are separate fields rather than one generic attachment because the app treats them
 * differently: an image is shown as it arrives, a PDF has its first page rendered. An enclosure of
 * any other type is left in enclosureUrl/enclosureType, where pollerSendAlert_ logs and drops it.
 *
 * RSS allows at most one enclosure per item, so an alert carries a picture or a circular, never
 * both. The app tolerates both being present; the feed simply cannot express it.
 */
function pollerParseFeed_(xml) {
  // Built here rather than at file scope: a getNamespace call at load time runs before the project
  // has finished loading its other files, and load-order bugs in Apps Script are miserable to find.
  var contentNs = XmlService.getNamespace('content', 'http://purl.org/rss/1.0/modules/content/');

  var channel = XmlService.parse(xml).getRootElement().getChild('channel');
  if (!channel) {
    throw new Error('Feed has no channel element; is ALERTS_FEED_URL pointing at an RSS feed?');
  }

  var items = channel.getChildren('item').map(function (item) {
    var enclosure = item.getChild('enclosure');
    var url = '';
    var mime = '';
    if (enclosure) {
      var urlAttr = enclosure.getAttribute('url');
      var typeAttr = enclosure.getAttribute('type');
      url = urlAttr ? urlAttr.getValue() : '';
      mime = typeAttr ? typeAttr.getValue() : '';
    }

    var encoded = item.getChild('encoded', contentNs);

    return {
      guid: pollerText_(item, 'guid') || pollerText_(item, 'link'),
      title: pollerText_(item, 'title'),
      body: pollerText_(item, 'description'),
      link: pollerText_(item, 'link'),
      pubDate: pollerText_(item, 'pubDate'),
      html: encoded ? String(encoded.getText() || '') : '',
      enclosureUrl: url,
      enclosureType: mime,
      pdfUrl: (mime === 'application/pdf') ? url : '',
      imageUrl: (mime.indexOf('image/') === 0) ? url : ''
    };
  });

  // The feed is newest first, so reverse rather than sort on pubDate: the generator has already
  // ordered it, and parsing RFC-822 dates here is one more thing that can be subtly wrong.
  return items.reverse();
}

function pollerText_(element, name) {
  var child = element.getChild(name);
  return child ? String(child.getText() || '').trim() : '';
}

// ---------------------------------------------------------------- what has already gone out

/**
 * Firebase key for a guid.
 *
 * Realtime Database keys cannot contain . $ # [ ] or /, and a guid is a URL, which contains most of
 * them. Hashing rather than escaping keeps the key a fixed, predictable length, and means a guid
 * that changes shape later cannot collide with an escaped form of an older one.
 */
function pollerSeenKey_(guid) {
  var bytes = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, String(guid), Utilities.Charset.UTF_8);
  return bytes.map(function (b) {
    return ('0' + (b & 0xff).toString(16)).slice(-2);
  }).join('').slice(0, 32);
}

function pollerIsSeen_(guid) {
  return firebase_('get', POLLER_SEEN_PATH + '/' + pollerSeenKey_(guid) + '.json?shallow=true') !== null;
}

/**
 * Records an alert as handled.
 *
 * Written under /alertsSeen, which belongs to the poller alone - the console and the sender neither
 * read nor write it. The guid and title are stored next to the hash so the table is legible to a
 * human debugging it later; a page of bare hashes tells you nothing about which notice is which.
 */
function pollerMarkSeen_(item, outcome) {
  firebase_('put', POLLER_SEEN_PATH + '/' + pollerSeenKey_(item.guid) + '.json', {
    guid: item.guid,
    title: item.title,
    pubDate: item.pubDate,
    handledAt: new Date().toISOString(),
    outcome: outcome,
    pollerVersion: POLLER_VERSION
  });
}

// ---------------------------------------------------------------- sending

/**
 * Sends one notice and records it. The poller's equivalent of sendNotice, without the human gates.
 *
 * Recorded before the send, for the same reason the sender does it that way: a notice that went out
 * but was never logged is worse than one logged and not sent, because the second is visible and the
 * first is not.
 *
 * Writes to the same /sent node as the sender, so listSent() in the console shows notices from both
 * with source: 'poller' distinguishing them. It shares nextLogId_ too, so the two cannot hand out
 * the same id - the residual race is a poller send and a human send inside the same millisecond,
 * and the loser stays unseen and is retried on the next run.
 */
function pollerDeliver_(title, body, extras) {
  title = (title || '').toString().trim();
  body = (body || '').toString().trim();

  if (!title) throw new Error('A title is required. It is what people read first, and often all they read.');
  if (title.length > 120) throw new Error('Title is too long (' + title.length + '); keep it under 120 characters.');
  if (body.length > 900) throw new Error('Body is too long (' + body.length + '); keep it under 900 characters.');

  var logId = nextLogId_();
  var sentAt = new Date().toISOString();

  var record = {
    title: title,
    body: body,
    category: 'NOTICES',
    sentAt: sentAt,
    source: 'poller',
    // Not the trigger owner's email. Session.getActiveUser() happens to return the owner under an
    // owner-installed trigger, so borrowing it would attribute every automated send to whoever
    // last reinstalled the trigger -- and would start lying the day somebody else does.
    sentBy: 'poller',
    scriptVersion: POLLER_VERSION
  };
  for (var field in extras) {
    if (extras.hasOwnProperty(field)) record[field] = extras[field];
  }
  firebase_('put', '/sent/' + logId + '.json', record);

  // Data-only, exactly as the sender does it. A notification block here would make the FCM SDK draw
  // the tray notification itself while the app is backgrounded: onMessageReceived would never run,
  // the entitlement check would be skipped, and nothing would be written to the phone's history.
  var data = { logId: logId, title: title, body: body, category: 'NOTICES' };
  for (var extra in extras) {
    if (extras.hasOwnProperty(extra)) data[extra] = String(extras[extra]);
  }

  var message = {
    message: {
      topic: TOPIC_OVERRIDE || TOPIC,
      android: { priority: 'high' },
      data: data
    }
  };

  var response = UrlFetchApp.fetch(
    'https://fcm.googleapis.com/v1/projects/' + property_('PROJECT_ID') + '/messages:send',
    {
      method: 'post',
      contentType: 'application/json',
      headers: { Authorization: 'Bearer ' + accessToken_() },
      payload: JSON.stringify(message),
      muteHttpExceptions: true
    }
  );

  if (response.getResponseCode() >= 300) {
    firebase_('patch', '/sent/' + logId + '.json', { error: response.getContentText() });
    throw new Error('FCM refused the message: ' + response.getContentText());
  }

  firebase_('patch', '/sent/' + logId + '.json', { fcmName: JSON.parse(response.getContentText()).name || '' });
  return { logId: logId, sentAt: sentAt, title: title };
}

/**
 * Sends one parsed feed item as a notice.
 *
 * Category is always NOTICES. The status topic is for the two dispensary messages, driven by a
 * person at a counter; nothing published on the website belongs on it.
 */
function pollerSendAlert_(item) {
  if (!item.title) throw new Error('Feed item has no title: ' + item.guid);

  if (!item.body) {
    // The CMS makes the summary a required field, so this should not happen. If it does, a title
    // with no body is a poor notification but a better one than nothing.
    Logger.log('Alert "%s" has no description; sending the title alone.', item.title);
  }

  var extras = {};
  if (item.pdfUrl) extras.pdfUrl = item.pdfUrl;
  if (item.imageUrl) extras.imageUrl = item.imageUrl;

  if (item.enclosureUrl && !item.pdfUrl && !item.imageUrl) {
    // Neither a PDF nor an image - a video, a zip, a spreadsheet. The app has no way to show it, and
    // sending the URL anyway would produce a notice with an attachment that never appears. Dropped,
    // but logged: this is how a new attachment type on the website becomes visible here rather than
    // silently going missing.
    Logger.log('Alert "%s" has a %s attachment, which the app cannot show. Sending text only.',
               item.title, item.enclosureType);
  }

  // A card only where there is no attachment. An alert with a poster and a link in its text wants
  // the poster shown; two pictures competing in one notification is worse than either alone.
  if (!item.pdfUrl && !item.imageUrl) {
    var found = pollerFirstLink_(item.body);
    if (found) {
      // Set before the fetches below, and deliberately so: if resolving the card fails, the phone
      // still receives linkUrl and shows a plain tappable link. Losing the decoration is a cosmetic
      // failure; losing the link is a notice that no longer says where to go.
      extras.linkUrl = pollerNormaliseUrl_(found);
      try {
        var card = pollerLinkCard_(extras.linkUrl);
        if (card.linkTitle) extras.linkTitle = card.linkTitle;
        if (card.linkImageUrl) extras.linkImageUrl = card.linkImageUrl;
        if (card.linkSite) extras.linkSite = card.linkSite;
      } catch (e) {
        // Never rethrown. pollerSendAlert_ throwing leaves the item unseen, so it is retried on
        // every run from now on - one site that blocks Apps Script would jam the queue for good.
        Logger.log('Alert "%s": could not resolve a card for %s (%s). Sending the link bare.',
                   item.title, extras.linkUrl, e.message);
      }
    }
  }

  return pollerDeliver_(item.title, item.body, extras);
}

// ---------------------------------------------------------------- link previews

/**
 * A notice whose description carries a URL and no attachment gets a preview card - a small logo,
 * the page's title, and the host - in the manner of a chat app.
 *
 * The resolution happens here rather than on the phone, and that is the whole point. Four hundred
 * handsets each fetching and parsing a web page inside onMessageReceived, which already has about
 * ten seconds to do everything, would be four hundred chances to lose a notice over a slow site.
 * Here it happens once, on a machine with no deadline, and the phone receives three plain strings
 * it already knows how to render.
 *
 * WHAT IS DELIBERATELY NOT READ
 *   Only <title> is taken from the page. No og: tags, no images, no link rel=icon - so this reads
 *   one element from a document it does not otherwise interpret, and the logo comes from a favicon
 *   service that needs no parsing at all. A page that changes its markup cannot break the poller.
 *   YouTube is the single exception, and uses a published oEmbed endpoint rather than the HTML.
 */

/** Longest prefix of a page searched for its <title>. Titles live in the head; this is generous. */
var POLLER_TITLE_SCAN_CHARS = 60000;

/** Longest linkTitle sent. Past this a notification truncates it anyway, on a smaller screen. */
var POLLER_LINK_TITLE_MAX = 120;

/**
 * Matches a URL in running text. `www.` is matched as well as a full scheme because editors type it
 * that way; pollerNormaliseUrl_ puts a scheme back before the link is fetched or sent.
 *
 * Kept identical to BodyText.PATTERN in the app. The two must agree on where a URL ends, or the
 * card previews one string while the tappable text in the body is another.
 */
var POLLER_URL_PATTERN = /(?:https?:\/\/|www\.)[^\s<>"']+/i;

/** Trailing punctuation trimmed from a matched URL, as BodyText.TRAILING. */
var POLLER_URL_TRAILING = '.,;:!?' + String.fromCharCode(39) + '"';

/**
 * The first URL in [text], or '' if there is none.
 *
 * Pure, so testPollerLinkExtraction can cover it without network or credentials. A port of
 * BodyText.links in the app, down to the bracket rule: a closing bracket is trimmed only when the
 * URL did not open one, because Wikipedia-style URLs really do contain them and a blanket trim
 * breaks exactly the links most likely to be pasted.
 */
function pollerFirstLink_(text) {
  var match = POLLER_URL_PATTERN.exec(String(text || ''));
  if (!match) return '';

  var candidate = match[0];
  while (candidate.length) {
    var last = candidate.charAt(candidate.length - 1);
    var opens = candidate.split('(').length - 1;
    var closes = candidate.split(')').length - 1;
    var trimmable = POLLER_URL_TRAILING.indexOf(last) >= 0 || (last === ')' && opens < closes);
    if (!trimmable) break;
    candidate = candidate.slice(0, -1);
  }

  // A bare scheme, or a "www." with nothing behind it, is not a link.
  if (candidate.length < 8) return '';
  if (candidate.charAt(candidate.length - 1) === '/' && candidate.indexOf('.') < 0) return '';
  return candidate;
}

/** Adds a scheme to a `www.` link so it can be fetched and handed to a browser. */
function pollerNormaliseUrl_(url) {
  return /^https?:\/\//i.test(url) ? url : 'https://' + url;
}

/** The host of [url], lower-cased and without a leading www. */
function pollerHost_(url) {
  var match = /^https?:\/\/([^\/?#]+)/i.exec(pollerNormaliseUrl_(url));
  if (!match) return '';
  return match[1].toLowerCase().replace(/^www\./, '').replace(/:\d+$/, '');
}

/**
 * The YouTube video id in [url], or ''.
 *
 * Five shapes because editors paste all five: a watch link copied from the address bar, a youtu.be
 * link from the share button, a Short, an embed pasted out of somebody's website, and a livestream.
 * Ids are exactly eleven characters from a fixed alphabet, which is what keeps these patterns from
 * matching arbitrary paths.
 */
function pollerYouTubeId_(url) {
  var patterns = [
    /(?:youtube\.com|youtube-nocookie\.com)\/watch\?(?:[^&\s]*&)*v=([A-Za-z0-9_-]{11})/i,
    /youtu\.be\/([A-Za-z0-9_-]{11})/i,
    /(?:youtube\.com|youtube-nocookie\.com)\/(?:shorts|embed|live|v)\/([A-Za-z0-9_-]{11})/i
  ];
  for (var i = 0; i < patterns.length; i++) {
    var match = patterns[i].exec(url);
    if (match) return match[1];
  }
  return '';
}

/** Collapses whitespace and caps a title at POLLER_LINK_TITLE_MAX. */
function pollerTrimTitle_(title) {
  var cleaned = String(title || '').replace(/\s+/g, ' ').trim();
  if (cleaned.length <= POLLER_LINK_TITLE_MAX) return cleaned;
  return cleaned.slice(0, POLLER_LINK_TITLE_MAX - 1).replace(/\s+\S*$/, '') + '…';
}

/**
 * Decodes the handful of entities that actually appear in a <title>.
 *
 * Not a general HTML decoder, and not trying to be. &amp; is the one that matters - it turns up in
 * roughly every title containing an ampersand - and the rest are cheap to carry alongside it.
 */
function pollerDecodeEntities_(text) {
  return String(text || '')
    .replace(/&#(\d+);/g, function (whole, code) { return String.fromCharCode(parseInt(code, 10)); })
    .replace(/&#x([0-9a-f]+);/gi, function (whole, code) { return String.fromCharCode(parseInt(code, 16)); })
    .replace(/&quot;/gi, '"')
    .replace(/&apos;/gi, String.fromCharCode(39))
    .replace(/&nbsp;/gi, ' ')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    // Last, so that a literal "&amp;lt;" decodes to "&lt;" rather than to "<".
    .replace(/&amp;/gi, '&');
}

/**
 * The site's logo, or '' when there is not one the phone could actually draw.
 *
 * A favicon service rather than the page's own <link rel=icon>, so no markup has to be parsed.
 *
 * The 200 check is not defensive padding. The service answers 404 for a domain it does not know -
 * a new site, an internal host, a preview deployment - and still returns a generic globe in the
 * body. Sending that URL anyway would fail twice over: NoticeImageStore.download rejects any
 * response outside 200..299, so the phone would show a card with a blank space where the logo goes
 * and nothing in any log to say why. Better to send no logo and let the app draw its own
 * placeholder, which it can do offline and instantly.
 *
 * The site's own /favicon.ico is deliberately not used as a fallback: it is usually ICO, and
 * Android's BitmapFactory cannot decode ICO. It would look like a working URL and render nothing.
 */
function pollerFaviconUrl_(url) {
  var candidate = 'https://www.google.com/s2/favicons?sz=128&domain_url=' +
                  encodeURIComponent(pollerNormaliseUrl_(url));
  try {
    if (UrlFetchApp.fetch(candidate, { method: 'head', muteHttpExceptions: true })
        .getResponseCode() === 200) {
      return candidate;
    }
  } catch (e) {
    // Treated as absent. A logo is the most decorative part of the card and the least worth a retry.
  }
  Logger.log('No usable logo for %s; the card will show its title and host alone.', pollerHost_(url));
  return '';
}

/** The <title> of [url], or '' if the page is unreachable, is not HTML, or has none. */
function pollerPageTitle_(url) {
  var response = UrlFetchApp.fetch(url, { muteHttpExceptions: true, followRedirects: true });
  if (response.getResponseCode() !== 200) return '';

  var body;
  try {
    body = response.getContentText();
  } catch (e) {
    // Not decodable as text - a PDF or an image behind a link that looked like a page.
    return '';
  }

  var match = /<title[^>]*>([\s\S]*?)<\/title>/i.exec(body.slice(0, POLLER_TITLE_SCAN_CHARS));
  return match ? pollerTrimTitle_(pollerDecodeEntities_(match[1])) : '';
}

/**
 * The card for a YouTube video.
 *
 * maxresdefault is preferred and mqdefault is the fallback. hqdefault, the one usually reached for,
 * is deliberately not used: it is 4:3, so a 16:9 video arrives with black bars baked into the
 * picture - the exact artefact this work exists to remove. mqdefault is small but honestly 16:9,
 * and maxres is neither guaranteed to exist nor detectable except by asking.
 */
function pollerYouTubePreview_(url, videoId) {
  var maxres = 'https://img.youtube.com/vi/' + videoId + '/maxresdefault.jpg';
  var thumbnail = 'https://img.youtube.com/vi/' + videoId + '/mqdefault.jpg';
  try {
    var head = UrlFetchApp.fetch(maxres, { method: 'head', muteHttpExceptions: true });
    if (head.getResponseCode() === 200) thumbnail = maxres;
  } catch (e) {
    // Keep mqdefault, which always exists.
  }

  var title = '';
  var endpoint = 'https://www.youtube.com/oembed?format=json&url=' +
                 encodeURIComponent('https://www.youtube.com/watch?v=' + videoId);
  var response = UrlFetchApp.fetch(endpoint, { muteHttpExceptions: true });
  if (response.getResponseCode() === 200) {
    title = pollerTrimTitle_(JSON.parse(response.getContentText()).title || '');
  }

  return { linkTitle: title, linkImageUrl: thumbnail, linkSite: 'YouTube' };
}

/**
 * Resolves the card for an already-normalised [url]. Network-bound; callers guard it.
 *
 * Returns the three display fields only. linkUrl is set by the caller before this runs, so that a
 * failure here downgrades a card to a plain tappable link rather than losing the link entirely.
 */
function pollerLinkCard_(url) {
  var videoId = pollerYouTubeId_(url);
  if (videoId) return pollerYouTubePreview_(url, videoId);

  return {
    linkTitle: pollerPageTitle_(url),
    linkImageUrl: pollerFaviconUrl_(url),
    linkSite: pollerHost_(url)
  };
}

// ---------------------------------------------------------------- the trigger entry point

/**
 * Sends every alert in the feed that has not been sent before. This is what the trigger calls.
 *
 * The lock matters more than it looks. Triggers overlap when a run is slow, and two overlapping
 * runs both see an unsent alert, both decide to send it, and four hundred people get the same
 * notice twice. Marking as seen after a successful send rather than before is the other half: an
 * alert that fails to send stays unseen and is retried on the next run.
 */
function pollAlertsFeed() {
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(30000)) {
    Logger.log('Another poll run is still going; skipping this one.');
    return { skipped: true };
  }

  try {
    var items = pollerParseFeed_(pollerFetchFeed_(pollerFeedUrl_()));
    var unsent = items.filter(function (item) { return item.guid && !pollerIsSeen_(item.guid); });

    if (unsent.length === 0) {
      Logger.log('%s: %s items in feed, nothing new.', POLLER_VERSION, items.length);
      return { checked: items.length, sent: 0 };
    }

    var budget = pollerMaxPerRun_();
    if (unsent.length > budget) {
      Logger.log('WARNING: %s unsent alerts but the per-run ceiling is %s. Sending the %s oldest; ' +
                 'the rest wait for the next run. If this is not expected, run pollerStopTrigger() ' +
                 'and look at the feed before it catches up.', unsent.length, budget, budget);
      unsent = unsent.slice(0, budget);
    }

    var sent = 0;
    unsent.forEach(function (item) {
      try {
        pollerSendAlert_(item);
        pollerMarkSeen_(item, 'sent');
        sent++;
      } catch (e) {
        // Left unseen on purpose so the next run retries it, and logged rather than rethrown so one
        // bad alert does not block the ones behind it.
        Logger.log('Alert "%s" failed and will be retried: %s', item.title, e.message);
      }
    });

    Logger.log('%s: %s items in feed, %s sent.', POLLER_VERSION, items.length, sent);
    return { checked: items.length, sent: sent };
  } finally {
    lock.releaseLock();
  }
}

// ---------------------------------------------------------------- setup and testing

/**
 * Marks everything currently in the feed as handled, without sending anything.
 *
 * Run once before the first trigger, and again after any change that alters guids. Without it, the
 * first run treats the whole back catalogue as new.
 */
function pollerSeedFeed() {
  var items = pollerParseFeed_(pollerFetchFeed_(pollerFeedUrl_()));
  items.forEach(function (item) {
    if (item.guid) pollerMarkSeen_(item, 'seeded');
  });
  Logger.log('Seeded %s items from %s. Nothing was sent.', items.length, pollerFeedUrl_());
  return items.length;
}

/**
 * Shows what the next real run would send. Sends nothing.
 *
 * The first thing to run against a feed you have just pointed at, and the thing to run again after
 * any change to the feed template.
 */
function pollerDryRun() {
  var items = pollerParseFeed_(pollerFetchFeed_(pollerFeedUrl_()));
  var unsent = items.filter(function (item) { return item.guid && !pollerIsSeen_(item.guid); });

  Logger.log('Feed: %s\n%s items, %s would be sent (ceiling %s):',
             pollerFeedUrl_(), items.length, unsent.length, pollerMaxPerRun_());
  unsent.slice(0, pollerMaxPerRun_()).forEach(function (item, i) {
    var link = (!item.pdfUrl && !item.imageUrl) ? pollerFirstLink_(item.body) : '';
    Logger.log('  %s. title   : %s\n     body    : %s\n     pdfUrl  : %s\n' +
               '     imageUrl: %s\n     linkUrl : %s\n     guid    : %s',
               i + 1, item.title, item.body, item.pdfUrl || '(none)',
               item.imageUrl || '(none)', link ? pollerNormaliseUrl_(link) : '(none)', item.guid);
  });
  return unsent.length;
}

/**
 * Parses a fixture instead of the network, and checks the fields the send path depends on.
 *
 * This is the test to run while the alerts section is still empty or undeployed: it needs no feed,
 * no credentials and no phones, and it catches the failure that actually happens in practice - a
 * change to the feed template that renames or drops a field the poller reads.
 */
function testPollerParseFixture() {
  var fixture =
    '<?xml version="1.0" encoding="utf-8" standalone="yes"?>' +
    '<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom" ' +
    'xmlns:content="http://purl.org/rss/1.0/modules/content/">' +
    '<channel><title>Alerts</title><link>https://parmanuseniorhealth-github-io.vercel.app/alerts/</link>' +
    // Newest first, as a real feed is: pollerParseFeed_ reverses rather than sorts, so a fixture
    // written oldest-first would pass a reversed test and prove the opposite of what it claims.
    '<item><title>Consultation camp on Friday</title>' +
    '<link>https://parmanuseniorhealth-github-io.vercel.app/alerts/2026-camp/</link>' +
    '<guid isPermaLink="true">https://parmanuseniorhealth-github-io.vercel.app/alerts/2026-camp/</guid>' +
    '<pubDate>Wed, 09 Sep 2026 12:00:00 +0530</pubDate>' +
    '<description>A consultation camp is being held on Friday. Prior appointment is required.</description>' +
    '<enclosure url="https://paramanuseniorshealth.org/files/camp-2026-09-11.jpeg" ' +
    'length="201988" type="image/jpeg"/>' +
    '<content:encoded><![CDATA[<p>Slots are <strong>limited</strong>.</p>]]></content:encoded></item>' +
    '<item><title>Holiday list published</title>' +
    '<link>https://parmanuseniorhealth-github-io.vercel.app/alerts/2026-holidays/</link>' +
    '<guid isPermaLink="true">https://parmanuseniorhealth-github-io.vercel.app/alerts/2026-holidays/</guid>' +
    '<pubDate>Tue, 08 Sep 2026 10:00:00 +0530</pubDate>' +
    '<description>The list of holidays for 2026 is now available.</description>' +
    '<enclosure url="https://paramanuseniorshealth.org/files/list-of-holidays-2026.pdf" ' +
    'length="664013" type="application/pdf"/>' +
    '<content:encoded><![CDATA[<p>The <strong>list</strong> is out.</p>]]></content:encoded></item>' +
    '<item><title>Older alert</title>' +
    '<link>https://parmanuseniorhealth-github-io.vercel.app/alerts/2026-older/</link>' +
    '<guid isPermaLink="true">https://parmanuseniorhealth-github-io.vercel.app/alerts/2026-older/</guid>' +
    '<pubDate>Mon, 07 Sep 2026 09:00:00 +0530</pubDate>' +
    '<description>An older notice with no attachment.</description>' +
    '<content:encoded><![CDATA[<p>Body.</p>]]></content:encoded></item>' +
    '</channel></rss>';

  var items = pollerParseFeed_(fixture);
  var failures = [];

  function check(label, actual, expected) {
    if (String(actual) !== String(expected)) {
      failures.push(label + ': expected "' + expected + '", got "' + actual + '"');
    }
  }

  check('item count', items.length, 3);
  check('oldest first', items[0].title, 'Older alert');
  check('newest last', items[2].title, 'Consultation camp on Friday');

  check('title', items[1].title, 'Holiday list published');
  check('body', items[1].body, 'The list of holidays for 2026 is now available.');
  check('pdfUrl', items[1].pdfUrl, 'https://paramanuseniorshealth.org/files/list-of-holidays-2026.pdf');
  check('guid', items[1].guid, 'https://parmanuseniorhealth-github-io.vercel.app/alerts/2026-holidays/');
  check('html decoded', items[1].html.indexOf('<strong>') >= 0, true);

  // The two attachment fields never both fill from one enclosure. Asserting the empty one on each
  // item is what catches a widened mime test sending a PDF as a picture, which the app would then
  // hand to an image decoder and quietly drop.
  check('imageUrl', items[2].imageUrl, 'https://paramanuseniorshealth.org/files/camp-2026-09-11.jpeg');
  check('image item has no pdfUrl', items[2].pdfUrl, '');
  check('pdf item has no imageUrl', items[1].imageUrl, '');
  check('no pdf on plain item', items[0].pdfUrl, '');
  check('no image on plain item', items[0].imageUrl, '');

  if (failures.length) {
    throw new Error('testPollerParseFixture failed:\n  ' + failures.join('\n  '));
  }
  Logger.log('testPollerParseFixture: %s items parsed, all fields correct.', items.length);
  return true;
}

/**
 * Checks the pure half of the link-preview path: what counts as a URL, and which ones are YouTube.
 *
 * No network, no credentials, sends nothing - so this runs anywhere, and it covers the failures
 * that actually happen. Every case here is a real shape an editor produces: a link at the end of a
 * sentence, a link in brackets, a share link, a Short.
 */
function testPollerLinkExtraction() {
  var failures = [];

  function check(label, actual, expected) {
    if (String(actual) !== String(expected)) {
      failures.push(label + ': expected "' + expected + '", got "' + actual + '"');
    }
  }

  // Where a URL ends. Getting this wrong is how a link arrives with the sentence's full stop
  // welded on, which then 404s on the phone.
  check('plain', pollerFirstLink_('See https://example.org/notice for details.'),
        'https://example.org/notice');
  check('sentence end', pollerFirstLink_('Read it at https://example.org/notice.'),
        'https://example.org/notice');
  check('bracketed', pollerFirstLink_('Details (https://example.org/a) follow.'),
        'https://example.org/a');
  check('balanced brackets kept', pollerFirstLink_('See https://en.wikipedia.org/wiki/A_(b) now'),
        'https://en.wikipedia.org/wiki/A_(b)');
  check('www gets a scheme', pollerNormaliseUrl_(pollerFirstLink_('Visit www.example.org today')),
        'https://www.example.org');
  check('no link', pollerFirstLink_('There is no link in this sentence.'), '');
  check('first of several', pollerFirstLink_('https://a.example.org and https://b.example.org'),
        'https://a.example.org');

  // Host, as it appears on the card.
  check('host', pollerHost_('https://www.Example.org/a/b?c=d'), 'example.org');
  check('host with port', pollerHost_('https://example.org:8443/a'), 'example.org');

  // The five shapes editors paste.
  check('watch', pollerYouTubeId_('https://www.youtube.com/watch?v=dQw4w9WgXcQ'), 'dQw4w9WgXcQ');
  check('watch with params',
        pollerYouTubeId_('https://www.youtube.com/watch?feature=share&v=dQw4w9WgXcQ'), 'dQw4w9WgXcQ');
  check('youtu.be', pollerYouTubeId_('https://youtu.be/dQw4w9WgXcQ?t=30'), 'dQw4w9WgXcQ');
  check('short', pollerYouTubeId_('https://www.youtube.com/shorts/dQw4w9WgXcQ'), 'dQw4w9WgXcQ');
  check('embed', pollerYouTubeId_('https://www.youtube.com/embed/dQw4w9WgXcQ'), 'dQw4w9WgXcQ');
  check('not youtube', pollerYouTubeId_('https://example.org/watch?v=dQw4w9WgXcQ'), '');

  // Titles, as they arrive out of real pages.
  check('entities', pollerDecodeEntities_('Rates &amp; charges &#8212; 2026'),
        'Rates & charges — 2026');
  check('whitespace collapsed', pollerTrimTitle_('  A   title\non two lines '), 'A title on two lines');
  check('long title capped', pollerTrimTitle_(new Array(40).join('word ')).length <= POLLER_LINK_TITLE_MAX,
        true);

  if (failures.length) {
    throw new Error('testPollerLinkExtraction failed:\n  ' + failures.join('\n  '));
  }
  Logger.log('testPollerLinkExtraction: all cases correct.');
  return true;
}

/**
 * Resolves a card for real, against the network. Sends nothing.
 *
 * Run this after changing anything in the link section, and whenever a card comes through blank on
 * a handset: it separates "the poller could not resolve it" from "the app did not render it".
 */
function testPollerLinkCard(url) {
  url = pollerNormaliseUrl_(url || 'https://www.youtube.com/watch?v=dQw4w9WgXcQ');
  var card = pollerLinkCard_(url);
  Logger.log('linkUrl  : %s\nlinkTitle: %s\nlinkImage: %s\nlinkSite : %s',
             url, card.linkTitle || '(none)', card.linkImageUrl || '(none)', card.linkSite || '(none)');
  return card;
}

/**
 * Sends the newest alert in the feed, for real, to the LIVE notices topic.
 *
 * This exercises the whole path - real credentials, real payload, real FCM response - and it is a
 * real broadcast: every subscriber gets it. That is deliberate while the app is in closed testing,
 * where the subscriber set is the test cohort and there is no audience to protect.
 *
 * BEFORE THE APP LEAVES CLOSED TESTING, point TOPIC_OVERRIDE below at 'poller-test' and subscribe a
 * test handset to it. Left as it is, a routine test run notifies every real user.
 *
 * It does not mark anything as seen, so it can be run repeatedly and does not consume an alert. It
 * does write to /sent, tagged source: 'poller' - that is the point, since the log write is part of
 * what is being tested. TOPIC_OVERRIDE is restored in a finally block: leaving it set would divert
 * the console's next real send to the scratch topic, and nobody would be told.
 */
function testPollerEndToEnd() {
  var items = pollerParseFeed_(pollerFetchFeed_(pollerFeedUrl_()));
  if (items.length === 0) throw new Error('Feed has no items to test with.');

  var newest = items[items.length - 1];
  TOPIC_OVERRIDE = 'notices-v1';
  try {
    var result = pollerSendAlert_(newest);
    Logger.log('Sent "%s" to %s. logId=%s pdfUrl=%s imageUrl=%s',
               newest.title, TOPIC_OVERRIDE, result.logId,
               newest.pdfUrl || '(none)', newest.imageUrl || '(none)');
    return result;
  } finally {
    TOPIC_OVERRIDE = null;
  }
}

/** Proves the feed and the shared credentials both work, without sending anything. */
function testPollerConnection() {
  var items = pollerParseFeed_(pollerFetchFeed_(pollerFeedUrl_()));
  var seen = firebase_('get', POLLER_SEEN_PATH + '.json?shallow=true') || {};
  Logger.log('version=%s feed=%s items=%s alreadyHandled=%s',
             POLLER_VERSION, pollerFeedUrl_(), items.length, Object.keys(seen).length);
}

/** Creates the 15-minute trigger. Safe to run twice; it removes any existing one first. */
function pollerInstallTrigger() {
  ScriptApp.getProjectTriggers().forEach(function (trigger) {
    if (trigger.getHandlerFunction() === 'pollAlertsFeed') ScriptApp.deleteTrigger(trigger);
  });
  ScriptApp.newTrigger('pollAlertsFeed').timeBased().everyMinutes(15).create();
  Logger.log('Trigger installed: pollAlertsFeed every 15 minutes.');
}

/** Removes the trigger. The first thing to reach for if something is going wrong. */
function pollerStopTrigger() {
  var removed = 0;
  ScriptApp.getProjectTriggers().forEach(function (trigger) {
    if (trigger.getHandlerFunction() === 'pollAlertsFeed') {
      ScriptApp.deleteTrigger(trigger);
      removed++;
    }
  });
  Logger.log('Removed %s trigger(s).', removed);
}
