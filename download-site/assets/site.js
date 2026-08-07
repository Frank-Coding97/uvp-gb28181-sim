(function () {
  "use strict";

  var copyButton = document.querySelector("[data-copy-sha]");
  if (copyButton) {
    copyButton.addEventListener("click", function () {
      var value = document.querySelector("[data-release='sha256']");
      var feedback = document.querySelector("[data-copy-feedback]");
      if (!value || !feedback) return;
      var text = value.textContent.trim();
      if (!navigator.clipboard) {
        feedback.textContent = "当前浏览器不支持自动复制，请长按或手动选择校验值。";
        return;
      }
      navigator.clipboard.writeText(text).then(function () {
        feedback.textContent = "SHA-256 已复制。";
      }).catch(function () {
        feedback.textContent = "复制失败，请手动选择校验值。";
      });
    });
  }

  if (document.body.dataset.page !== "mobile") return;
  fetch("../releases/mobile.json", { cache: "no-cache" }).then(function (response) {
    if (!response.ok) throw new Error("release metadata unavailable");
    return response.json();
  }).then(function (release) {
    var nodes = document.querySelectorAll("[data-release]");
    nodes.forEach(function (node) {
      var field = node.dataset.release;
      if (field === "size" && release.bytes) {
        node.textContent = (release.bytes / 1000000).toFixed(1) + " MB";
      } else if (release[field]) {
        node.textContent = release[field];
      }
    });
    var download = document.querySelector(".mobile-download-button");
    if (download && release.downloadPath) download.href = release.downloadPath;
  }).catch(function () {
    document.documentElement.classList.add("release-fallback");
  });
}());
