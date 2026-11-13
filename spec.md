# 3D Stacker — Game Specification

## 1. Overview

**3D Stacker** is a simple, endless 3D stacking game for Android. Shapes made of cubes fall from the sky onto a small 3×3 board, Tetris-style. You steer each falling shape so that all of its cubes land on the board. Any cube that misses the board falls off into the void and costs you a heart. You have 3 hearts. The aim is to stack as high as you can for a new high score.

As the tower grows, the camera rises with it and the background color slowly changes, so you can see how high you have climbed.

### Reference sketch (`example.png`)

The rough draft shows:
- A flat **3×3 grid board** drawn in an isometric / three-quarter view.
- A **2×1×1 piece** (two cubes side by side) falling toward the board, with arrows marking where it will land.
- A plain **cyan sky background**, which is the starting background color.

---

## 2. Core Concepts

| Term | Definition |
|---|---|
| **Board** | A fixed 3×3 grid of cells at ground level (layer 0). It is the footprint of the tower. Nothing exists outside it. |
| **Cell** | One 1×1 square of the board footprint. Addressed as `(x, z)` with `x, z ∈ {0,1,2}`. |
| **Layer** | A horizontal slice of the tower at height `y` (y = 0 sits on the board). |
| **Cube** | The unit block, 1×1×1. |
| **Piece** | A rigid group of cubes that falls together (for example 2×1×1). |
| **Stack / Tower** | All cubes that have landed and are resting on the board. |
| **Heart** | A life. You start with 3. Losing all 3 ends the game. |

---

## 3. Gameplay Loop

1. A new **piece spawns** above the tower, centered over the board, at least 4 layers above the current highest cube.
2. The piece **falls** steadily, one layer per tick.
3. While it falls, the player can **move** it horizontally and **rotate** it (see §5).
4. The piece **lands** when any of its cubes would move into an occupied cell or below layer 0.
5. On landing, the piece breaks apart and each cube is resolved on its own (see §4):
   - Cubes **over the board footprint** drop straight down until they rest on a cube or the board, then lock into the stack.
   - Cubes **outside the footprint** detach, tumble off with a physics-style animation, and disappear.
6. If one or more cubes fell off, the player **loses 1 heart**. This is per piece, not per cube.
7. The score and tower height update, and the camera and background adjust (see §7).
8. If hearts > 0, go to step 1. Otherwise, **Game Over**.

---

## 4. Placement & Landing Rules

- **Rigid while falling:** A piece keeps its shape while falling. It stops as a whole as soon as *any* of its cubes is blocked, as in Tetris.
- **Cubes settle after landing (no gaps):** Once the piece has landed, it breaks into separate cubes. Any on-board cube with empty space under it falls straight down until it rests on another cube or the board. This means the tower never has gaps or overhangs: every column is solid from the board up.
  - *Example:* A 2×1×1 domino lands with one cube on a column 3 high and the other above a column 1 high. The first cube locks at layer 3, and the second drops down and locks at layer 1.
  - The settling drop is animated quickly (about 0.15 s per layer) with a small bounce when the cube lands.
- **Off-board cubes:**
  - A piece may be moved partly outside the 3×3 footprint while falling. This makes risky placement possible.
  - A piece can never be moved *completely* off the footprint. At least one cube must stay over the board.
  - When the piece lands, every cube outside the footprint breaks away and falls off-screen. The remaining cubes settle as described above.
  - If a piece loses cubes, the player loses **1 heart**, however many cubes fell.
- **Landing test for off-board cubes:** Only cubes above the footprint can be blocked. Cubes beyond the edge have nothing under them, so the piece's landing height depends only on its on-board cubes.
- **No line clears:** Full layers are *not* removed. Only the Bomb (§6) removes cubes.
- **Fill the board (gap rule):** If any cell of the 3×3 board falls **10 or more levels** behind the tallest column, the game ends immediately with "BOARD NOT FILLED", even if hearts are left. This includes an empty cell when the tower reaches 10. Once a column trails by 6 or more, a pulsing red pillar marks it, and a banner reads "FILL THE GAPS! N levels before you lose".

---

## 4b. No Fall Mode

