# Changelog

## [1.2.0] - 11/19/2026

### Added

- Production stations. Every player gets a Mine Shaft to place on their plot from Decorations. It digs ore while you are online anywhere in Eternia. Collect from its crate to watch the ore fly out, then spend it at its signboard on levels, tools and talents. New tiers change how the mine looks. There are rare finds to discover, golden moments to catch, and daily orders that pay Seals for your Estate. Open Production in Home in your Journal to see all your stations, orders and Seals at a glance.
- House and production hexagons. Your plot starts with a house hexagon and one free production hexagon that you place beside it. You can have up to 4 house hexagons and 6 production hexagons. Each production hexagon holds 2 stations, and a plot can have one station of each kind. Stations go on production hexagons and your house goes on house hexagons. The Claim Map marks them with a house or a pickaxe and asks which kind you want when you add one.
- You can own up to 5 plots. Your first plot is free and each extra plot costs 1,500 Crowns. Every plot has its own hexagons and stations, and My Plots lists them all.
- The Foundations free pass gives every plot one more free production hexagon at level 10.
- Guilds get their own Crowns. Any member can give Crowns to the guild, and they stay with the guild. Members whose role may spend guild Crowns use them on more guild plots and hexagons. Find it under Guild estate, Guild Crowns.
- A guild plot starts with 4 house hexagons and 2 free production hexagons, and can grow to 10 house hexagons and 12 production hexagons for 1,000 guild Crowns each. A guild can have 2 plots; the second costs 2,500 guild Crowns. Guild plots can hold 2 stations of each kind.
- Each adventure world now keeps its own inventory and memories. Whatever you carry and remember in the Hub and the housing worlds stays there, and you pick up your adventure gear and memories again when you return to that world.
- Type /hub to go back to the Hub from anywhere.
- Titles now come with a colour. Your title and name show in that colour in chat and above your head.
- Ranks: Admin, Mod, Builder, Streamer, Founder, Wanderer, Starborn, Celestial, Cosmic and Eternal. Each comes with a coloured title you can wear from your Collection.
- Moderators can now kick players and mute them in chat, for a set time or until unmuted.
- Builders can turn on building in housing worlds as well as the Hub.
- The name above a player's head now shows their title on a small line above it and their guild underneath. Turn on Display Combat Text in the game's settings to see them.
- Everyone who plays the alpha test earns the Alpha Tester title, and keeps it when the beta starts.
- Playtesters get free starting Crowns and Coins the first time they join, to try everything out.
- Admins can give houses, decorations, stations and collection items from a menu with `/e admin give`. Type a player's name or pick someone online, then press Give.
- Three new starter homes: the Darkwood Cabin, the Lightwood Cabin and the Log Cabin. Every player gets all three free.
- 17 new decorations, from cozy kitchens and bookshelves to wall axes and a welcome mat.
- Worlds can have a border that players cannot walk past, shown as a glowing force field when you come near it. Admins set it per world with `/e admin border set` followed by how far it reaches from the centre.
- Admins can choose which houses and decorations every player gets free, and which are sold in the Crown store and for how much, with `/e admin catalog`.
- Eternia has its own chat. Press the walk key to open it, pick a channel with the tabs along the top, and add emotes from the smiley button or by typing a name between colons, like :heart:. New messages pop up for a few seconds while it is closed.
- New plots are levelled for you. A freshly claimed hexagon is flattened to the height of the road beside it, and every hexagon you add later matches the rest of your plot. A guild's estate and its members' plots share one height. The edges either blend gently into the land around them or rise as a neat hexagon step. Trees, bushes, wild wheat, berry bushes, flowers and rubble are cleared while grass stays, on any kind of ground. A tree cut by your plot is removed whole, so no pieces are left floating beside it. Crops growing on farmland are left alone. Buildings, roads and other plots are never changed.
- You can sell a hexagon back from your plot and get its Crowns back. Open Home in your Journal and press Sell a hexagon. Decorations on it go back to your inventory, and its ground goes back to how it was.
- Staff can grow a town's roads in one go. Stand by the Hub plaza and type `/e admin town roads grow`, or stand where a new town should go and type `/e admin town portal` to set down a spawn platform with a portal and roads around it. The roads branch out and bend around hills, plots and the plaza, cross rivers on wooden bridges, and leave room for homes between them. Build every road at once, or let the town grow: new roads then appear as homes are claimed, whenever free land beside the roads runs low. Each road smooths its whole hexagons into one gentle slope and clears the trees, bushes and crops there first. A map of the planned roads and bridges shows before anything is built, and `/e admin town roads undo` takes the nearest town's roads away again and puts the land back. Portal towns show up by name on the world map and in every portal's travel menu, and `/e admin town rename` gives the nearest one a new name. `/e admin town roads clear` takes a town's roads away but keeps the town, so new roads can be grown.

