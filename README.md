# Clearness

Something now waits far from spawn. Your compass knows where.
What you do when you find it is your own affair.

Server-side only. Players join with a vanilla client.
Fabric and NeoForge, Minecraft 1.21.1, 26.2 and 26.3. No dependencies.

## What happens

- On the first join, a glass cathedral appears about 600 blocks from spawn.
- At its heart, a sealed chest on an altar. It cannot be broken. Explosions do nothing to it.
- Every player gets a Seeker's Compass once. It points at the cathedral. Right-click it for the distance.
- Open the chest, and the Clearness wakes: a deep sound, a moment of darkness, no title.

Then the world turns to glass.

- It spreads in clusters, never one block at a time.
- It speeds up every day, up to twenty times faster.
- It follows players, in every dimension.
- Using a crafting table, a furnace, an anvil or another workstation feeds it.
- Skipping the night feeds it even more, around the bed.

## What resists

- **Built blocks**: anything with a block entity, stairs, slabs, walls, fences, planks, rails, bricks and the like.
- **Structures**: the actual volume of their pieces, plus one block. Villages, mineshafts, temples keep their exact shape.
- **The ground under buildings**: three blocks of natural terrain beneath any built block, so nothing floats.
- **Around builds**: three blocks around a built block, except natural terrain, which always goes.

The edges stay sharp. That is the point.

## Commands

For operators.

| Command | |
|---|---|
| `/clearness status` | running or not, speed, frontier size |
| `/clearness start [pos]` | start without opening the chest |
| `/clearness stop` | stop the spread |
| `/clearness cathedral` | build a cathedral where you stand |
| `/clearness check` | why the block under you is spared, or not |

## Coming from 2.0

Worlds carry over. A chest that was never opened comes back as the new sealed chest, in the same place.
A Clearness already running keeps running.

## Building

Requires JDK 25 for the Gradle process (the 1.21.1 toolchain, Java 21, is downloaded automatically).

```bash
./gradlew build                             # default line (gradle.properties)
./gradlew build -Pminecraft_version=1.21.1  # any line of the MATRIX in build.gradle
```

## License

MIT