Started with the **NO FALL** button under Play on the main menu. All the normal rules apply, with these changes:
- **Overhanging cubes grow the board.** A piece still needs at least one cube over the board, as usual. When it lands, any cube hanging off the edge does not fall away. Its cell joins the board, a new tile appears there, and the cube settles into it like any other cube (straight down onto the new tile). The landing shadow is white for those cubes, not red.
- The board can grow up to **6 cells past the starting 3×3 on every side** (15×15 at most). A cube beyond that still falls off and costs a heart, as in the classic game.
- **No gap rule.** New cells start empty, so "BOARD NOT FILLED", the warning pillars and the "FILL THE GAPS!" banner are all off.
- X cubes work as usual: hanging off the edge they fall away safely ("DODGED!"), and they never grow the board.
- **Camera:** the view stays centered on the board as it grows, possibly off to one side, and zooms out smoothly so the whole board stays in frame at every view angle.
- No Fall keeps its own High Score and Best Height. The HUD shows "NO FALL" under the best score. Coins are earned the same way.

## 5. Controls (Touch)

The camera looks at the board from a diagonal angle, as in the sketch. Dragging is mapped through the camera onto the board's two axes as they appear on screen, so the piece follows the finger cell by cell in the direction the player sees, at any view angle. Leftover drag carries over between steps, and a slightly crooked drag doesn't drift onto the other axis.

| Gesture | Action |
|---|---|
| **Drag** (any direction except near-vertical) | Piece follows the finger along the board's X and Z axes, one cell at a time |
| **Press on the board and drag sideways** (or two-finger drag/twist anywhere) | Turn the board view; it follows the finger and snaps to the nearest 90° on release (a short turn still commits one step). Drags that start off the board, or on the falling piece, move the piece. |
| **Tap** | Rotate piece 90° around the vertical axis |
| **Two-finger tap** (or rotate button) | Tip the piece 90° (lay a long piece flat ↔ stand it upright) |
| **Fast swipe down** | Hard drop: piece falls instantly to its landing position |
| **Pause button** (top corner) | Pause the game |

Guidelines:
- A **landing shadow / ghost piece** is drawn on the stack surface below the falling piece. It shows exactly where each cube will land. Cells that are off the board are drawn in **red**.
- Moves and rotations that would push a piece into an occupied cell, or fully off the board, are rejected. The piece gives a small "bump" animation instead.
- On-screen **Rotate**, **Tip** and **Drop** buttons are shown along the bottom by default. They can be turned off in Settings by players who prefer gestures only.

---

## 6. Pieces

All pieces are built from 1×1×1 cubes. MVP set:

| Name | Size / Shape | Cubes |
|---|---|---|
| Single | 1×1×1 | 1 |
| Domino | 2×1×1 | 2 |
| Bar | 3×1×1 | 3 |
| Corner | L-shape, 3 cubes in one plane | 3 |
| Square | 2×2×1 flat slab | 4 |
| Tee | T-shape, 4 cubes in one plane | 4 |

### Large pieces

As the tower climbs, bigger and harder-to-fit regular pieces join the bag. They follow every normal rule and are never more than 3 long on any axis. When a tier unlocks, a "BIGGER PIECES!" banner appears and the new shapes are slipped into the current bag so they show up right away. After that they come once per bag like any other piece.

| Unlocks at height | Name | Shape | Cubes |
|---|---|---|---|
| 10 | Big Corner | Two 3-long arms meeting at a corner | 5 |
| 10 | U | A U shape | 5 |
| 20 | Slab | Flat 3×2 slab | 6 |
| 20 | Stairs | Staircase three steps high | 6 |
| 35 | Arch | Two legs with a beam across the top | 5 |
| 35 | Post Corner | Corner with a two-high post at the joint | 5 |
| 50 | Ring | The 3×3 outline with a hole in the middle | 8 |
| 50 | Big Cube | 2×2×2 block | 8 |

### Special blocks

Specials start appearing after the first 4 pieces and never come twice in a row. Each spawn has a 5% chance of a Bomb and an 8% chance of an X piece, plus the chance of every shop special the player owns (§8b). One roll covers all of them, so the chances add up. A short banner explains each one when it appears.

| Block | Look | Effect |
|---|---|---|
| **Bomb** | Black cube with a lit bomb | When it lands, it explodes. It destroys every cube within one cell sideways of where it landed, from 1 layer below it to 1 layer above it. Cubes above the blast drop down to fill the hole. **Never costs a heart.** +15 points per cube destroyed. |
| **X piece** | A 1×2 domino or 1×3 bar with a red X cube on one end | The plain cubes stack like a normal piece. The X cube never joins the tower. If it lands on the board, you **lose a heart** and it shatters. If it hangs off the edge, it falls away safely and scores +25 ("DODGED!"). So you have to rotate and slide the piece until the plain cubes are on the board and the X end is off. Like any piece, at least one cube must stay over the board. A piece costs at most 1 heart, even if the X lands on the board and plain cubes also spill. The X cube's landing shadow is always red. |