### Fixed

- Packing up a building or plot no longer refuses because a door was opened, a light was lit or a crop grew. If a block really was swapped out, the message now says where.
- Picking up a house or decoration, packing or moving a plot, removing a path and finishing work that was cut short no longer stop because blocks there were placed or broken since. They carry on and put the saved land back.
- A path from your house now reaches the road however far it is across your hexagons, crossing the road's own hexagon to meet it. It follows gentle one block steps in the ground, clears grass and flowers in its way, goes around your house and decorations, and can start right at your door.
- Menus close once you press their button, so you can see what happens next.
- Packing up your house now packs the decorations and additions inside it for you first, instead of asking you to do it.
- Plot borders glow all the way around, including over paths, planks and paving, along small steps in the ground and beside fences.
- Moving your plot no longer fails because the land changed shape, a road was built next to it, or an animal or dropped item was standing on it.
- Moving your plot steps you onto the road beside it instead of sending you to another world.
- Records now shows the Journal tabs along the top, like every other page.
- Buying, selling and moving hexagons, and packing up a plot, are much faster.
- Making a world the Hub in world setup now works when an old test world still held the Hub role, so Hub building can be turned on there.
- The hexagon under your mouse on the claim map is outlined whole, instead of with its top and bottom cut off.
- Wiping a test world's plots now also packs up the houses, decorations, stations and paths on them back to their owners and puts the land back to how it was when the plot was claimed.

### Changed

