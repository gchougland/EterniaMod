// Original editorial landing page; artwork lives in /media and /icons.
export function landing(view){
 document.body.classList.add('landing-page');
 view.innerHTML=`
 <section class="realm-hero" aria-labelledby="welcome-title">
  <img class="realm-landscape" src="/media/eternia-valley.svg" alt="An illustrated valley of cottages, a guild hall, and a glowing gateway beneath the mountains" width="1400" height="880" fetchpriority="high">
  <div class="realm-copy"><div class="eyebrow"><span class="tiny-diamond"></span> A shared adventure in Hytale</div>
   <h1 id="welcome-title">Go far.<br>Come <em>home.</em></h1>
   <p>For the explorers who stay a little longer.<br>The builders who make a place their own.<br>And the friends who turn a world into home.</p>
   <div class="actions"><a class="button primary" href="#discover">Discover Eternia <span aria-hidden="true">↓</span></a><a class="realm-link" href="/account">Your adventurer profile <span aria-hidden="true">↗</span></a></div>
   <div class="realm-note">A Hytale community to call home <span aria-hidden="true">✦</span> Currently in development</div>
  </div>
  <div class="art-caption">THE ETERNIA VALLEY <span>Illustration of our world</span></div>
 </section>
 <div class="realm-ribbon" aria-label="The Eternia experience"><span>Adventure together</span><i aria-hidden="true">✦</i><span>Build something yours</span><i aria-hidden="true">✦</i><span>Progress at your pace</span></div>
 <section class="realm-section" id="discover" aria-labelledby="discover-title"><div class="section-heading"><div><p class="eyebrow">More than a place to pass through</p><h2 id="discover-title">Put down roots.<br>Write your own story.</h2></div><p>Set out through the Hub, return with a story, and add another little piece to the place you call home.</p></div>
 <div class="realm-features">
  <article class="realm-feature"><div class="feature-art home-art" aria-hidden="true"><img src="/media/eternia-valley.svg" alt=""></div><div class="feature-copy"><span class="feature-number">01 / YOUR OWN CORNER</span><h3>A doorstep with your name on it.</h3><p>Start with a free housing plot. Choose your house, lay a garden path, and fill the rooms with the things you find along the way.</p><a href="/owned">Explore your collection <span aria-hidden="true">↗</span></a></div></article>
  <article class="realm-feature"><div class="feature-art adventure-art" aria-hidden="true"><img src="/media/eternia-valley.svg" alt="" loading="lazy"></div><div class="feature-copy"><span class="feature-number">02 / A WORLD BEYOND YOUR DOOR</span><h3>There is always another road.</h3><p>Meet at the Hub, step through a portal, and set out together. Bring back stories, treasures, and something new for your home.</p><a href="/seasons">Find your next chapter <span aria-hidden="true">↗</span></a></div></article>
 </div></section>
 <section class="guild-story" aria-labelledby="guild-build-title" data-ready="false">
  <div class="guild-sticky"><div class="guild-build-copy"><p class="eyebrow">A place for your people</p><h2 id="guild-build-title">Good company.<br>Great <em>foundations.</em></h2><p>A gathering place. A shared ambition.<br>A hall that becomes the heart of your neighborhood.</p><p class="guild-scroll-hint">Scroll to build Eternia Guild Hall <span aria-hidden="true">↓</span></p><a class="realm-link" href="#chapters">Continue the story <span aria-hidden="true">↗</span></a></div>
  <figure class="guild-build-figure"><div class="guild-build-stage" role="img" aria-label="Eternia Guild Hall rises from its foundations as you scroll, from stone walls to its blue roof."><div class="build-orbit" aria-hidden="true"></div><img class="build-poster" src="/media/guild-build/47.webp" width="768" height="768" loading="lazy" alt="Eternia Guild Hall with its entrance facing forward"><canvas width="768" height="768" aria-hidden="true"></canvas></div><figcaption><span data-build-label>Eternia Guild Hall</span><div class="build-track" aria-hidden="true"><span></span></div><button type="button" hidden aria-pressed="false">See completed hall</button></figcaption></figure></div>
 </section>
 <section class="realm-chapter" id="chapters" aria-labelledby="chapter-title"><div class="chapter-seal" aria-hidden="true"><img src="/icons/eternia.svg" alt="" width="128" height="128"><span>I</span></div><div><p class="eyebrow">Every season is another chapter</p><h2 id="chapter-title">Life happens.<br>Your adventure can wait.</h2><p>Quest, explore, and earn rewards through ordinary play. Earlier season passes stay available, so you can return to your story whenever you are ready.</p><a class="button" href="/seasons">Explore season passes <span aria-hidden="true">↗</span></a></div><div class="chapter-detail" aria-hidden="true"><span>EXPLORE</span><i>✦</i><span>COLLECT</span><i>✦</i><span>BELONG</span></div></section>
 <section class="realm-invitation"><img src="/icons/eternia.svg" alt="" width="54" height="54"><p class="eyebrow">The next chapter is yours</p><h2>Every great adventure<br>needs a place to come back to.</h2><a class="button primary" href="/account">Visit your account <span aria-hidden="true">↗</span></a><p class="muted">Hytale sign in connects your character, collection, and progress.</p></section>`;
 import('./guild-build.js').then(({mountGuildBuild})=>mountGuildBuild(view.querySelector('.guild-story'))).catch(()=>{view.querySelector('.guild-story').dataset.fallback='true';});
}