**Shop specials** (only spawn once bought, see §8b). Each one is a single special cube. Half the time it comes alone, and half the time it comes **attached to a regular piece** from the bag: one extra cube on a free side of the piece, wherever the result stays at most **3 long** on every axis (so a bar only takes it on its side, never its end). Pieces are never 4 long.
- A lone special cube can't leave the board entirely, so it always lands, and it never costs a heart.
- An attached piece is a regular piece: if any of its cubes miss the board (including the special cube), it costs a heart and the missed special does nothing.
- Bombs always come alone, because an attached bomb would blow up its own piece. X pieces already come as a domino or bar with an X cube.

| Block | Look | Effect |
|---|---|---|
| **Heart Block** | Pink cube with a heart | Stays in the tower. Restores 1 heart, unless you already have 3. |
| **Golden Block** | Gold cube with a star | Stays in the tower. **+2 coins**, paid out with the score coins when the run ends. The HUD shows the run's bonus coins under the height. |
| **Multiplier Block** | Purple cube with up chevrons | Stays in the tower. Raises the run's **score multiplier by ×0.1** (×1.0 → ×1.1 → ×1.2 …). The multiplier applies to every later landing's points, not to the block's own landing, and resets each run. Shown as "SCORE ×1.2" under the height. |
| **Cracked Block** | Grey stone cube with cracks | Stays in the tower and scores **+40 bonus points**. Each one gets a random fuse of **10–15 layers**: once that many layers sit above it (measured against the tallest column), it crumbles. The cube is removed and everything above it in that column drops one layer, which can unbalance the tower. Bombs can destroy it early. |
| **Mystery Block** | Teal/blue/purple cube with a "?" | Very rare. On landing over the board it turns into a random effect, shown as "MYSTERY: …": **Gold Rush** (becomes a Golden Block worth +10 coins), **Heal** (becomes a Heart Block; only rolled when a heart is missing), **Multiply** (becomes a Multiplier Block worth +×0.3), **Bomb** (explodes like a Bomb, after the rest of its piece has stacked), **Cracked** (becomes a Cracked Block, without the bonus) or **Curse** (shatters like an X cube and costs a heart). |

- Pieces are drawn with a **shuffled-bag randomizer**: every piece type appears once per bag, in random order. This prevents long streaks of the same piece.
- A **"Next" preview** in the HUD shows the upcoming piece.
- Each piece type has its own color. Locked cubes keep their color so the tower looks colorful.

---

## 7. Camera & Background

### Camera
- The camera uses a fixed isometric-style angle (about 35° pitch, 45° yaw) looking at the board's center, matching `example.png`.
- The camera's **target height** follows the **current tower height**. The top of the tower sits about one third of the way up the screen, which leaves room above for the falling piece.
- The camera moves **slowly and smoothly** toward the target (eased, roughly 1 second to settle). It never jumps.
- The camera follows the tower height. It normally only rises, but it eases back down if a Bomb lowers the tower.

### Background
- The background is a vertical gradient. Its colors depend on tower height and **blend smoothly** between key heights:

| Tower height (layers) | Theme | Approx. colors |
|---|---|---|
| 0 | Day sky | Cyan `#00FFFF` → light blue |
| 15 | Afternoon | Sky blue → soft yellow |
| 30 | Sunset | Orange → pink |
| 50 | Dusk | Purple → deep blue |
| 75 | Night | Navy → black, faint stars |
| 100+ | Space | Black with stars; continues cycling slowly through hues |

- Colors are interpolated by height, so every new layer changes the shade slightly.
- Optional extra: ambient decorations at certain heights, such as clouds (~10), birds (~25), and stars (~75).

---

## 8. Scoring & Difficulty

### Score
- **+10 points** for each cube that locks onto the stack.
- **+50 points** each time the tower reaches a new maximum height layer.
- **Perfect placement bonus:** +25 if no cube was lost and no cube had to settle after landing, meaning the piece sat flush on the stack.
- Lost cubes score nothing.
- The **High Score** and **Best Height** are saved on the device and shown on the main menu and Game Over screen.

### Difficulty curve
- Fall speed starts at **1 layer per 1.0 s** and **speeds up smoothly as the score rises**. The fall interval is multiplied by 0.975 for every 250 points, applied continuously, down to a floor of **0.25 s**. That works out to about 0.82 s at 2,000 points, 0.60 s at 5,000 and 0.36 s at 10,000. Score never goes down, so bombs can't slow the game back down.
- Larger pieces join the bag at heights 10, 20, 35 and 50 (see §6, Large pieces).

