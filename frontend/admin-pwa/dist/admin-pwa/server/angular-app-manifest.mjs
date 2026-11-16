
export default {
  bootstrap: () => import('./main.server.mjs').then(m => m.default),
  inlineCriticalCss: true,
  baseHref: "/",
  locale: undefined,
  routes: [
  {
    "renderMode": 2,
    "route": "/"
  }
],
  entryPointToBrowserMapping: undefined,
  assets: {
    "index.csr.html": {size: 712, hash: "349408ce60832f0cf7b750ef39f452e8d8b819e9cfe999a8ab58a00fc0647f48", text: () => import("./assets-chunks/index_csr_html.mjs").then(m => m.default)},
    "index.server.html": {size: 946, hash: "05f20a845ecf4920d7acd5ffcda7365bc79495531ac824860d263b16966c3564", text: () => import("./assets-chunks/index_server_html.mjs").then(m => m.default)},
    "index.html": {size: 61232, hash: "b264cc0159085db30737369e4300355782862b46f2c9951373b1368c4a0aab92", text: () => import("./assets-chunks/index_html.mjs").then(m => m.default)},
    "styles-EHFYDENW.css": {size: 196, hash: "E8+1yDA9KmI", text: () => import("./assets-chunks/styles-EHFYDENW_css.mjs").then(m => m.default)}
  },
};
