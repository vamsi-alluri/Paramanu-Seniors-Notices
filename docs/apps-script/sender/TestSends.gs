/**
 * Paramanu Seniors Notices - device test sends
 *
 * Sends a fixed set of notices to the TEST dispensary's topics, so a handset holding a test-disp code
 * can be checked by eye: the tray, the list, attachments, broken URLs, long and non-Latin text.
 *
 *   anything carrying a PDF or an image  ->  test-notices-v1
 *   everything else                      ->  test-notifications-v1
 *
 * Nothing here can reach a real user. The two topics are constants in this file, not read from
 * DISPENSARY_ID, and tsSend_ refuses any topic that does not start with `test-`. It also never
 * touches TOPIC_OVERRIDE, which belongs to the real send path.
 *
 * Nothing is written to /sent either. That log is what staff read in the sender page, and a screen of
 * test notices there is how a real one gets missed. logIds are still epoch millis, because the app
 * parses them into the notice's time, and are handed out strictly increasing within a run.
 *
 * NAMING
 *   Apps Script shares one global scope across every .gs file, and a duplicate name silently
 *   replaces the other. Everything this file owns starts with `testSend` (entry points) or `ts`
 *   (helpers). It borrows from the other files, and has to follow them if they are renamed:
 *
 *     Code.gs     property_()   accessToken_()
 *     Poller.gs   pollerFeedUrl_()   pollerFetchFeed_()   pollerParseFeed_()   pollerLinkCard_()
 *
 * WHERE THE ATTACHMENTS COME FROM
 *   The newest PDF and the newest image in the website's alerts feed, so the test uses the same
 *   kind of file a real notice carries. To pin specific files instead, set the Script Properties
 *   TEST_PDF_URL and/or TEST_IMAGE_URL; they win over the feed.
 *
 * THE PHONE
 *   It has to hold a code whose `dispensary` is test-disp, and /dispensaries/test-disp/topics must
 *   offer both topics, each with a `label` -- an entry without one is dropped by the app, and every
 *   notice on its topic is then dropped with it ("not offered, or switched off" in logcat).
 *
 * USE
 *   testSendDryRun()   logs every payload, sends nothing. Run this first.
 *   testSendAll()      sends every case, a couple of seconds apart, in the order listed below.
 *   testSendOne('pdf') sends one case by its id.
 */

var TEST_SEND_VERSION = '2026-09-21-test-sends';

/** Attachments go here. Must match a topic in /dispensaries/test-disp/topics. */
var TEST_SEND_ATTACHMENT_TOPIC = 'test-notices-v1';

/** Everything without an attachment goes here. Must match a topic in /dispensaries/test-disp/topics. */
var TEST_SEND_PLAIN_TOPIC = 'test-notifications-v1';

/** Gap between sends, so they arrive in order and each tray notification can be seen. */
var TEST_SEND_GAP_MS = 2500;

var TS_LAST_LOG_ID = 0;

// ---------------------------------------------------------------- the cases

/**
 * Every case, in the order testSendAll sends them. Built on each run, because the attachment URLs
 * come from the feed.
 *
 * `extras` are the same data fields the real senders use (see NoticeMessagingService): pdfUrl,
 * imageUrl, linkUrl, linkTitle, linkImageUrl, linkSite. The topic is decided from them, not listed
 * here, so a case cannot be put on the wrong topic by hand.
 */
