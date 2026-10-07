# 302 redirects, not 301

Live Short Links answer follows with `302 Found` rather than `301 Moved Permanently`. Clicks are counted server-side, and a 301 is cached permanently by browsers and CDNs — those Clicks would vanish from analytics and the redirect could never be corrected. The cost is a few ms of repeat-visit latency and no SEO rank transfer; a short `Cache-Control` header can reclaim the latency without revisiting this decision.