- Extra hexagons cost 500 Crowns each instead of coins. Selling a hexagon gives back what you paid for it, and free hexagons give nothing back.
- Plots can no longer be claimed beside a guild. Every player plot stands on its own.
- New quick access decorations in the Crown Store: a Season Board, Market Stall and Production Ledger. Place one on your plot to buy season passes, visit the market or check production right there, without going to the Hub.
- The Crown Store is now a tab in your Journal, so you can shop from anywhere.
- Every player now gets a free Mailbox and a free Crown Store Kiosk for their plot. Mail is read and sent there, and its flag goes up when you have new mail. Letters arrive as envelopes, you can pack items straight from your bag, and letters can use the same emotes as chat.
- A new guild menu. Browse every guild, see who is online and which world they are in, and join, ask to join or accept an invitation in one click. Start a guild with a name, a short tag, a motto and your own banner, made from 24 colours, 26 patterns and 53 emblems. Leaders choose whether anyone can join, players ask first, or it is invite only. Your guild's page shows who is online and where, the latest notices, and every member with their role and when they were last seen. Officers answer join requests and change roles from there.
- Friends. Open Friends in your Journal to send friend requests by name, accept or decline them, and see which friends are online and which world they are in, or when they were last seen. Choose what each friend may do on your plot: visit and use doors, or open chests. Visit a friend's plot when they share it with you, and invite friends to your guild. Friends show first when you write a letter at your mailbox, share land in an adventure world, or invite players to your guild. You get a short note in chat when a friend comes online, which you can turn off. Blocking a player stops their friend requests, mail and guild invites.
- Random teleport. At any public portal, choose Random spot in an adventure world to land somewhere new on dry ground at the surface, inside the world's border. Your first trip is free, and each one after costs 100 Coins. You only pay once a safe spot is found, and get your Coins back if the trip fails.
- Teleport requests. Type /tpa or /tpask and a player's name to ask to teleport to them, or /tpahere to ask them to come to you. They answer with /tpaccept or /tpdeny. Requests last two minutes, and after a teleport goes through you can send another request in 30 minutes. Players you have blocked cannot send you requests.
- The Journal still shows your season passes and lets you choose which one is active. New passes are bought at the Hub or a Season Board.
- Guilds can buy a Guild Notice Board and a Guild Treasury as charters. Redeem them for your guild and place them from Guild decorations. Any member can use them to see the guild page or give Coins and Crowns to the guild.
- Claim land in adventure worlds. Open the land map with `/e land map` (or `/e claim` while in an adventure world) and pick open hexagons to make them yours. Nobody else can break, place, open chests or use things on your land, and explosions leave it alone.
- Your first hexagon of land is free. After that land costs Coins, and each new hexagon costs a little more than the last, up to a set number of hexagons. Giving a hexagon up gives back half of what it cost.
- A gap is kept around every claim, so nobody can claim right next to someone else's land.
- Guilds can claim land together on the Guild tab of the land map, paid from the guild's Coins. Any member can give Coins to the guild under Guild estate, Guild Coins. Guild roles decide who may claim it, build on it, open its chests and use its doors.
- Share your land with friends from the land map or with `/e land friend NAME`, and choose whether each friend may build, open chests or use doors and benches. You can also let everyone use doors and benches.
- Hexagons are bigger, so one hexagon holds a starter cabin with room to decorate around it. Plots that already exist keep their size.
- The old placeholder house is gone.
- Roads built with the Road Designer use half blocks where they step up one block, so walking up them is smooth.
- Placing the Eternia town now lays one short road out of each gate, lined up with it, instead of the whole road grid. Grow the rest with `/e admin town roads grow`.
- Houses and decorations in Collections have a Place button. Stand on your plot and press it to start placing.
- The Hub walkthrough now takes you to one townsperson at a time. The panel shows which step you are on, who to talk to next and why, and an arrow that points the way with how far you have to go.
- Hexvane's rules now show in one list you can scroll instead of pages.
- A guild now needs 3 members to claim its estate instead of 5.
- Prowl no longer has a Travel button. Step through the Hub portal to travel to the worlds.
- Home in the Journal is now your whole housing menu on one page. Go to your plot, add or sell hexagons, pack or move your whole plot, and find your houses, decorations, palettes and paths in their own tabs, with your guild estate beside them. The old stack of housing menus is gone.

## [1.1.0]

### Added

- Housing plots are hexagons. A cottage stands on one, a manor on three and a guild keep on seven, and you can grow your plot one hexagon at a time.
- The Claim Map: use your deed, ask Bramble or open Home in your Journal to see the land around you as a map with every hexagon coloured. Roads stand out, free land beside a road glows green, and clicking a hexagon tells you whose it is, what it costs and whether you can claim it. No more walking a deed around.
- Add a hexagon from My Plots on the same map. Each hexagon you add costs a little more than the last.
- Your plot, your guild's land, your neighbours and other guilds show on the world map in their own colours with each plot's name written inside it, and plot borders glow in those colours in the world.
- Holding your deed shows faint green marks on free hexagons beside the road nearby.
- A packed home can be set down again on any free hexagons of the same shape, chosen on the Claim Map.
- Each hexagon holds up to 24 decorations. Grow your plot to place more.
- Varek Dawnmantle, an Outlander who keeps the Gatehouse, looks after season passes. See all your passes at once, switch your active pass or claim every reward with one click, take on daily and weekly bounties, and find the premium track.
- Season passes and season items in the Crown Store can be bought only while their season runs. The store and the website show when each one opens or closes, and anything you already own stays yours forever.
- Chat channels: talk to everyone, your world, your party, your guild or players nearby. Type `/e c` to see them, `/e c p` to switch to party chat, or `/e c p hello` to send just one message. Starting a line with `@p` sends it to your party without switching. Your channel is remembered next time you log in.
- Adventure Records: type `/e stats` to see your chests opened, blocks travelled, creatures and bosses defeated, times fallen and time in Eternia. Add a name to see another adventurer's records, or open them from the Records button in your Journal.
- The first adventurer to defeat each boss on each difficulty is announced to everyone and remembered as a Server First.
- 33 new titles inspired by the creatures of Orbis, from the Trork Slayer to the Chicken Whisperer.
- Staff can ban rule breakers for a set time or for good, lift bans and look up ban records. Repeat offences get longer bans, and banned players see why and when they can return.
- The website home page shows how many adventurers are online right now.
- The Crown treasury says clearly, before you pay, that Crowns are digital goods and all sales are final.
- The website now sends visitors who type www in front of the address to the main site.
- A Hub walkthrough for new adventurers: meet every townsperson, hear what each one looks after, then return to Prowl to choose your world. A small panel in the Hub shows who you still need to meet. Type `/e tutorial hide`, `skip` or `restart` to change it.
- Every Hub character greets you in their own words the first time you meet, and with a shorter hello after that.
- Hexvane, the Master of Arms, explains the rules of the realm.

