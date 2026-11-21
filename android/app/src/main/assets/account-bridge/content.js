(() => {
  let lastToken = "";
  let sending = false;

  const readToken = () => {
    try {
      const raw = localStorage.getItem("userToken");
      if (!raw) return "";
      const parsed = JSON.parse(raw);
      const value =
        parsed && typeof parsed === "object" && "value" in parsed ? parsed.value : parsed;
      return typeof value === "string" ? value.trim() : "";
    } catch {
      return "";
    }
  };

  const report = async () => {
    if (sending) return;
    const token = readToken();
    if (!token || token === lastToken) return;
    sending = true;
    try {
      await browser.runtime.sendNativeMessage("browser", {
        type: "platform-token",
        token,
      });
      lastToken = token;
    } catch {
      // The next interval retries without persisting the Platform token.
    } finally {
      sending = false;
    }
  };

  void report();
  setInterval(report, 1000);
  window.addEventListener("pageshow", report);
})();
