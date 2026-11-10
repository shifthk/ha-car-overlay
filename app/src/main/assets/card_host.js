/*
 * Injected into the Home Assistant web page that runs inside the overlay's WebView.
 * It hides the normal interface and uses Home Assistant's own card code to draw one card,
 * so the popup looks exactly like the dashboard (themes and custom cards included).
 * Talks to the app through window.haOverlayHost: ready(), size(cssPx), expanded(bool), failed(msg).
 */
(function () {
  if (window.__haOverlay) return;
  var O = (window.__haOverlay = { ha: null, helpers: null, box: null, card: null, cfg: null, ready: false, tries: 0 });
  var host = window.haOverlayHost || { ready: function () {}, size: function () {}, expanded: function () {}, failed: function () {} };

  function root() {
    return document.querySelector('home-assistant, ha-demo');
  }

  function wait() {
    var ha = root();
    if (ha && ha.hass && ha.shadowRoot && ha.shadowRoot.querySelector('home-assistant-main') &&
        typeof window.loadCardHelpers === 'function') {
      setup(ha).catch(function (e) { host.failed(String(e)); });
      return;
    }
    if (++O.tries > 360) {   // 90 seconds
      host.failed('Home Assistant did not finish loading');
      return;
    }
    setTimeout(wait, 250);
  }

  async function setup(ha) {
    O.ha = ha;
    O.helpers = await window.loadCardHelpers();

    var page = document.createElement('style');
    page.textContent = 'html,body{background:transparent!important}';
    document.head.appendChild(page);

    var inner = document.createElement('style');
    inner.textContent =
      'home-assistant-main{display:none!important}' +
      '#ha-overlay-box{position:fixed;left:0;top:0;right:0;max-height:100vh;overflow:auto;box-sizing:border-box}' +
      '#ha-overlay-box>*{display:block}';
    ha.shadowRoot.appendChild(inner);

    var box = document.createElement('div');
    box.id = 'ha-overlay-box';
    ha.shadowRoot.appendChild(box);
    O.box = box;

    // Dialogs (more-info and others) need the whole window, not just the card's height.
    ha.addEventListener('hass-more-info', function (ev) { host.expanded(!!(ev.detail && ev.detail.entityId)); });
    ha.addEventListener('show-dialog', function () { host.expanded(true); });
    ha.addEventListener('dialog-closed', function () { host.expanded(false); });

    O.ro = new ResizeObserver(report);
    O.ro.observe(box);

    // Keep the card's data live: hand it Home Assistant's state object whenever that changes.
    setInterval(function () {
      if (O.card && O.card.hass !== ha.hass) O.card.hass = ha.hass;
    }, 250);

    O.ready = true;
    host.ready();
    build();
  }

  function report() {
    if (!O.box || !O.card) return;
    var h = Math.ceil(O.box.scrollHeight);
    if (h > 0) host.size(h);
  }

  function build() {
    if (!O.ready || !O.cfg) return;
    var el;
    try {
      el = O.helpers.createCardElement(O.cfg);
    } catch (e) {
      el = O.helpers.createCardElement({ type: 'error', error: String(e), origConfig: O.cfg });
    }
    el.hass = O.ha.hass;
    // Custom cards that weren't loaded yet ask to be rebuilt once their code arrives.
    el.addEventListener('ll-rebuild', function (ev) {
      ev.stopPropagation();
      build();
    }, { once: true });
    if (O.card) O.ro.unobserve(O.card);
    O.box.replaceChildren(el);
    O.card = el;
    O.ro.observe(el);
    setTimeout(report, 50);
    setTimeout(report, 500);
  }

  /** Called by the app with the card's dashboard config. */
  O.show = function (cfg) {
    O.cfg = cfg;
    build();
  };

  /** Called when the popup closes: shut any detail dialog that was left open. */
  O.reset = function () {
    try {
      if (O.ha) O.ha.dispatchEvent(new CustomEvent('hass-more-info', { detail: { entityId: null } }));
    } catch (e) { /* ignore */ }
  };

  wait();
})();