### Changed

- The Packaging Wand makes the decoration you aim at glow gold, and packing it makes it vanish in a swirl of sparkles.
- Decorations are placed by hand. Choose Place in your Decorations and you carry it as a box while a see through preview follows where you look. Right click sets it down, R turns it and Q puts it away. A small panel shows whether it fits there.

### Fixed

- The game is less likely to crash right after joining near busy parts of town.
- Accept invite on the Guild page works without typing a code and joins the guild that invited you.
- Packing a decoration with the Packaging Wand no longer gets stuck. If packing was interrupted earlier, it finishes on its own when you open your housing menu, place or pack something, or press Fix my plot in My Plots. The decoration goes back to your Decorations.
- Doors, beds and benches in your own home keep working even while something on your plot is unfinished.
- A packed decoration can face any direction when you place it again. One that still holds items goes back facing the way it was packed.
- The Change shape button on the plot claim page now switches a guild estate between square and long shapes. Personal plots say they are always square.

## [1.0.0]

### Added

- Eternia runs on Hytale Update 7 (0.7.0).

- Loot chests found out in the world now hold separate loot for every player. Your chest stays as you left it, and it does not refill. These chests can no longer be broken. Chests that had already appeared in explored areas stay shared.

- Road Designer includes node markers, saved road outlines, selection and deletion, and a paving style workshop. Roads can overlap and cross, and NPCs or dropped items do not block building. Any public road supports nearby plots without a portal connection; house paths do not count.

- New players receive 500 Coins when they first join Eternia.
- Memories now belong to each player. You collect and record your own, and recipes that need memories unlock from your own count. Memories recorded earlier were shared by everyone, so they have been cleared and everyone starts fresh.
- Admins can grant Crowns and Coins to players by name, with each grant recorded.
- Admins can set up prefabs for the website in game: pick a prefab in the prefab browser, give it a name and details, list it in the Crown Store and add it to a season pass. Saving sends it to the website automatically.
- Admins can design season passes in game with `/eternia season`: levels, rewards and dates. Drafts go to the website as they are saved, and a pass goes live in game when it is ready.

- Admins can turn Hub building on and off for themselves while homes, roads and service areas stay protected.
- Admins can turn natural enemy and animal spawning and goblin breaches off or on in the Hub with `/eternia admin setup spawns`.
- The Gilded Gull, a furnished harbour tavern for the Hub, is ready to place from the prefab browser. Its hearth warms visitors and its tower has a lookout over the town.
- A new Hub town square is ready to place from the prefab browser. Players arrive under a tall gate tower in the middle of the square, with the Waygate to other worlds straight ahead.
- Admins can build the whole town in one go with `/eternia admin town place`: it settles into the land where you stand, with streets, houses and walls that rise and fall with the hills, and lays wide roads out of town for players and guilds to build along. `/eternia admin town undo` puts the land back.
- A walled Old Town surrounds the square: streets of houses and shops, a chapel, a smithy, a bakery, warehouses and green corners with a fountain and wells, inside stone walls with towers and three great gates.
- Every Hub character has a home on the square: the Wayfarer's Lodge for Prowl, the Builders' Hall for Bramble, the Guildhall for Torren, the Market Exchange for Nima, the Crown Store for Lyra and the Games Hall for Pip. Each has its own welcome as you step inside.