### Height milestones
- Every 10 layers (10, 20, 30 …) is a milestone. The **first time ever** the tower reaches one, a "NEW MILESTONE REACHED!" banner shows the height and its reward, and the coins go straight into the balance.
- The reward scales: milestone N (height 10 × N) pays **25 × N coins**, so 25 at height 10, 50 at 20, 75 at 30 and 250 at 100. A piece that jumps past several milestones at once pays for all of them.
- The highest milestone reached is saved on the device, so each one pays only once. The game-over "+N COINS" includes milestone coins earned that run.

### Hearts
- Start with **3 hearts**, shown as icons in the HUD.
- Hearts are **never regained** during a run. There is no way to earn hearts back.
- Lose 1 heart for each piece that drops at least one cube off the board.
- When a heart is lost: screen shake, the heart icon breaks, and a sound plays.
- An X cube landing on the board also costs 1 heart. Bombs never cost hearts.
- At 0 hearts: **Game Over** ("OUT OF HEARTS"). The gap rule (§4) can also end the game.

---

## 8b. Coins, Revive & Shop

### Coins
- When a run ends, you earn **1 coin per 100 points** (rounded down), plus any coins from Golden Blocks. A score of 1200 gives 12 coins.
- Coins are saved on the device. The balance shows on the main menu and in the shop, and the game-over screen shows "+N COINS".

### Revive (rewarded ad)
- The first time you run out of hearts in a run, the game pauses on an **"OUT OF HEARTS"** offer with two buttons:
  - **WATCH AD:** continue with 1 heart.
  - **NO THANKS:** end the run.
- This is offered once per run, and only for running out of hearts.
- *Placeholder:* no ad SDK is integrated yet, so WATCH AD grants the heart right away. The ad SDK call goes in `GameScreen.watchAd()`.

### Continue after "Board not filled" (rewarded ad)
- When a run ends with **"BOARD NOT FILLED"**, the Game Over panel shows a **WATCH AD & CONTINUE** button at the top, above the score.
- After the ad, every column that is at the losing gap, or 1 or 2 levels short of it, is topped up with plain filler cubes until it trails the tallest column by **7**, which leaves **3 levels of grace** before the gap rule triggers again. Columns already closer than that are left alone.
- The filler cubes drop in from just above the tower, one column after another and each cube a beat after the one below, so the holes visibly fill up. The next piece spawns once they have landed. A "GAPS FILLED! / 3 LEVELS OF GRACE" banner shows.
- Filler cubes score no points. Offered once per run (separate from the hearts revive). Coins already paid out at the first game over are not paid again; the next game over adds only the coins earned since.
- *Placeholder:* the reward is granted right away. The ad SDK call goes in `GameScreen.watchAdContinue()`.

### Shop
The SHOP button is on the main menu, under Play and Settings. It has three tabs:

| Tab | Items | Price |
|---|---|---|
| **Backgrounds** | Classic Sky (default), **Neon Nights** (synthwave pink/purple, stars from the start), **Desert Dusk** (sandy gold to burnt orange to violet night, with sun), **Aurora** (teal/green aurora bands, stars almost immediately), **Cotton Candy** (pastel pinks and lavenders, with sun) | Free / 100 coins |
| **Blocks** | Classic Mix (default, one color per piece type), plus 5 single-color sets that paint every block one color: Ruby, Gold, Emerald, Ocean, Snow. Two premium sets: **Slime** and **Bubble Gum** (below) | Free / 100 coins each / 300 coins each |
| **Specials** | Heart, Golden, Multiplier, Cracked and Mystery Blocks (§6) | see below |

- Buying a background or block set equips it right away. Owned items can be re-equipped at any time, and only one of each kind is active.
- Special cubes always keep their printed faces, whatever block colors are equipped.
- **Slime:** see-through glossy green blocks. Each block mixes one of four styles (bubbly with a few small white bubbles, clear, a dark core floating inside, cloudy swirls) in slightly varied shades. Blocks jiggle when a piece lands on or near them, and the wobble ripples out a few cells and down the tower; bombs and crumbles shake them too. Landings play a squelch.
- **Bubble Gum:** solid pink blocks in three styles (bubbly, swirled, hot pink). They sag, grow drips down over the block below, and stick together: they stretch to close the gaps between layers and slowly spread wider under the weight of the blocks above. They jiggle slower and gooier than slime, with a lower squelch. All of this is visual only; the tower still lines up with the grid.