function tsCases_() {
  var files = tsAttachments_();
  var site = tsSiteRoot_();
  var stamp = new Date().toISOString().slice(11, 19);

  var cases = [
    { id: 'text', title: 'Plain text notice', body: 'No attachment, no link. Should arrive on the notifications topic.' },
    { id: 'multiline', title: 'Several lines of body', body: 'First line.\nSecond line.\n\nA line after a blank one.' },
    { id: 'unicode', title: 'सूचना - मराठी / हिन्दी चाचणी', body: 'दवाखाना सोमवारी बंद राहील. डिस्पेंसरी सोमवार को बंद रहेगी।' },
    { id: 'long', title: tsRepeat_('Long title to check wrapping ', 120), body: tsRepeat_('Long body to check the expanded notification and the list row. ', 900) },
    { id: 'link-bare', title: 'Link with no card', body: 'The phone should show a plain tappable link: ' + site,
      extras: { linkUrl: site } },
    { id: 'link-card', title: 'Link with a preview card', body: 'Resolved by the sender: ' + site,
      extras: tsLinkCardExtras_(site) }
  ];

  if (files.pdfUrl) {
    cases.push({ id: 'pdf', title: 'PDF circular', body: 'Page one should render into the tray. From: ' + files.pdfSource,
      extras: { pdfUrl: files.pdfUrl } });
  }
  if (files.imageUrl) {
    cases.push({ id: 'image', title: 'Image poster', body: 'The picture should fill the tray notification. From: ' + files.imageSource,
      extras: { imageUrl: files.imageUrl } });
  }
  if (files.pdfUrl && files.imageUrl) {
    // The feed cannot carry both, but the app is meant to tolerate it: the image wins the tray.
    cases.push({ id: 'pdf-and-image', title: 'PDF and image together', body: 'The image should win the tray; both should show in the row.',
      extras: { pdfUrl: files.pdfUrl, imageUrl: files.imageUrl } });

    // The image as the circular's own thumbnail, not as a second attachment. It travels as
    // pdfThumbUrl, the key the deferred-fetching design gives it, so the phone can draw the card
    // without downloading the PDF. Until the app reads that key it shows the rendered page instead.
    var thumbExtras = { pdfUrl: files.pdfUrl, pdfThumbUrl: files.imageUrl };
    var props = PropertiesService.getScriptProperties();
    if (props.getProperty('TEST_PDF_BYTES')) thumbExtras.pdfBytes = props.getProperty('TEST_PDF_BYTES');
    if (props.getProperty('TEST_PDF_PAGES')) thumbExtras.pdfPages = props.getProperty('TEST_PDF_PAGES');
    cases.push({ id: 'pdf-with-thumb', title: 'PDF with the image as its thumbnail',
      body: 'The image should be the PDF\'s thumbnail, and only one picture should show. Tap to open the circular.',
      extras: thumbExtras });
  }

  // Missing files. The phone should keep the notice and show a failed-attachment state, not drop it.
  cases.push({ id: 'pdf-404', title: 'PDF that does not exist', body: 'The attachment should fail; the notice should not.',
    extras: { pdfUrl: site + 'files/does-not-exist-' + Date.now() + '.pdf' } });
  cases.push({ id: 'image-404', title: 'Image that does not exist', body: 'The attachment should fail; the notice should not.',
    extras: { imageUrl: site + 'images/does-not-exist-' + Date.now() + '.jpg' } });
  cases.push({ id: 'pdf-not-a-pdf', title: 'PDF URL that serves HTML', body: 'The renderer should refuse it without crashing.',
    extras: { pdfUrl: site } });

  // The time in front tells one run from the last on the phone. Cut back to the sender's 120 so the
  // long case still sits exactly on the limit rather than past it.
  cases.forEach(function (c) { c.title = ('[' + stamp + '] ' + c.title).slice(0, 120); c.extras = c.extras || {}; });
  return cases;
}

// ---------------------------------------------------------------- entry points

/** Logs every payload that testSendAll would send, and sends nothing. */
function testSendDryRun() {
  tsCases_().forEach(function (c) {
    Logger.log('%s -> %s\n%s', c.id, tsTopicFor_(c.extras), JSON.stringify(tsRequest_(c, 'DRY-RUN'), null, 2));
  });
}

/** Sends every case. Stops at the first refusal from FCM, so a bad credential is not repeated ten times. */
function testSendAll() {
  var cases = tsCases_();
  cases.forEach(function (c, i) {
    if (i > 0) Utilities.sleep(TEST_SEND_GAP_MS);
    tsSend_(c);
  });
  Logger.log('Sent %s test notices.', cases.length);
}

/** Sends the one case with this id, e.g. testSendOne('pdf'). */
function testSendOne(id) {
  var cases = tsCases_();
  for (var i = 0; i < cases.length; i++) {
    if (cases[i].id === id) return tsSend_(cases[i]);
  }
  throw new Error('No test case "' + id + '". Cases: ' + cases.map(function (c) { return c.id; }).join(', '));
}

// Zero-argument wrappers, because the editor's Run button cannot pass an argument.
function testSendText() { return testSendOne('text'); }
function testSendPdf() { return testSendOne('pdf'); }
function testSendImage() { return testSendOne('image'); }
function testSendLinkCard() { return testSendOne('link-card'); }
function testSendPdfWithThumb() { return testSendOne('pdf-with-thumb'); }

// ---------------------------------------------------------------- sending

/** An attachment goes to the notices topic, anything else to the notifications topic. Pure. */
function tsTopicFor_(extras) {
  return (extras && (extras.pdfUrl || extras.imageUrl)) ? TEST_SEND_ATTACHMENT_TOPIC : TEST_SEND_PLAIN_TOPIC;
}

