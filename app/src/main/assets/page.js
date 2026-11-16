(() => {
  'use strict';
  if (window.top !== window) return;

  const BRIDGE_NAME = '__BRIDGE_NAME__';
  const bridge = window[BRIDGE_NAME];
  if (!bridge) return;
  try { delete window[BRIDGE_NAME]; } catch (_) {}

  const send = (msg) => {
    try { bridge.postMessage(JSON.stringify(msg)); } catch (_) {}
  };
  const report = (what, err) => send({ t: 'error', what, msg: String(err && err.message || err).slice(0, 200) });

  const nativeToString = Function.prototype.toString;
  const disguised = new WeakMap();
  const disguise = (fn, name) => {
    disguised.set(fn, `function ${name}() { [native code] }`);
    return fn;
  };
  try {
    const toString = disguise(function toString() {
      return disguised.has(this) ? disguised.get(this) : nativeToString.call(this);
    }, 'toString');
    Object.defineProperty(Function.prototype, 'toString', {
      value: toString, writable: true, configurable: true, enumerable: false,
    });
  } catch (e) { report('toString', e); }

  const replaceGetter = (proto, prop, get) => {
    const desc = Object.getOwnPropertyDescriptor(proto, prop);
    if (!desc || !desc.configurable) return false;
    Object.defineProperty(proto, prop, { ...desc, get: disguise(get, `get ${prop}`) });
    return true;
  };
  const replaceMethod = (proto, prop, fn) => {
    const desc = Object.getOwnPropertyDescriptor(proto, prop);
    if (!desc || !desc.configurable) return false;
    Object.defineProperty(proto, prop, { ...desc, value: disguise(fn, prop) });
    return true;
  };

  const CATCH_UP = __CATCH_UP__;
  let catchUp = () => 0;
  if (CATCH_UP) {
    try {
      const nSetTimeout = window.setTimeout;
      const nClearTimeout = window.clearTimeout;
      const nSetInterval = window.setInterval;
      const nClearInterval = window.clearInterval;
      const timers = new Map();
      const fail = (e) => { try { (window.reportError || console.error)(e); } catch (_) {} };

      const nPerfNow = Performance.prototype.now;
      const perfNow = () => nPerfNow.call(performance);
      const wall0 = Date.now();
      const perf0 = perfNow();
      let sleptMs = 0;
      const syncClock = () => {
        const behind = (Date.now() - wall0) - (perfNow() - perf0);
        if (behind > sleptMs + 100) sleptMs = behind;
      };
      replaceMethod(Performance.prototype, 'now', function now() {
        return nPerfNow.call(this) + sleptMs;
      });

      const setTimeout = disguise(function setTimeout(handler, delay, ...args) {
        if (typeof handler !== 'function') return nSetTimeout.apply(this, arguments);
        const ms = Math.max(0, Number(delay) || 0);
        let id = 0;
        const run = () => { timers.delete(id); handler.apply(window, args); };
        id = nSetTimeout.call(window, run, ms);
        if (ms >= 1000) timers.set(id, { due: Date.now() + ms, run, every: 0 });
        return id;
      }, 'setTimeout');
      const clearTimeout = disguise(function clearTimeout(id) {
        timers.delete(id);
        return nClearTimeout.call(window, id);
      }, 'clearTimeout');
      const setInterval = disguise(function setInterval(handler, delay, ...args) {
        if (typeof handler !== 'function') return nSetInterval.apply(this, arguments);
        const ms = Math.max(0, Number(delay) || 0);
        let id = 0;
        const run = () => {
          const t = timers.get(id);
          if (t) t.due = Date.now() + ms;
          handler.apply(window, args);
        };
        id = nSetInterval.call(window, run, ms);
        if (ms >= 1000) timers.set(id, { due: Date.now() + ms, run, every: ms });
        return id;
      }, 'setInterval');
      const clearInterval = disguise(function clearInterval(id) {
        timers.delete(id);
        return nClearInterval.call(window, id);
      }, 'clearInterval');

      window.setTimeout = setTimeout;
      window.clearTimeout = clearTimeout;
      window.setInterval = setInterval;
      window.clearInterval = clearInterval;

      catchUp = () => {
        syncClock();
        const now = Date.now();
        let fired = 0;
        for (const [id, t] of timers) {
          if (t.due > now - 250) continue;
          if (t.every) {
            t.due = now + t.every;
          } else {
            timers.delete(id);
            nClearTimeout.call(window, id);
          }
          fired++;
          try { t.run(); } catch (e) { fail(e); }
        }
        return fired;
      };
    } catch (e) { report('catchUp', e); }
  }

  let visible = false;
  try {
    replaceGetter(Document.prototype, 'visibilityState', function () { return visible ? 'visible' : 'hidden'; });
    replaceGetter(Document.prototype, 'hidden', function () { return !visible; });
    const nativeHasFocus = Document.prototype.hasFocus;
    replaceMethod(Document.prototype, 'hasFocus', function () { return visible && nativeHasFocus.call(this); });
  } catch (e) { report('visibility', e); }

  const nRaf = window.requestAnimationFrame;
  const nCaf = window.cancelAnimationFrame;
  const waitingFrames = new Map();
  let nextFrameId = -1;
  try {
    window.requestAnimationFrame = disguise(function requestAnimationFrame(cb) {
      if (visible) return nRaf.call(window, cb);
      const id = nextFrameId--;
      waitingFrames.set(id, cb);
      return id;
    }, 'requestAnimationFrame');
    window.cancelAnimationFrame = disguise(function cancelAnimationFrame(id) {
      if (waitingFrames.delete(id)) return;
      return nCaf.call(window, id);
    }, 'cancelAnimationFrame');
  } catch (e) { report('animationFrame', e); }

  const PAUSE_CSS = '*,*::before,*::after{animation-play-state:paused!important;' +
    'transition:none!important}';
  let pauseStyle = null;
  const applyRendering = () => {
    try {
      if (visible) {
        if (pauseStyle) { pauseStyle.remove(); pauseStyle = null; }
        const due = [...waitingFrames.values()];
        waitingFrames.clear();
        for (const cb of due) nRaf.call(window, cb);
      } else if (!pauseStyle) {
        const root = document.head || document.documentElement;
        if (!root) return;
        pauseStyle = document.createElement('style');
        pauseStyle.textContent = PAUSE_CSS;
        root.appendChild(pauseStyle);
      } else if (!pauseStyle.isConnected) {
        (document.head || document.documentElement).appendChild(pauseStyle);
      }
    } catch (e) { report('pauseStyle', e); }
  };

  const setVisible = (v) => {
    if (v === visible) return;
    visible = v;
    applyRendering();
    try {
      document.dispatchEvent(new Event('visibilitychange'));
      window.dispatchEvent(new Event(v ? 'focus' : 'blur'));
    } catch (_) {}
  };

  let nextId = 0;
  const live = new Map();

  const CALL_TEXT = /incoming (voice |video )?call|check your phone to answer|eingehender? .*anruf|anruf/i;
  const isCall = (o) => {
    try {
      if (o.data && o.data.callId != null) return true;
      if (Array.isArray(o.actions) && o.actions.some((a) => /call/i.test(String(a && a.action)))) return true;
      return CALL_TEXT.test(String(o.body == null ? '' : o.body));
    } catch (_) { return false; }
  };
  const forward = (title, opts) => {
    const id = ++nextId;
    const o = opts || {};
    send({
      t: 'notify', id,
      title: String(title == null ? '' : title).slice(0, 300),
      body: String(o.body == null ? '' : o.body).slice(0, 2000),
      tag: String(o.tag == null ? '' : o.tag).slice(0, 200),
      call: isCall(o),
    });
    return id;
  };

  try {
    class Notification extends EventTarget {
      constructor(title, options) {
        super();
        if (!new.target) throw new TypeError("Failed to construct 'Notification'");
        const o = options || {};
        this.title = String(title);
        this.body = o.body == null ? '' : String(o.body);
        this.tag = o.tag == null ? '' : String(o.tag);
        this.icon = o.icon || '';
        this.data = o.data == null ? null : o.data;
        this.silent = !!o.silent;
        this.requireInteraction = !!o.requireInteraction;
        this.onclick = null; this.onshow = null; this.onclose = null; this.onerror = null;
        const id = forward(title, o);
        Object.defineProperty(this, '__id', { value: id, enumerable: false });
        live.set(id, this);
        setTimeout(() => {
          const ev = new Event('show');
          this.dispatchEvent(ev);
          if (typeof this.onshow === 'function') { try { this.onshow(ev); } catch (_) {} }
        }, 0);
      }
      close() {
        if (!live.delete(this.__id)) return;
        send({ t: 'unnotify', id: this.__id, tag: this.tag });
        const ev = new Event('close');
        this.dispatchEvent(ev);
        if (typeof this.onclose === 'function') { try { this.onclose(ev); } catch (_) {} }
      }
      static get permission() { return 'granted'; }
      static get maxActions() { return 2; }
      static requestPermission(cb) {
        if (typeof cb === 'function') { try { cb('granted'); } catch (_) {} }
        return Promise.resolve('granted');
      }
    }
    disguise(Notification, 'Notification');
    disguise(Notification.prototype.close, 'close');
    disguise(Notification.requestPermission, 'requestPermission');
    Object.defineProperty(window, 'Notification', {
      value: Notification, writable: true, configurable: true, enumerable: false,
    });
  } catch (e) { report('Notification', e); }

  try {
    if (window.ServiceWorkerRegistration) {
      replaceMethod(ServiceWorkerRegistration.prototype, 'showNotification', function (title, options) {
        forward(title, options);
        return Promise.resolve();
      });
      replaceMethod(ServiceWorkerRegistration.prototype, 'getNotifications', function () {
        return Promise.resolve([]);
      });
    }
  } catch (e) { report('swNotification', e); }

  try {
    if (navigator.permissions && navigator.permissions.query) {
      const nativeQuery = navigator.permissions.query;
      const query = disguise(function query(desc) {
        if (desc && (desc.name === 'notifications' || desc.name === 'push')) {
          const status = new EventTarget();
          Object.defineProperties(status, {
            state: { value: 'granted', enumerable: true },
            name: { value: desc.name, enumerable: true },
            onchange: { value: null, writable: true, enumerable: true },
          });
          return Promise.resolve(status);
        }
        return nativeQuery.call(this, desc);
      }, 'query');
      Object.defineProperty(Object.getPrototypeOf(navigator.permissions), 'query', {
        value: query, writable: true, configurable: true, enumerable: true,
      });
    }
  } catch (e) { report('permissions', e); }

  let socketsSeen = 0;
  const openSockets = new Set();
  try {
    const NativeWS = window.WebSocket;
    const watch = (ws, url) => {
      if (!/whatsapp\.(com|net)/i.test(url)) return;
      const id = ++socketsSeen;
      send({ t: 'ws', ev: 'new', id });
      let lastFrame = 0;
      ws.addEventListener('open', () => { openSockets.add(id); send({ t: 'ws', ev: 'open', id }); });
      ws.addEventListener('close', (e) => {
        openSockets.delete(id);
        send({ t: 'ws', ev: 'close', id, code: e.code, clean: !!e.wasClean });
      });
      ws.addEventListener('error', () => send({ t: 'ws', ev: 'error', id }));
      ws.addEventListener('message', () => {
        const now = Date.now();
        if (now - lastFrame < 2000) return;
        lastFrame = now;
        send({ t: 'ws', ev: 'frame', id });

        queueMicrotask(() => {
          const fired = catchUp();
          if (fired) send({ t: 'caughtUp', n: fired });
        });
      });
    };
    const WatchedWS = new Proxy(NativeWS, {
      construct(target, args, newTarget) {
        const ws = Reflect.construct(target, args, newTarget === WatchedWS ? target : newTarget);
        try { watch(ws, String(args[0])); } catch (_) {}
        return ws;
      },
    });
    disguise(WatchedWS, 'WebSocket');

    Object.defineProperty(NativeWS.prototype, 'constructor', {
      ...Object.getOwnPropertyDescriptor(NativeWS.prototype, 'constructor'), value: WatchedWS,
    });
    Object.defineProperty(window, 'WebSocket', {
      ...Object.getOwnPropertyDescriptor(window, 'WebSocket'), value: WatchedWS,
    });
  } catch (e) { report('WebSocket', e); }

  const has = (sel) => { try { return !!document.querySelector(sel); } catch (_) { return false; } };
  const state = (reason) => {
    const title = document.title || '';
    const m = /^\((\d+)\)/.exec(title);
    return {
      t: 'state', reason,
      chats: has('#pane-side') || has('[data-testid="chat-list"]') || has('#side [role="grid"]') ||
        has('[aria-label][role="grid"]'),
      qr: has('[data-ref] canvas') || has('canvas[aria-label]') || has('[data-testid="qrcode"]') ||
        has('[data-ref]'),

      loading: has('progress') || has('[role="progressbar"]'),
      unread: m ? Number(m[1]) : 0,
      ready: document.readyState,
      socketsSeen,
      socketsOpen: openSockets.size,
      visible,
    };
  };

  let lastSent = '';
  const push = (reason) => {
    const s = state(reason);
    const key = JSON.stringify({ ...s, reason: '' });
    if (key === lastSent && reason === 'change') return;
    lastSent = key;
    send(s);
  };

  let pending = 0;
  const soon = () => {
    if (pending) return;
    pending = setTimeout(() => { pending = 0; push('change'); }, 800);
  };

  let watchedTitle = null;
  const titleObserver = new MutationObserver(soon);
  const watchTitle = () => {
    const title = document.querySelector('title');
    if (!title || title === watchedTitle) return;
    watchedTitle = title;
    titleObserver.disconnect();
    titleObserver.observe(title, { childList: true, characterData: true, subtree: true });
    soon();
  };
  const observe = () => {
    try {
      watchTitle();
      if (document.head) new MutationObserver(watchTitle).observe(document.head, { childList: true });
    } catch (e) { report('observer', e); }
    applyRendering();
    push('load');
  };
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', observe, { once: true });
  } else {
    observe();
  }

  bridge.addEventListener('message', (event) => {
    let msg;
    try { msg = JSON.parse(event.data); } catch (_) { return; }
    if (msg.t === 'probe') {
      applyRendering();
      const fired = catchUp();
      if (fired) send({ t: 'caughtUp', n: fired });
      push('probe');
    }
    else if (msg.t === 'visible') { setVisible(!!msg.v); push('probe'); }
  });

  send({ t: 'hello' });
})();