#### Special block upgrades
Every shop special starts locked. Buying it unlocks **level 1**, and it can then be upgraded with coins up to **level 5**. Each level adds a little spawn chance. An upgrade from level L costs `price × L / 2` coins (for example, the Golden Block costs 75, 150, 225 and 300). The row shows the current spawn chance and the level as five pips on the swatch.

| Special | Unlock price | Spawn chance at level 1 | Per level | At level 5 |
|---|---|---|---|---|
| Heart Block | 250 | 0.4% | +0.15% | 1% |
| Golden Block | 150 | 2% | +0.5% | 4% |
| Multiplier Block | 300 | 1% | +0.25% | 2% |
| Cracked Block | 100 | 2% | +0.5% | 4% |
| Mystery Block | 500 | 0.3% | +0.1% | 0.7% |

**Balance:** hearts are kept scarce so that losing one still matters. X pieces alone are 8% of spawns. A Heart Block at level 1 heals about once per 250 pieces (roughly once per run), and two or three times per run fully upgraded. A Mystery Block can roll Heal only while a heart is missing. With everything at level 5, the shop specials total about 12% of spawns (Bomb and X add 13%).

Players who bought the Heart Block before upgrades existed keep it at level 1.

## 9. Screens & UI

1. **Main Menu:** title, coin balance, High Score and Best Height, and the Play, No Fall (§4b), Settings and Shop buttons.
2. **In-Game HUD:**
   - Top-left: hearts (♥ ♥ ♥)
   - Top-center: score
   - Top-right: next piece preview and pause button
   - Small height meter along the side, e.g. "Height: 23"
3. **Pause Menu:** Resume, Restart, Settings, Quit to Menu.
4. **Game Over:** final score, height reached, "New High Score!" banner if earned, Retry and Menu buttons. The camera slowly pulls back or orbits to show the whole tower.
5. **Settings:** music and SFX volume, vibration on/off, on-screen control buttons on/off.

---

## 10. Audio & Feedback

- A soft "thunk" when a piece lands, with the pitch rising slightly as height increases.
- A whoosh/fall sound when cubes drop off the board.
- A heart-loss sting and a short haptic buzz.
- Relaxing ambient music that changes subtly with the background theme.

---

## 11. Technical Requirements

| Item | Requirement |
|---|---|
| Platform | Android 8.0 (API 26) and up |
| Orientation | Portrait, locked |
| Engine | **libGDX with Kotlin** (OpenGL ES 3.0). Chosen for the smallest APK, fastest startup and lowest overhead. There is no visual editor; scenes are built in code. |
| Target FPS | 60 fps on mid-range devices, and on low-end devices too |
| Rendering | Simple low-poly cubes, flat or soft shading, a few lights. No heavy assets needed. |
| Persistence | High score, best height, and settings stored locally with libGDX `Preferences` |
| Offline | Fully playable offline. No account needed. |
| APK size | Target < 15 MB |

### Data model (suggested)
- The tower has no gaps (see §4), so the stack is just **9 columns**: `columns: Array<MutableList<Color>>` (size 9, one per cell). Each column's height is its list size, and the colors are kept for rendering. Finding where a cube lands is a single lookup: `columns[x + 3*z].size`.
- `Piece { cells: Array<Vector3i>, position: Vector3i, color }`: the cells are offsets relative to the piece's origin.
- `GameState { score, hearts, towerHeight, bestHeight, fallInterval, bag, nextPiece }`.

### Performance note
The tower is unbounded, so cubes far below the camera's view (e.g. more than 30 layers down) can be merged into a single static mesh or hidden. This keeps the draw-call count low.

---

## 12. Out of Scope for MVP / Future Ideas

- Clearing full 3×3 layers for bonus points (optional "Classic" mode)
- Power-ups (e.g. a "glue" piece, a slow-motion piece). No heart restores.
- Online leaderboards (Google Play Games)
- Unlockable cube skins and background themes
- Daily challenge with a fixed piece sequence

---

## 13. Decisions Log

- Hearts are never regained (§8).
- Cubes settle onto the cube below after landing, so the tower never has gaps (§4).
- Pieces **can stand upright**, using the tip gesture or button (§5).
- Special blocks: the Bomb and the X piece (§6). X pieces are a domino or bar with an X cube on one end.
- Shop specials (Heart, Golden, Multiplier, Cracked, Mystery) are bought and upgraded for spawn chance; the Bomb and X piece stay free core mechanics (§8b).
- Gap rule: lose if any cell trails the tallest column by 10 or more levels (§4).
- Engine: libGDX with Kotlin (§11).