/**
 * The FCM request for one case. Pure.
 *
 * Data-only, exactly as the real senders build it: a notification block would be drawn by the SDK
 * while the app is backgrounded, and onMessageReceived -- the thing under test -- would never run.
 * `topic` travels in the data too, because the phone checks it against what test-disp offers.
 */
function tsRequest_(c, logId) {
  var topic = tsTopicFor_(c.extras);
  var data = { logId: String(logId), title: c.title, body: c.body || '', topic: topic };
  for (var key in c.extras) {
    if (c.extras.hasOwnProperty(key) && c.extras[key]) data[key] = String(c.extras[key]);
  }
  return { message: { topic: topic, android: { priority: 'high' }, data: data } };
}

function tsSend_(c) {
  var request = tsRequest_(c, tsNextLogId_());
  var topic = request.message.topic;
  if (topic.indexOf('test-') !== 0) {
    throw new Error('Refusing to send a test notice to "' + topic + '": only test- topics are allowed from this file.');
  }

  var response = UrlFetchApp.fetch(
    'https://fcm.googleapis.com/v1/projects/' + property_('PROJECT_ID') + '/messages:send',
    { method: 'post', contentType: 'application/json',
      headers: { Authorization: 'Bearer ' + accessToken_() },
      payload: JSON.stringify(request), muteHttpExceptions: true });

  if (response.getResponseCode() >= 300) {
    throw new Error('FCM refused test case "' + c.id + '": ' + response.getContentText());
  }
  Logger.log('%s -> %s  logId=%s  %s', c.id, topic, request.message.data.logId,
             JSON.parse(response.getContentText()).name || '');
  return request.message.data.logId;
}

/** Epoch millis, strictly increasing within a run, so two cases never share an id on the phone. */
function tsNextLogId_() {
  TS_LAST_LOG_ID = Math.max(Date.now(), TS_LAST_LOG_ID + 1);
  return TS_LAST_LOG_ID;
}

// ---------------------------------------------------------------- inputs

/** The PDF and image to attach: Script Properties first, then the newest of each in the feed. */
function tsAttachments_() {
  var props = PropertiesService.getScriptProperties();
  var result = {
    pdfUrl: props.getProperty('TEST_PDF_URL') || '', pdfSource: 'TEST_PDF_URL',
    imageUrl: props.getProperty('TEST_IMAGE_URL') || '', imageSource: 'TEST_IMAGE_URL'
  };
  if (result.pdfUrl && result.imageUrl) return result;

  var items = [];
  try {
    items = pollerParseFeed_(pollerFetchFeed_(pollerFeedUrl_()));
  } catch (e) {
    Logger.log('Could not read the alerts feed (%s); only Script Property attachments will be used.', e.message);
  }
  // Oldest first from the parser, so walk backwards for the newest.
  for (var i = items.length - 1; i >= 0; i--) {
    if (!result.pdfUrl && items[i].pdfUrl) { result.pdfUrl = items[i].pdfUrl; result.pdfSource = items[i].title; }
    if (!result.imageUrl && items[i].imageUrl) { result.imageUrl = items[i].imageUrl; result.imageSource = items[i].title; }
  }
  if (!result.pdfUrl) Logger.log('No PDF found in the feed and TEST_PDF_URL is not set: the pdf cases are skipped.');
  if (!result.imageUrl) Logger.log('No image found in the feed and TEST_IMAGE_URL is not set: the image cases are skipped.');
  return result;
}

/** The website's root, taken from the feed URL, for the link and broken-file cases. */
function tsSiteRoot_() {
  var match = /^(https?:\/\/[^\/]+)/.exec(pollerFeedUrl_());
  return (match ? match[1] : 'https://paramanuseniorshealth.org') + '/';
}

/** A resolved card, as the poller would send it. Falls back to a bare link if resolving fails. */
function tsLinkCardExtras_(url) {
  var extras = { linkUrl: url };
  try {
    var card = pollerLinkCard_(url);
    if (card.linkTitle) extras.linkTitle = card.linkTitle;
    if (card.linkImageUrl) extras.linkImageUrl = card.linkImageUrl;
    if (card.linkSite) extras.linkSite = card.linkSite;
  } catch (e) {
    Logger.log('Could not resolve a card for %s (%s); the link-card case goes out bare.', url, e.message);
  }
  return extras;
}

/** [text] repeated and cut to exactly [length] characters: the sender's own limits. */
function tsRepeat_(text, length) {
  var out = '';
  while (out.length < length) out += text;
  return out.slice(0, length);
}
