import { defineConfig } from 'vite';
import scalaJSPlugin from 'vite-plugin-scalajs-ascent';

export default defineConfig({
  plugins: [
    scalaJSPlugin({ cwd: '../..', projectID: 'ascent-fixture' }),
  ],
  server: { open: false },
});
