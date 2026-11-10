# Movies & TV "Tonight" picker

Decided with the user on Nov 10, 2026. **Mockups only, nothing built yet.**

## The problem

The user and his partner get decision paralysis choosing a film or show. The current Movies & TV layout looks like Netflix (rows of posters) and he finds it boring; he wants something that stands out.

Hard requirement: every mode must work with **one remote and one person**, and just as well with two people sharing that remote. Two-phone or two-player ideas were rejected because they fail when watching alone.

## Mockups

The Design canvas "Nuvio: Choosing Tonight" in the user's Claude artifacts has nine 1280x720 TV screens: the launcher plus the eight modes below. Posters there are placeholder colour blocks; real artwork comes from the addons.

## Structure

The Movies & TV tab opens on a **Tonight launcher**:
- eight mode tiles in a 4 x 2 grid;
- the mode used last time is highlighted;
- a "Just browse everything" link keeps the old rows reachable;
- Back from any mode returns to the launcher.

## The eight modes (all kept)

1. **Three Questions**: three full-screen either/or questions, one tap each (e.g. *Laugh or think? Something you know or something new? Under two hours?*). A counter shows the list shrinking ("214 → 41 titles left"); Back skips a question. **When done, it goes straight to Three Cards.**
2. **Three Cards**: only three titles, dealt face up and deliberately different: Safe, New to you, Wildcard. Each shows year, length and finishing time ("ends 23:11"). One reshuffle per night; there is never a fourth card. OK plays; holding OK plays the trailer.
3. **Knockout**: eight titles in a bracket, two at a time head to head, three rounds. Left/Right keeps one; Down plays a 20 s taste of each; Back undoes the last pick. The winner plays straight away.
4. **The Map**: no rows. Titles sit on a field: across is Light ↔ Heavy, up is Comfort ↔ Something new. A glowing lens steered by the remote enlarges the posters near it and blurs the rest; a side panel shows the title under the lens.
5. **Ends By**: starts from the clock ("It's 21:10. Bed at 23:30."). A timeline from now to bedtime shows films as bars and shows as episode blocks (e.g. "3 eps · 22:40"). Anything that runs past bedtime sinks to the bottom, striped. Left/Right moves bedtime.
6. **Channel Flick**: trailers play full screen back to back, like channel surfing. OK plays the title, Left means never show it again, Up saves it for later, Right is the next channel. Builds on the pre-show trailer engine.
7. **Coin Toss**: Nuvio picks one title and starts a 10-second countdown; doing nothing plays it. Left vetoes and tosses again, and the vetoes teach it a lean ("Heat: too long, Tár: too heavy → leaning shorter and lighter").
8. **The Pile**: library items never started, drawn as a stack of spines, newest on top; older ones gather dust and fade. The selected one pulls out with how long it has waited, and offers "Finally watch it" or "Let it go" (off the pile for good).

## Decisions (user, Nov 10)

- **Placement on The Map: rules first, AI refines.** Rules (genre, runtime, rating for Light/Heavy; the user's watch history for Comfort/New) so it works with no AI key; when the AI (Groq) is available it nudges titles, cached per title like the album facts.
- **Ends By is also a filter everywhere**: a bedtime chip on every mode, as well as being its own mode.
- **Build first: the launcher plus Three Questions → Three Cards.**
- Per the standing rule, new screens are built behind a Settings switch, off by default, until the user is happy.

## Not to propose again

- Two-phone "Split Decision" and a Venn of two people's tastes (two-player only).
- Still dead ends from earlier: "The Evening" (a one-button planned night), Blind Date with a Film, Quiz Night.

## Still open

- Exactly which questions Three Questions asks, and whether they vary night to night.
- How the Safe / New to you / Wildcard cards are chosen.
- Where "Let it go" and "Never show me this" are stored, and whether they can be undone.
