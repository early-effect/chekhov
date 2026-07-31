// Load the Scala.js ES module via the vendored vite plugin's raw prefix (skips Vite import-analysis).
const s = document.createElement('script');
s.type = 'module';
s.src = '/@scalajs-raw/main.js';
document.head.appendChild(s);