- Guild Waygates include an optional glowing portal for the guild estate, placed and packed like a decoration. Step inside to choose a destination. Public and guild portals share teal arches, translucent facets, gold gems and turning runes.

- Press the game mode key (O by default) to open your Journal anywhere: collection, quests, seasons, mail, guild and home, in a full screen view that keeps the world visible behind it.
- Joining Eternia takes you to the right place: new players start in the Hub, players who have met the Hub characters wait in the housing area until they claim a plot, and players with a home log in at their house. Log back in within 10 minutes and you appear exactly where you left off instead.
- Returning to an adventure world puts you back where you left it. Your first visit starts at the world's spawn.
- The Eternia Home Portal takes you straight back to your house from anywhere it is placed.
- Admins can choose where brand new players appear and where players without a plot appear with `/eternia admin setup firstjoin` and `newcomer`. World setup also lets admins choose the world name shown in portals and menus and add a description to its portal card.
- Houses and decorations can be turned and zoomed in 3D in the Crown Store and from your Collection.
- Each Hub character now runs only their own trade: Bramble Hearthleaf for homes, Torren Ironbough for guilds, Nima Quicktail for the market, Prowl for getting started and travel, and Pip Coppercap for minigames.

- Illustrated menus for the Architect's Ledger, travel, and housing inventory, with decorations you can browse anywhere. My Plots can recover interrupted house placements and restore access to the house.

- Framed menu balances and price icons make Coins and Crowns easy to tell apart.
- Season passes hang as banners in the Journal, each with its own crest and colour, your level and the next reward at a glance. Open one to see every level on a single track, claim rewards and check your quests.
- Season quests include 30 daily and 15 weekly goals, with fresh choices on real time resets. Completion awards Coins and XP with a toast and sound.

- Distinct category icons and item previews in the Crown Store, Collection, and season rewards. Collections have compact six column cards with separate categories for houses, decorations, materials, titles, wardrobe, pets, mounts and upgrades.

- The Crown Store has twenty new furnishings for homes and gardens, such as a dining table laid for supper, a cosy bed, a fire pit and a garden well.
- The Corn Buddy is a new pet in the Crown Store. This cheerful little ear of corn walks along behind you, hops with joy when it catches up, looks around, dances, and naps when you stand still for a while. A Crowned Corn Buddy with a little golden crown is also available.
- Seven new houses for plots: three cottages and townhouses for free plots, a manor and a tower house for larger plots, and a lodge and a keep for guild estates.

- Prowl Shirt is the first paid Foundations reward and can be worn from your Wardrobe.
- Everyone receives a Horse in their Mounts collection. Summon it to ride and dismount to send it away.

- Prowl offers a free housing toolkit with a deed and packaging wand.
- Eternia Guild Hall is available for guilds and in the example village.
- Illustrated website welcome page with a guild hall that builds as you scroll and a new Eternia icon.

- The website shows every season pass as a hanging banner. Choose one to see its free and premium rewards with pictures, and your level and progress when signed in.
- Crown treasury on the website with current package prices, account balances, and secure Tebex checkout.
- Nearby plot edges glow with a continuous rune line and gently rising lights.
- **Hub plots** â€” Admins can mark plot areas and assign them to players, including assigning the plot you are standing in. Use `/eternia plots show` to toggle plot outlines. Plot previews can be positioned while account access loads.
- **Building placement** â€” Use a building item in your plot to place and move a preview before building.
- **House** â€” First test building available for hub plots.
- **Props** â€” Place decorations like the Aqua Lamp in your plot using a prop item.
- **Packaging wand** â€” Use the wand to see props in your plot and pack them back into items.
- Prop placement includes a bird's eye view, movement that follows your view, and a red highlight for blocked spots.
- **Prefab browser** â€” Use `/eternia prefab browse` to browse server prefabs and create props or buildings from them.
- Props and buildings now place prefab entities (NPCs, items, etc.) and remove them when packaged or picked up.

### Fixed

- The game no longer crashes when entering instances such as the Forgotten Temple or the Deepworks.
