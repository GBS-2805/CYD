// Static render site: the asset layer already serves index.html for "/" and
// redirects "/index.html" back to "/", so rewriting here created a loop.
const handler = {
  async fetch(request, env) {
    return env.ASSETS.fetch(request);
  },
};

export default handler;
