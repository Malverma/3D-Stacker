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
- **Fill the board (gap rule):** If any cell of the 3×3 board falls **more than 10 levels** behind the tallest column, the game ends immediately with "BOARD NOT FILLED", even if hearts are left. This includes an empty cell when the tower passes 10. Once a column trails by 7 or more, a pulsing red pillar marks it, and a banner reads "FILL THE GAPS! N levels before you lose".

---

## 5. Controls (Touch)

The camera looks at the board from a fixed diagonal angle, as in the sketch. Movement is mapped to the board's two visible diagonal axes, so a swipe moves the piece in the direction the player sees on screen.

| Gesture | Action |
|---|---|
| **Swipe up-right / down-left** | Move piece ±1 cell along the board's X axis |
| **Swipe up-left / down-right** | Move piece ±1 cell along the board's Z axis |
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

### Special blocks

Specials start appearing after the first 4 pieces and never come twice in a row. Each spawn has a 5% chance of a Bomb and an 8% chance of an X piece. A short banner explains each one when it appears.

| Block | Look | Effect |
|---|---|---|
| **Bomb** | Black cube with a lit bomb | When it lands, it explodes. It destroys every cube within one cell sideways of where it landed, from 1 layer below it to 1 layer above it. Cubes above the blast drop down to fill the hole. **Never costs a heart.** +15 points per cube destroyed. |
| **X piece** | A 1×2 domino or 1×3 bar with a red X cube on one end | The plain cubes stack like a normal piece. The X cube never joins the tower. If it lands on the board, you **lose a heart** and it shatters. If it hangs off the edge, it falls away safely and scores +25 ("DODGED!"). So you have to rotate and slide the piece until the plain cubes are on the board and the X end is off. Like any piece, at least one cube must stay over the board. A piece costs at most 1 heart, even if the X lands on the board and plain cubes also spill. The X cube's landing shadow is always red. |

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
- Fall speed starts at **1 layer per 1.0 s** and **speeds up smoothly as the score rises**. The fall interval is multiplied by 0.98 for every 250 points, applied continuously, down to a floor of **0.25 s**. That works out to about 0.85 s at 2,000 points, 0.67 s at 5,000 and 0.45 s at 10,000. Score never goes down, so bombs can't slow the game back down.
- Larger pieces (Square, Tee) appear more often as height increases. This is optional tuning.

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
- When a run ends, you earn **1 coin per 100 points** (rounded down). A score of 1200 gives 12 coins.
- Coins are saved on the device. The balance shows on the main menu and in the shop, and the game-over screen shows "+N COINS".

### Revive (rewarded ad)
- The first time you run out of hearts in a run, the game pauses on an **"OUT OF HEARTS"** offer with two buttons:
  - **WATCH AD:** continue with 1 heart.
  - **NO THANKS:** end the run.
- This is offered once per run, and only for running out of hearts. A "board not filled" loss ends the run immediately.
- *Placeholder:* no ad SDK is integrated yet, so WATCH AD grants the heart right away. The ad SDK call goes in `GameScreen.watchAd()`.

### Shop
The SHOP button is on the main menu, under Play and Settings. It has three tabs:

| Tab | Items | Price |
|---|---|---|
| **Backgrounds** | Classic Sky (default) and **Neon Nights**, a synthwave pink/purple gradient with stars from the start | Free / 100 coins |
| **Blocks** | Classic Mix (default, one color per piece type), plus 6 single-color sets that paint every block one color: All Ruby, All Gold, All Mint, All Ocean, All Grape, All Snow | Free / 100 coins each |
| **Specials** | **Heart Block** | 250 coins |

- Buying a background or block set equips it right away. Owned items can be re-equipped at any time, and only one of each kind is active.
- Bomb, X and heart cubes always keep their printed faces, whatever block colors are equipped.
- **Heart Block:** once unlocked, each spawn has a **1% chance** of being a Heart Block (a pink 1×1 cube with a heart). It stacks like a normal cube and stays in the tower. When it lands on the board it **restores 1 heart**, unless you already have 3. If it falls off the board, nothing happens.

## 9. Screens & UI

1. **Main Menu:** title, coin balance, High Score and Best Height, and the Play, Settings and Shop buttons.
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
- Rotating the camera view in 90° steps
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
- Gap rule: lose if any cell trails the tallest column by more than 10 levels (§4).
- Engine: libGDX with Kotlin (§11).
