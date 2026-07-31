package chekhov.jsenv

/** Injected into the page before user scripts. Provides `scalajsCom` + `__chekhovCom` poll bridge. */
object ComSetup:
  val source: String =
    """
      |(function () {
      |  var inbound = [];
      |  var outbound = [];
      |  var callback = null;
      |  var errs = [];
      |  var logs = [];
      |
      |  function capture(level, args) {
      |    try {
      |      var line = Array.prototype.slice.call(args).map(String).join(" ");
      |      logs.push({ level: level, line: line });
      |    } catch (e) {}
      |  }
      |
      |  var origLog = console.log;
      |  var origErr = console.error;
      |  var origWarn = console.warn;
      |  console.log = function () { capture("log", arguments); return origLog.apply(console, arguments); };
      |  console.error = function () { capture("error", arguments); return origErr.apply(console, arguments); };
      |  console.warn = function () { capture("warn", arguments); return origWarn.apply(console, arguments); };
      |
      |  window.addEventListener("error", function (e) {
      |    errs.push(String((e && e.message) || e));
      |  });
      |  window.addEventListener("unhandledrejection", function (e) {
      |    errs.push(String((e && e.reason) || e));
      |  });
      |
      |  window.scalajsCom = {
      |    init: function (cb) {
      |      callback = cb;
      |      for (var i = 0; i < inbound.length; i++) cb(inbound[i]);
      |      inbound = [];
      |    },
      |    send: function (msg) {
      |      outbound.push(String(msg));
      |    }
      |  };
      |
      |  window.__chekhovCom = {
      |    push: function (msg) {
      |      if (callback) callback(String(msg));
      |      else inbound.push(String(msg));
      |    },
      |    fetch: function () {
      |      var out = outbound;
      |      outbound = [];
      |      var e = errs;
      |      errs = [];
      |      var l = logs;
      |      logs = [];
      |      return JSON.stringify({ msgs: out, errs: e, logs: l });
      |    }
      |  };
      |})();
      |""".stripMargin
end ComSetup
